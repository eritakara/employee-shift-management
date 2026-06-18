param(
  [string]$Url = "http://localhost:8080/shiftflow/",
  [string]$TomcatBase
)

$ErrorActionPreference = "Stop"
$project = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ([string]::IsNullOrWhiteSpace($TomcatBase)) { $TomcatBase = Join-Path $project ".tomcat" }
$base = [IO.Path]::GetFullPath($TomcatBase)
$database = Join-Path $base "data\shiftapp.mv.db"
$logs = Join-Path $base "logs"
$started = Get-Date

$response = Invoke-WebRequest -Uri $Url -Method Get -TimeoutSec 10 -UseBasicParsing
$appHealthy = $response.StatusCode -eq 200 -and $response.Content.Contains("ShiftFlow")
$databaseFile = Get-Item -LiteralPath $database -ErrorAction SilentlyContinue
$logFiles = if (Test-Path -LiteralPath $logs) { @(Get-ChildItem -LiteralPath $logs -File) } else { @() }
$latestLog = $logFiles | Sort-Object LastWriteTime -Descending | Select-Object -First 1

$result = [ordered]@{
  checkedAt = $started.ToString("o")
  url = $Url
  httpStatus = $response.StatusCode
  applicationHealthy = $appHealthy
  databasePresent = $null -ne $databaseFile
  databaseSizeBytes = if ($databaseFile) { $databaseFile.Length } else { 0 }
  logFileCount = $logFiles.Count
  latestLogUpdatedAt = if ($latestLog) { $latestLog.LastWriteTime.ToString("o") } else { $null }
  latestLogSizeBytes = if ($latestLog) { $latestLog.Length } else { 0 }
}

[pscustomobject]$result | ConvertTo-Json
if (-not $appHealthy) { throw "ShiftFlow health check failed: unexpected HTTP response" }
if (-not $databaseFile) { throw "ShiftFlow health check failed: database file was not found" }

