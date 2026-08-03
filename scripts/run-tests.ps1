param(
    [string]$JdkRoot = "D:\Unity\Hub\Editor\2022.3.62f2\Editor\Data\PlaybackEngines\AndroidPlayer\OpenJDK"
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$testOutput = Join-Path $projectRoot "build\tests"
New-Item -ItemType Directory -Force $testOutput | Out-Null
& "$JdkRoot\bin\javac.exe" -source 11 -target 11 -encoding UTF-8 -d $testOutput `
    "$projectRoot\app\src\main\java\io\github\nasmanager\WakeOnLan.java" `
    "$projectRoot\tests\io\github\nasmanager\WakeOnLanTest.java"
if ($LASTEXITCODE -ne 0) { throw "Test compilation failed" }
& "$JdkRoot\bin\java.exe" -ea -cp $testOutput io.github.nasmanager.WakeOnLanTest
if ($LASTEXITCODE -ne 0) { throw "Tests failed" }
