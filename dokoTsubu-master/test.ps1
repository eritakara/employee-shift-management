param(
  [string]$TomcatHome = "C:\tomcat\10",
  [string]$JavaHome = "C:\Program Files\Java\latest\jdk-25"
)

$ErrorActionPreference = "Stop"
& (Join-Path $PSScriptRoot "build.ps1") -TomcatHome $TomcatHome -JavaHome $JavaHome

$libDir = Join-Path $PSScriptRoot "src\main\webapp\WEB-INF\lib"
$libs = (Get-ChildItem $libDir -Filter *.jar | ForEach-Object FullName) -join ";"
$testClasses = Join-Path $PSScriptRoot "build\test-classes"
New-Item -ItemType Directory -Force $testClasses | Out-Null
$tests = Get-ChildItem (Join-Path $PSScriptRoot "src\test\java") -Recurse -Filter *.java | ForEach-Object FullName
& (Join-Path $JavaHome "bin\javac.exe") --release 21 -encoding UTF-8 -cp "$(Join-Path $PSScriptRoot 'build\classes');$libs" -d $testClasses $tests
if ($LASTEXITCODE -ne 0) { throw "Test compilation failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" SmokeTest
if ($LASTEXITCODE -ne 0) { throw "Tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" config.DemoShiftCsvTest
if ($LASTEXITCODE -ne 0) { throw "Demo shift CSV tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" service.MailDeliveryTest
if ($LASTEXITCODE -ne 0) { throw "Mail tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" service.LeavePolicyTest
if ($LASTEXITCODE -ne 0) { throw "Leave policy tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" config.LeaveGrantBackfillTest
if ($LASTEXITCODE -ne 0) { throw "Leave grant backfill tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" service.ShiftSubmissionPolicyTest
if ($LASTEXITCODE -ne 0) { throw "Shift submission policy tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" service.AttendanceFinalizationTest
if ($LASTEXITCODE -ne 0) { throw "Attendance finalization tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" service.AttendanceClockGuardTest
if ($LASTEXITCODE -ne 0) { throw "Attendance clock guard tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" service.AuditSearchTest
if ($LASTEXITCODE -ne 0) { throw "Audit search tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" service.AuditActionLabelTest
if ($LASTEXITCODE -ne 0) { throw "Audit action label tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" service.ExportServiceTest
if ($LASTEXITCODE -ne 0) { throw "Export service tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" service.ExportPerformanceTest
if ($LASTEXITCODE -ne 0) { throw "Export performance tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" service.AttendanceCalculatorTest
if ($LASTEXITCODE -ne 0) { throw "Attendance calculator tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" service.SecurityAuthorizationTest
if ($LASTEXITCODE -ne 0) { throw "Security authorization tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" service.ShiftWorkflowTest
if ($LASTEXITCODE -ne 0) { throw "Shift workflow tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" service.ShiftPreferenceWorkflowTest
if ($LASTEXITCODE -ne 0) { throw "Shift preference workflow tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" service.ShiftAutoAssignmentPerformanceTest
if ($LASTEXITCODE -ne 0) { throw "Shift auto-assignment performance tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" service.NotificationRoutingTest
if ($LASTEXITCODE -ne 0) { throw "Notification routing tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" service.SecurityHardeningTest
if ($LASTEXITCODE -ne 0) { throw "Security hardening tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" service.SensitiveDataLeakTest
if ($LASTEXITCODE -ne 0) { throw "Sensitive data leak tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" service.DataRetentionServiceTest
if ($LASTEXITCODE -ne 0) { throw "Data retention tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" service.BackupRestoreTest
if ($LASTEXITCODE -ne 0) { throw "Backup and restore tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" service.CoreWorkflowE2ETest
if ($LASTEXITCODE -ne 0) { throw "Core workflow E2E tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" service.PerformanceTest
if ($LASTEXITCODE -ne 0) { throw "Performance tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" service.DashboardChartTest
if ($LASTEXITCODE -ne 0) { throw "Dashboard chart tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" service.AttendanceAdjustmentAuditTest
if ($LASTEXITCODE -ne 0) { throw "Attendance adjustment audit tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" config.ProductionInitializationTest
if ($LASTEXITCODE -ne 0) { throw "Production initialization tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" config.HrPasswordResetTest
if ($LASTEXITCODE -ne 0) { throw "HR password reset tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" service.ApprovedRequirementsTest
if ($LASTEXITCODE -ne 0) { throw "Approved requirements tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" service.ConcurrentLoadTest
if ($LASTEXITCODE -ne 0) { throw "Concurrent load tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" service.InvitationWorkflowTest
if ($LASTEXITCODE -ne 0) { throw "Invitation workflow tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" service.AccessibilityTest
if ($LASTEXITCODE -ne 0) { throw "Accessibility tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" service.UiStateCoverageTest
if ($LASTEXITCODE -ne 0) { throw "UI state coverage tests failed" }
& (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $PSScriptRoot 'build\classes');$testClasses;$libs" service.ShiftAutoFillTest
if ($LASTEXITCODE -ne 0) { throw "Shift auto fill tests failed" }
