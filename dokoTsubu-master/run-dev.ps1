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
New-Item -ItemType Directory -Force $webapp | Out-Null
Copy-Item (Join-Path $project "target\shiftflow\*") $webapp -Recurse -Force

if ($Port -ne 8080) {
  $serverXml = Join-Path $base "conf\server.xml"
  $replacement = 'port="' + $Port + '" protocol="HTTP/1.1"'
  $shutdownPort = 8005 + ($Port - 8080)
  (Get-Content $serverXml -Raw).Replace('port="8080" protocol="HTTP/1.1"', $replacement).Replace('<Server port="8005"', '<Server port="' + $shutdownPort + '"') | Set-Content $serverXml -Encoding UTF8
}

$env:CATALINA_HOME = $TomcatHome
$env:CATALINA_BASE = $base
$env:JRE_HOME = $JavaHome
$env:CATALINA_OPTS = (($env:CATALINA_OPTS + " -Dshiftapp.seedDemoShifts=true").Trim())
& (Join-Path $TomcatHome "bin\startup.bat")
Write-Host "ShiftFlow: http://localhost:$Port/shiftflow/"
