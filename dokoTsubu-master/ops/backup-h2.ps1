param(
  [string]$DataDir,
  [string]$BackupDir,
  [string]$JavaHome = "C:\Program Files\Java\latest\jdk-25"
)

$ErrorActionPreference = "Stop"
$project = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ([string]::IsNullOrWhiteSpace($DataDir)) { $DataDir = Join-Path $project ".tomcat\data" }
if ([string]::IsNullOrWhiteSpace($BackupDir)) { $BackupDir = Join-Path $project "backups" }
$h2 = Join-Path $project "src\main\webapp\WEB-INF\lib\h2-2.4.240.jar"
$java = Join-Path $JavaHome "bin\java.exe"
$source = [IO.Path]::GetFullPath((Join-Path $DataDir "shiftapp"))
$destination = [IO.Path]::GetFullPath($BackupDir)
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$backup = Join-Path $destination "shiftapp-$stamp.sql"
$temporary = Join-Path $destination "shiftapp-$stamp.partial.sql"

if (-not (Test-Path -LiteralPath "$source.mv.db" -PathType Leaf)) {
  throw "Source database was not found: $source.mv.db"
}
New-Item -ItemType Directory -Force -Path $destination | Out-Null
if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }

$url = "jdbc:h2:file:$($source.Replace('\', '/'));AUTO_SERVER=TRUE"
try {
  & $java -cp $h2 org.h2.tools.Script -url $url -user sa -script $temporary
  if ($LASTEXITCODE -ne 0) { throw "H2 SCRIPT failed with exit code $LASTEXITCODE" }
  if (-not (Test-Path -LiteralPath $temporary -PathType Leaf) -or (Get-Item $temporary).Length -eq 0) {
    throw "H2 SCRIPT did not create a usable backup"
  }
  Move-Item -LiteralPath $temporary -Destination $backup
} finally {
  if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
}

Write-Host "Backup completed: $backup"
