param(
    [string]$JdkRoot = "D:\Unity\Hub\Editor\2022.3.62f2\Editor\Data\PlaybackEngines\AndroidPlayer\OpenJDK"
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$testOutput = Join-Path $projectRoot "build\tests"
New-Item -ItemType Directory -Force $testOutput | Out-Null
& "$JdkRoot\bin\javac.exe" -source 11 -target 11 -encoding UTF-8 -d $testOutput `
    "$projectRoot\app\src\main\java\io\github\nasmanager\AppConfig.java" `
    "$projectRoot\app\src\main\java\io\github\nasmanager\WakeOnLan.java" `
    "$projectRoot\app\src\main\java\io\github\nasmanager\DashboardUiFormatter.java" `
    "$projectRoot\app\src\main\java\io\github\nasmanager\ServerReachabilityProbe.java" `
    "$projectRoot\app\src\main\java\io\github\nasmanager\DailyScheduleCalculator.java" `
    "$projectRoot\app\src\main\java\io\github\nasmanager\DashboardData.java" `
    "$projectRoot\app\src\main\java\io\github\nasmanager\TrueNasDataParser.java" `
    "$projectRoot\tests\io\github\nasmanager\AppConfigSecurityTest.java" `
    "$projectRoot\tests\io\github\nasmanager\WakeOnLanTest.java" `
    "$projectRoot\tests\io\github\nasmanager\DashboardUiFormatterTest.java" `
    "$projectRoot\tests\io\github\nasmanager\ServerReachabilityProbeTest.java" `
    "$projectRoot\tests\io\github\nasmanager\ScheduleManagerTest.java" `
    "$projectRoot\tests\io\github\nasmanager\TrueNasDataParserTest.java"
if ($LASTEXITCODE -ne 0) { throw "Test compilation failed" }
& "$JdkRoot\bin\java.exe" -ea -cp $testOutput io.github.nasmanager.AppConfigSecurityTest
if ($LASTEXITCODE -ne 0) { throw "Tests failed" }
& "$JdkRoot\bin\java.exe" -ea -cp $testOutput io.github.nasmanager.WakeOnLanTest
if ($LASTEXITCODE -ne 0) { throw "Tests failed" }
& "$JdkRoot\bin\java.exe" -ea -cp $testOutput io.github.nasmanager.DashboardUiFormatterTest
if ($LASTEXITCODE -ne 0) { throw "Tests failed" }
& "$JdkRoot\bin\java.exe" -ea -cp $testOutput io.github.nasmanager.ServerReachabilityProbeTest
if ($LASTEXITCODE -ne 0) { throw "Tests failed" }
& "$JdkRoot\bin\java.exe" -ea -cp $testOutput io.github.nasmanager.ScheduleManagerTest
if ($LASTEXITCODE -ne 0) { throw "Tests failed" }
& "$JdkRoot\bin\java.exe" -ea -cp $testOutput io.github.nasmanager.TrueNasDataParserTest
if ($LASTEXITCODE -ne 0) { throw "Tests failed" }
