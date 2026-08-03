param(
    [string]$JdkRoot = "D:\Unity\Hub\Editor\2022.3.62f2\Editor\Data\PlaybackEngines\AndroidPlayer\OpenJDK",
    [string]$Keystore = (Join-Path $PSScriptRoot "..\.secrets\nasmanager-release-v1.1.p12"),
    [string]$PasswordFile = (Join-Path $PSScriptRoot "..\.secrets\nasmanager-release-v1.1.password.txt"),
    [string]$KeyAlias = "nasmanager"
)

$ErrorActionPreference = "Stop"
$keytool = Join-Path $JdkRoot "bin\keytool.exe"
$secretsDirectory = Split-Path -Parent $Keystore
$passwordEnvironmentName = "NASMANAGER_RELEASE_KEY_PASSWORD"

if (-not (Test-Path -LiteralPath $keytool)) { throw "keytool was not found: $keytool" }
if (Test-Path -LiteralPath $Keystore) { throw "Refusing to overwrite existing keystore: $Keystore" }
if (Test-Path -LiteralPath $PasswordFile) { throw "Refusing to overwrite existing password file: $PasswordFile" }

New-Item -ItemType Directory -Force -Path $secretsDirectory | Out-Null
$randomBytes = New-Object byte[] 32
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
$random.GetBytes($randomBytes)
$random.Dispose()
$password = ([BitConverter]::ToString($randomBytes)).Replace("-", "")
[IO.File]::WriteAllText($PasswordFile, $password, [Text.Encoding]::ASCII)
[Environment]::SetEnvironmentVariable($passwordEnvironmentName, $password, "Process")

try {
    & $keytool -genkeypair -alias $KeyAlias -keyalg RSA -keysize 4096 -sigalg SHA256withRSA `
        -validity 10000 -dname "CN=NAS Manager, OU=Mobile, O=NAS Manager, C=CY" `
        -storetype PKCS12 -keystore $Keystore `
        -storepass:env $passwordEnvironmentName -keypass:env $passwordEnvironmentName -noprompt
    if ($LASTEXITCODE -ne 0) { throw "keytool failed to create the release key" }

    $userSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    foreach ($secretPath in @($Keystore, $PasswordFile)) {
        & icacls.exe $secretPath /inheritance:r /grant:r "*$userSid`:(F)" "*S-1-5-18:(F)" "*S-1-5-32-544:(F)" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Failed to restrict access to $secretPath" }
    }

    Write-Output "Created release keystore: $Keystore"
    Write-Output "Saved password in gitignored file: $PasswordFile"
    & $keytool -list -v -keystore $Keystore -storepass:env $passwordEnvironmentName -alias $KeyAlias |
        Select-String -Pattern "Alias name:|SHA256:|Valid from:"
} catch {
    if (Test-Path -LiteralPath $Keystore) { Remove-Item -LiteralPath $Keystore -Force }
    if (Test-Path -LiteralPath $PasswordFile) { Remove-Item -LiteralPath $PasswordFile -Force }
    throw
} finally {
    [Environment]::SetEnvironmentVariable($passwordEnvironmentName, $null, "Process")
    $password = $null
    [Array]::Clear($randomBytes, 0, $randomBytes.Length)
}
