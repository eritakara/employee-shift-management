param(
  [string]$ProjectPath,
  [string]$TomcatBase = "C:\ShiftFlow\production\tomcat",
  [string]$BackupDir = "D:\ShiftFlowBackups\daily",
  [string]$AlertDir = "D:\ShiftFlowAlerts",
  [string]$Url = "https://shiftflow.example/shiftflow/",
  [string]$TaskPrefix = "ShiftFlow",
  [switch]$Apply
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($ProjectPath)) { $ProjectPath = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path }
$project = [IO.Path]::GetFullPath($ProjectPath)
$powershell = (Get-Command powershell.exe).Source
$backupScript = Join-Path $project "ops\backup-h2.ps1"
$monitorScript = Join-Path $project "ops\monitor-health.ps1"
$archiveScript = Join-Path $project "ops\archive-logs.ps1"
foreach ($script in @($backupScript, $monitorScript, $archiveScript)) {
  if (-not (Test-Path -LiteralPath $script -PathType Leaf)) { throw "Required script was not found: $script" }
}

$definitions = @(
  [pscustomobject]@{ Name="$TaskPrefix-DailyBackup"; Schedule="Daily 01:00"; Script=$backupScript; Arguments="-DataDir `"$(Join-Path $TomcatBase 'data')`" -BackupDir `"$BackupDir`"" },
  [pscustomobject]@{ Name="$TaskPrefix-HealthMonitor"; Schedule="Every 5 minutes"; Script=$monitorScript; Arguments="-Url `"$Url`" -TomcatBase `"$TomcatBase`" -AlertDir `"$AlertDir`"" },
  [pscustomobject]@{ Name="$TaskPrefix-LogMaintenance"; Schedule="Daily 02:00"; Script=$archiveScript; Arguments="-TomcatBase `"$TomcatBase`" -ActiveRetainDays 30 -ArchiveRetainDays 365 -Apply" }
)

if (-not $Apply) {
  $definitions | ConvertTo-Json
  Write-Host "Preview only. Re-run with -Apply from an approved elevated session to register tasks."
  exit 0
}

$backupTrigger = New-ScheduledTaskTrigger -Daily -At "01:00"
$monitorTrigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 5)
$logTrigger = New-ScheduledTaskTrigger -Daily -At "02:00"
$triggers = @($backupTrigger, $monitorTrigger, $logTrigger)
for ($i = 0; $i -lt $definitions.Count; $i++) {
  $definition = $definitions[$i]
  $arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$($definition.Script)`" $($definition.Arguments)"
  $action = New-ScheduledTaskAction -Execute $powershell -Argument $arguments
  Register-ScheduledTask -TaskName $definition.Name -Action $action -Trigger $triggers[$i] `
    -Description $definition.Schedule -RunLevel Highest -Force | Out-Null
}
Write-Host "ShiftFlow operations tasks registered"

