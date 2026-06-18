param(
  [string]$TomcatHome = "C:\tomcat\10",
  [string]$JavaHome = "C:\Program Files\Java\latest\jdk-25"
)

$ErrorActionPreference = "Stop"
& (Join-Path $PSScriptRoot "build.ps1") -TomcatHome $TomcatHome -JavaHome $JavaHome

$h2 = Join-Path $PSScriptRoot "src\main\webapp\WEB-INF\lib\h2-2.4.240.jar"
$testClasses = Join-Path $PSScriptRoot "build\test-classes"
New-Item -ItemType Directory -Force $testClasses | Out-Null
$tests = Get-ChildItem (Join-Path $PSScriptRoot "src\test\java") -Recurse -Filter *.java | ForEach-Object FullName
& (Join-Path $JavaHome "bin\javac.exe") --release 21 -encoding UTF-8 -cp "$(Join-Path $PSScriptRoot 'build\classes');$h2" -d $testClasses $tests
if ($LASTEXITCODE -ne 0) { throw "Test compilation failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$h2" SmokeTest
if ($LASTEXITCODE -ne 0) { throw "Tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$h2" service.MailDeliveryTest
if ($LASTEXITCODE -ne 0) { throw "Mail tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$h2" service.LeavePolicyTest
if ($LASTEXITCODE -ne 0) { throw "Leave policy tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$h2" service.ShiftSubmissionPolicyTest
if ($LASTEXITCODE -ne 0) { throw "Shift submission policy tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$h2" service.AttendanceFinalizationTest
if ($LASTEXITCODE -ne 0) { throw "Attendance finalization tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$h2" service.AuditSearchTest
if ($LASTEXITCODE -ne 0) { throw "Audit search tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$h2" service.ExportServiceTest
if ($LASTEXITCODE -ne 0) { throw "Export service tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$h2" service.AttendanceCalculatorTest
if ($LASTEXITCODE -ne 0) { throw "Attendance calculator tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$h2" service.SecurityAuthorizationTest
if ($LASTEXITCODE -ne 0) { throw "Security authorization tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$h2" service.ShiftWorkflowTest
if ($LASTEXITCODE -ne 0) { throw "Shift workflow tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$h2" service.NotificationRoutingTest
if ($LASTEXITCODE -ne 0) { throw "Notification routing tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$h2" service.SecurityHardeningTest
if ($LASTEXITCODE -ne 0) { throw "Security hardening tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$h2" service.SensitiveDataLeakTest
if ($LASTEXITCODE -ne 0) { throw "Sensitive data leak tests failed" }
