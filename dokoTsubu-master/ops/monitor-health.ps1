param(
  [string]$Url = "http://localhost:8080/shiftflow/",
  [string]$TomcatBase,
  [string]$AlertDir
)

$ErrorActionPreference = "Stop"
$project = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ([string]::IsNullOrWhiteSpace($TomcatBase)) { $TomcatBase = Join-Path $project ".tomcat" }
if ([string]::IsNullOrWhiteSpace($AlertDir)) { $AlertDir = Join-Path $TomcatBase "alerts" }

try {
  & (Join-Path $PSScriptRoot "check-health.ps1") -Url $Url -TomcatBase $TomcatBase
  if ($LASTEXITCODE -ne 0) { throw "Health check returned exit code $LASTEXITCODE" }
} catch {
  New-Item -ItemType Directory -Force -Path $AlertDir | Out-Null
  $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
  $alert = [ordered]@{
    occurredAt = (Get-Date).ToString("o")
    severity = "P1"
    component = "ShiftFlow"
    url = $Url
    message = "Health check failed. Review the server-local Tomcat logs and operations runbook."
  }
  $path = Join-Path $AlertDir "shiftflow-health-$stamp.json"
  $alert | ConvertTo-Json | Set-Content -LiteralPath $path -Encoding UTF8
  Write-Error "ShiftFlow health check failed. Alert: $path"
  exit 1
}

