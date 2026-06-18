param(
  [Parameter(Mandatory = $true)]
  [string]$BackupFile,
  [Parameter(Mandatory = $true)]
  [string]$RestoreDataDir,
  [string]$ProductionDataDir,
  [string]$JavaHome = "C:\Program Files\Java\latest\jdk-25"
)

$ErrorActionPreference = "Stop"
$project = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ([string]::IsNullOrWhiteSpace($ProductionDataDir)) {
  $ProductionDataDir = Join-Path $project ".tomcat\data"
}
$h2 = Join-Path $project "src\main\webapp\WEB-INF\lib\h2-2.4.240.jar"
$java = Join-Path $JavaHome "bin\java.exe"
$script = (Resolve-Path -LiteralPath $BackupFile).Path
$targetDir = [IO.Path]::GetFullPath($RestoreDataDir).TrimEnd('\')
$productionDir = [IO.Path]::GetFullPath($ProductionDataDir).TrimEnd('\')
$target = Join-Path $targetDir "shiftapp"
$production = Join-Path $productionDir "shiftapp"

if ((Get-Item -LiteralPath $script).Length -eq 0) { throw "Backup file is empty: $script" }
if ($target.Equals($production, [StringComparison]::OrdinalIgnoreCase)) {
  throw "Restore target must be a separate database; the production database cannot be overwritten"
}
if (Test-Path -LiteralPath $targetDir) {
  if (Get-ChildItem -LiteralPath $targetDir -Force | Select-Object -First 1) {
    throw "Restore directory must be empty: $targetDir"
  }
} else {
  New-Item -ItemType Directory -Path $targetDir | Out-Null
}
foreach ($suffix in @(".mv.db", ".h2.db", ".lock.db", ".trace.db")) {
  if (Test-Path -LiteralPath "$target$suffix") { throw "Restore database already exists: $target$suffix" }
}

$url = "jdbc:h2:file:$($target.Replace('\', '/'))"
& $java -cp $h2 org.h2.tools.RunScript -url $url -user sa -script $script
if ($LASTEXITCODE -ne 0) { throw "H2 RUNSCRIPT failed with exit code $LASTEXITCODE" }

$validationSql = "SELECT COUNT(*) AS TABLE_COUNT FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'; SELECT COUNT(*) AS USER_COUNT FROM USERS;"
& $java -cp $h2 org.h2.tools.Shell -url $url -user sa -sql $validationSql
if ($LASTEXITCODE -ne 0) { throw "Restored database validation failed with exit code $LASTEXITCODE" }

Write-Host "Restore validation completed in separate database: $target.mv.db"
