param(
  [string]$TomcatHome = "C:\tomcat\10",
  [string]$JavaHome = "C:\Program Files\Java\latest\jdk-25"
)

$ErrorActionPreference = "Stop"
$project = $PSScriptRoot
$servletApi = Join-Path $TomcatHome "lib\servlet-api.jar"
$h2 = Join-Path $project "src\main\webapp\WEB-INF\lib\h2-2.4.240.jar"
$classes = Join-Path $project "build\classes"
$stage = Join-Path $project "target\ROOT"
$war = Join-Path $project "target\ROOT.war"

$libDir = Join-Path $project "src\main\webapp\WEB-INF\lib"
New-Item -ItemType Directory -Force $libDir | Out-Null

$pgJar = Join-Path $libDir "postgresql-42.7.3.jar"
if (-not (Test-Path $pgJar)) {
  Write-Warning "Downloading PostgreSQL JDBC Driver..."
  Invoke-WebRequest -Uri "https://jdbc.postgresql.org/download/postgresql-42.7.3.jar" -OutFile $pgJar
}

$hikariJar = Join-Path $libDir "HikariCP-5.1.0.jar"
if (-not (Test-Path $hikariJar)) {
  Write-Warning "Downloading HikariCP..."
  Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/com/zaxxer/HikariCP/5.1.0/HikariCP-5.1.0.jar" -OutFile $hikariJar
}

$slf4jJar = Join-Path $libDir "slf4j-api-2.0.12.jar"
if (-not (Test-Path $slf4jJar)) {
  Write-Warning "Downloading SLF4J API..."
  Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.12/slf4j-api-2.0.12.jar" -OutFile $slf4jJar
}

if (-not (Test-Path $servletApi)) { throw "Tomcat 10 not found: $TomcatHome" }
New-Item -ItemType Directory -Force $classes, $stage | Out-Null

$libs = (Get-ChildItem $libDir -Filter *.jar | ForEach-Object FullName) -join ";"
$sources = Get-ChildItem (Join-Path $project "src\main\java") -Recurse -Filter *.java | ForEach-Object FullName
& (Join-Path $JavaHome "bin\javac.exe") --release 21 -encoding UTF-8 -cp "$servletApi;$libs" -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw "Java compilation failed" }

$resources = Join-Path $project "src\main\resources"
if (Test-Path $resources) { Copy-Item (Join-Path $resources "*") $classes -Recurse -Force }

Copy-Item (Join-Path $project "src\main\webapp\*") $stage -Recurse -Force
$webInfClasses = Join-Path $stage "WEB-INF\classes"
New-Item -ItemType Directory -Force $webInfClasses | Out-Null
Copy-Item (Join-Path $classes "*") $webInfClasses -Recurse -Force

if (Test-Path $war) { Remove-Item -LiteralPath $war -Force }
& (Join-Path $JavaHome "bin\jar.exe") --create --file $war -C $stage .
if ($LASTEXITCODE -ne 0) { throw "WAR packaging failed" }
Write-Host "Built: $war"
