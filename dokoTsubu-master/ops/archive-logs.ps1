param(
  [string]$TomcatBase,
  [string]$ArchiveDir,
  [int]$ActiveRetainDays = 30,
  [int]$ArchiveRetainDays = 365,
  [switch]$Apply
)

$ErrorActionPreference = "Stop"
$project = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ([string]::IsNullOrWhiteSpace($TomcatBase)) { $TomcatBase = Join-Path $project ".tomcat" }
$base = [IO.Path]::GetFullPath($TomcatBase).TrimEnd('\')
$logs = [IO.Path]::GetFullPath((Join-Path $base "logs")).TrimEnd('\')
if ([string]::IsNullOrWhiteSpace($ArchiveDir)) { $ArchiveDir = Join-Path $base "log-archive" }
$archive = [IO.Path]::GetFullPath($ArchiveDir).TrimEnd('\')
if ($ActiveRetainDays -lt 1 -or $ArchiveRetainDays -lt $ActiveRetainDays) {
  throw "Retention days are invalid"
}
if (-not (Test-Path -LiteralPath $logs -PathType Container)) { throw "Tomcat log directory was not found: $logs" }

$activeCutoff = (Get-Date).AddDays(-$ActiveRetainDays)
$archiveCutoff = (Get-Date).AddDays(-$ArchiveRetainDays)
$candidates = @(Get-ChildItem -LiteralPath $logs -File | Where-Object { $_.LastWriteTime -lt $activeCutoff })
$expiredArchives = if (Test-Path -LiteralPath $archive) {
  @(Get-ChildItem -LiteralPath $archive -File -Filter *.zip | Where-Object { $_.LastWriteTime -lt $archiveCutoff })
} else { @() }

if (-not $Apply) {
  [pscustomobject]@{
    mode = "Preview"
    logsToArchive = $candidates.Count
    archivesToDelete = $expiredArchives.Count
    activeRetainDays = $ActiveRetainDays
    archiveRetainDays = $ArchiveRetainDays
  } | ConvertTo-Json
  exit 0
}

New-Item -ItemType Directory -Force -Path $archive | Out-Null
foreach ($file in $candidates) {
  $resolved = [IO.Path]::GetFullPath($file.FullName)
  if (-not $resolved.StartsWith($logs + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to archive a file outside the Tomcat log directory: $resolved"
  }
  $zip = Join-Path $archive ($file.Name + ".zip")
  if (Test-Path -LiteralPath $zip) { throw "Archive already exists: $zip" }
  Compress-Archive -LiteralPath $resolved -DestinationPath $zip -CompressionLevel Optimal
  if (-not (Test-Path -LiteralPath $zip) -or (Get-Item -LiteralPath $zip).Length -eq 0) {
    throw "Log archive verification failed: $zip"
  }
  Remove-Item -LiteralPath $resolved -Force
}
foreach ($file in $expiredArchives) {
  $resolved = [IO.Path]::GetFullPath($file.FullName)
  if (-not $resolved.StartsWith($archive + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to delete a file outside the log archive: $resolved"
  }
  Remove-Item -LiteralPath $resolved -Force
}
Write-Host "Log maintenance completed: archived=$($candidates.Count), deleted=$($expiredArchives.Count)"

