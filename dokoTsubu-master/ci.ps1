param(
  [string]$TomcatHome = "C:\tomcat\10",
  [string]$JavaHome = "C:\Program Files\Java\latest\jdk-25"
)

$ErrorActionPreference = "Stop"
$project = $PSScriptRoot
$servletApi = Join-Path $TomcatHome "lib\servlet-api.jar"
$h2 = Join-Path $project "src\main\webapp\WEB-INF\lib\h2-2.4.240.jar"
$lintClasses = Join-Path $project "build\lint-classes"

if (-not (Test-Path $servletApi)) { throw "Servlet API was not found: $servletApi" }
New-Item -ItemType Directory -Force $lintClasses | Out-Null
$sources = Get-ChildItem (Join-Path $project "src\main\java") -Recurse -Filter *.java | ForEach-Object FullName
& (Join-Path $JavaHome "bin\javac.exe") --release 21 -encoding UTF-8 -Xlint:all,-serial -Werror -cp "$servletApi;$h2" -d $lintClasses $sources
if ($LASTEXITCODE -ne 0) { throw "Static analysis failed" }

& (Join-Path $project "test.ps1") -TomcatHome $TomcatHome -JavaHome $JavaHome
if ($LASTEXITCODE -ne 0) { throw "Build or tests failed" }
Write-Host "CI checks passed"
