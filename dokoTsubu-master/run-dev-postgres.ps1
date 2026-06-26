param(
  [string]$TomcatHome = "C:\tomcat\10",
  [string]$JavaHome = "C:\Program Files\Java\latest\jdk-25",
  [int]$Port = 8080,
  [switch]$NoDocker,
  [switch]$ResetDatabase,
  [switch]$NoWait
)

$ErrorActionPreference = "Stop"
$project = $PSScriptRoot
$compose = Join-Path $project "docker-compose.postgres.yml"

if (-not $NoDocker) {
  if ($ResetDatabase) {
    Write-Host "Resetting local PostgreSQL Docker volume..."
    docker compose -f $compose down -v
  }

  Write-Host "Starting local PostgreSQL..."
  docker compose -f $compose up -d

  for ($i = 0; $i -lt 30; $i++) {
    $ready = docker compose -f $compose exec -T postgres pg_isready -U shiftflow -d shiftflow_dev
    if ($LASTEXITCODE -eq 0) {
      break
    }
    Start-Sleep -Seconds 1
  }

  if ($LASTEXITCODE -ne 0) {
    throw "Local PostgreSQL did not become ready. Run 'docker compose -f docker-compose.postgres.yml logs postgres' for details."
  }
}

if (-not $env:JDBC_URL) { $env:JDBC_URL = "jdbc:postgresql://localhost:5433/shiftflow_dev" }
if (-not $env:DB_USER) { $env:DB_USER = "shiftflow" }
if (-not $env:DB_PASSWORD) { $env:DB_PASSWORD = "shiftflow_dev_password" }
if (-not $env:BASE_SEED) { $env:BASE_SEED = "true" }

Write-Host "Using local PostgreSQL: $($env:JDBC_URL)"
Write-Host "DB_USER configured: $([bool]$env:DB_USER)"
Write-Host "DB_PASSWORD configured: $([bool]$env:DB_PASSWORD)"

& (Join-Path $project "run-dev.ps1") -TomcatHome $TomcatHome -JavaHome $JavaHome -Port $Port -NoWait:$NoWait
