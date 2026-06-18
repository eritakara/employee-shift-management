param(
  [Parameter(Mandatory = $true)]
  [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?$')]
  [string]$Version,
  [string]$TomcatHome = "C:\tomcat\10",
  [string]$JavaHome = "C:\Program Files\Java\latest\jdk-25",
  [string]$OutputDir,
  [switch]$AllowDirty
)

$ErrorActionPreference = "Stop"
$project = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ([string]::IsNullOrWhiteSpace($OutputDir)) { $OutputDir = Join-Path $project "target\releases" }
$changes = @(& git -C $project status --porcelain)
if ($LASTEXITCODE -ne 0) { throw "Could not inspect the Git worktree" }
if ($changes.Count -gt 0 -and -not $AllowDirty) {
  throw "Release creation requires a clean Git worktree"
}

& (Join-Path $project "ci.ps1") -TomcatHome $TomcatHome -JavaHome $JavaHome
if ($LASTEXITCODE -ne 0) { throw "Release checks failed" }

$sourceWar = Join-Path $project "target\shiftflow.war"
$releaseDir = [IO.Path]::GetFullPath((Join-Path $OutputDir "shiftflow-$Version"))
if (Test-Path -LiteralPath $releaseDir) { throw "Release already exists: $releaseDir" }
New-Item -ItemType Directory -Path $releaseDir | Out-Null
$releaseWar = Join-Path $releaseDir "shiftflow.war"
Copy-Item -LiteralPath $sourceWar -Destination $releaseWar
$hash = (Get-FileHash -LiteralPath $releaseWar -Algorithm SHA256).Hash.ToLowerInvariant()
$commit = (& git -C $project rev-parse HEAD).Trim()
$manifest = [ordered]@{
  application = "ShiftFlow"
  version = $Version
  gitCommit = $commit
  dirtyWorktree = $changes.Count -gt 0
  createdAtUtc = [DateTime]::UtcNow.ToString("o")
  artifact = "shiftflow.war"
  sha256 = $hash
}
$manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $releaseDir "release-manifest.json") -Encoding UTF8
"$hash  shiftflow.war" | Set-Content -LiteralPath (Join-Path $releaseDir "shiftflow.war.sha256") -Encoding ASCII
Write-Host "Release created: $releaseDir"

