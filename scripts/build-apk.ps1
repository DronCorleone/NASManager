param(
    [string]$AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$JdkRoot = "D:\Unity\Hub\Editor\2022.3.62f2\Editor\Data\PlaybackEngines\AndroidPlayer\OpenJDK",
    [string]$Keystore = "",
    [string]$KeystorePassword = "",
    [string]$KeystorePasswordFile = "",
    [string]$KeyAlias = "nasmanager"
)

$ErrorActionPreference = "Stop"
$versionName = "1.3.0"
$versionCode = "4"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$buildRoot = Join-Path $projectRoot "build\manual"
$appRoot = Join-Path $projectRoot "app"
$buildTools = Join-Path $AndroidSdk "build-tools\34.0.0"
$androidJar = Join-Path $AndroidSdk "platforms\android-34\android.jar"

if (-not (Test-Path $androidJar)) { throw "Android SDK platform 34 was not found: $androidJar" }
if (-not (Test-Path (Join-Path $JdkRoot "bin\javac.exe"))) { throw "JDK was not found: $JdkRoot" }

if (Test-Path $buildRoot) {
    $resolvedBuild = (Resolve-Path $buildRoot).Path
    if (-not $resolvedBuild.StartsWith($projectRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean a build path outside the project."
    }
    Remove-Item -LiteralPath $resolvedBuild -Recurse -Force
}
New-Item -ItemType Directory -Force $buildRoot, "$buildRoot\gen", "$buildRoot\classes", "$buildRoot\dex" | Out-Null

& "$buildTools\aapt2.exe" compile --dir "$appRoot\src\main\res" -o "$buildRoot\resources.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }
& "$buildTools\aapt2.exe" link -o "$buildRoot\base-unsigned.apk" -I $androidJar `
    --manifest "$appRoot\src\main\AndroidManifest.xml" --java "$buildRoot\gen" `
    --min-sdk-version 26 --target-sdk-version 34 --version-code $versionCode --version-name $versionName `
    --auto-add-overlay "$buildRoot\resources.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

$javaFiles = @(Get-ChildItem "$appRoot\src\main\java", "$buildRoot\gen" -Filter *.java -Recurse | ForEach-Object FullName)
& "$JdkRoot\bin\javac.exe" -source 11 -target 11 -encoding UTF-8 -cp $androidJar -d "$buildRoot\classes" $javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
& "$JdkRoot\bin\jar.exe" --create --file "$buildRoot\classes.jar" -C "$buildRoot\classes" .

$env:JAVA_HOME = $JdkRoot
& "$buildTools\d8.bat" --lib $androidJar --min-api 26 --output "$buildRoot\dex" "$buildRoot\classes.jar"
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }
& "$JdkRoot\bin\jar.exe" --update --file "$buildRoot\base-unsigned.apk" -C "$buildRoot\dex" classes.dex
& "$buildTools\zipalign.exe" -f 4 "$buildRoot\base-unsigned.apk" "$buildRoot\NASManager-v$versionName-aligned.apk"

$output = Join-Path $buildRoot "NASManager-v$versionName.apk"
if ($Keystore) {
    if ($KeystorePassword -and $KeystorePasswordFile) {
        throw "Use either KeystorePassword or KeystorePasswordFile, not both."
    }
    if ($KeystorePasswordFile) {
        if (-not (Test-Path -LiteralPath $KeystorePasswordFile)) {
            throw "Keystore password file was not found: $KeystorePasswordFile"
        }
        $passwordSource = "file:$((Resolve-Path -LiteralPath $KeystorePasswordFile).Path)"
    } elseif ($KeystorePassword) {
        $passwordSource = "pass:$KeystorePassword"
    } else {
        throw "KeystorePasswordFile or KeystorePassword is required when Keystore is provided."
    }
    & "$buildTools\apksigner.bat" sign --ks $Keystore --ks-key-alias $KeyAlias `
        --ks-pass $passwordSource `
        --out $output "$buildRoot\NASManager-v$versionName-aligned.apk"
    if ($LASTEXITCODE -ne 0) { throw "APK signing failed" }
} else {
    Copy-Item "$buildRoot\NASManager-v$versionName-aligned.apk" $output
    Write-Warning "No keystore supplied: the resulting APK is unsigned."
}

Write-Output $output
