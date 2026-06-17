param(
  [string]$TomcatHome = "C:\tomcat\10",
  [string]$JavaHome = "C:\Program Files\Java\latest\jdk-25",
  [int]$Port = 8080
)

$ErrorActionPreference = "Stop"
$project = $PSScriptRoot
$base = Join-Path $project ".tomcat"
$webapp = Join-Path $base "webapps\shiftflow"

& (Join-Path $project "build.ps1") -TomcatHome $TomcatHome -JavaHome $JavaHome

New-Item -ItemType Directory -Force (Join-Path $base "conf"), (Join-Path $base "logs"), (Join-Path $base "temp"), (Join-Path $base "work"), (Join-Path $base "webapps") | Out-Null
Copy-Item (Join-Path $TomcatHome "conf\*") (Join-Path $base "conf") -Recurse -Force
Copy-Item (Join-Path $project "target\shiftflow\*") $webapp -Recurse -Force

if ($Port -ne 8080) {
  $serverXml = Join-Path $base "conf\server.xml"
  $replacement = 'port="' + $Port + '" protocol="HTTP/1.1"'
  (Get-Content $serverXml -Raw).Replace('port="8080" protocol="HTTP/1.1"', $replacement) | Set-Content $serverXml -Encoding UTF8
}

$env:CATALINA_HOME = $TomcatHome
$env:CATALINA_BASE = $base
$env:JRE_HOME = $JavaHome
& (Join-Path $TomcatHome "bin\startup.bat")
Write-Host "ShiftFlow: http://localhost:$Port/shiftflow/"
