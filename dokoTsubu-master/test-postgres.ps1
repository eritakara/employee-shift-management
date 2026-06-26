param(
  [string]$TomcatHome = "C:\tomcat\10",
  [string]$JavaHome = "C:\Program Files\Java\latest\jdk-25",
  [string[]]$Tests = @("service.ShiftPreferenceWorkflowTest"),
  [switch]$ResetDatabase
)

$ErrorActionPreference = "Stop"
$project = $PSScriptRoot
$compose = Join-Path $project "docker-compose.postgres.yml"

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

$env:JDBC_URL = "jdbc:postgresql://localhost:5433/shiftflow_dev"
$env:DB_USER = "shiftflow"
$env:DB_PASSWORD = "shiftflow_dev_password"
$env:BASE_SEED = "true"

& (Join-Path $project "build.ps1") -TomcatHome $TomcatHome -JavaHome $JavaHome

$h2 = Join-Path $project "src\main\webapp\WEB-INF\lib\h2-2.4.240.jar"
$pg = Join-Path $project "src\main\webapp\WEB-INF\lib\postgresql-42.7.3.jar"
$testClasses = Join-Path $project "build\test-classes"
New-Item -ItemType Directory -Force $testClasses | Out-Null
$testSources = Get-ChildItem (Join-Path $project "src\test\java") -Recurse -Filter *.java | ForEach-Object FullName

& (Join-Path $JavaHome "bin\javac.exe") --release 21 -encoding UTF-8 -cp "$(Join-Path $project 'build\classes');$h2;$pg" -d $testClasses $testSources
if ($LASTEXITCODE -ne 0) { throw "PostgreSQL test compilation failed" }

foreach ($test in $Tests) {
  Write-Host "Running PostgreSQL compatibility test: $test"
  & (Join-Path $JavaHome "bin\java.exe") -cp "$(Join-Path $project 'build\classes');$testClasses;$h2;$pg" $test
  if ($LASTEXITCODE -ne 0) { throw "PostgreSQL compatibility test failed: $test" }
}
