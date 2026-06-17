param(
  [string]$TomcatHome = "C:\tomcat\10",
  [string]$JavaHome = "C:\Program Files\Java\latest\jdk-25"
)

$ErrorActionPreference = "Stop"
$project = $PSScriptRoot
$servletApi = Join-Path $TomcatHome "lib\servlet-api.jar"
$h2 = Join-Path $project "src\main\webapp\WEB-INF\lib\h2-2.4.240.jar"
$classes = Join-Path $project "build\classes"
$stage = Join-Path $project "target\shiftflow"
$war = Join-Path $project "target\shiftflow.war"

if (-not (Test-Path $servletApi)) { throw "Tomcat 10 not found: $TomcatHome" }
New-Item -ItemType Directory -Force $classes, $stage | Out-Null

$sources = Get-ChildItem (Join-Path $project "src\main\java") -Recurse -Filter *.java | ForEach-Object FullName
& (Join-Path $JavaHome "bin\javac.exe") --release 21 -encoding UTF-8 -cp "$servletApi;$h2" -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw "Java compilation failed" }

Copy-Item (Join-Path $project "src\main\webapp\*") $stage -Recurse -Force
$webInfClasses = Join-Path $stage "WEB-INF\classes"
New-Item -ItemType Directory -Force $webInfClasses | Out-Null
Copy-Item (Join-Path $classes "*") $webInfClasses -Recurse -Force

if (Test-Path $war) { Remove-Item -LiteralPath $war -Force }
& (Join-Path $JavaHome "bin\jar.exe") --create --file $war -C $stage .
if ($LASTEXITCODE -ne 0) { throw "WAR packaging failed" }
Write-Host "Built: $war"
