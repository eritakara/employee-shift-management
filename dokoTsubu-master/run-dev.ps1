param(
  [string]$TomcatHome = "C:\tomcat\10",
  [string]$JavaHome = "C:\Program Files\Java\latest\jdk-25",
  [int]$Port = 8080,
  [switch]$NoWait
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
$env:CATALINA_OPTS = ((($env:CATALINA_OPTS -replace "\s*-Dshiftapp\.seedDemoShifts=true", "") + " -Dshiftapp.seedDemoShifts=true").Trim())

$url = "http://localhost:$Port/shiftflow/"

if ($NoWait) {
  $process = Start-Process -FilePath (Join-Path $TomcatHome "bin\catalina.bat") -ArgumentList "run" -WindowStyle Hidden -PassThru
  Set-Content (Join-Path $base "tomcat.pid") $process.Id -Encoding ASCII
  for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 1
    if ($process.HasExited) { throw "Tomcat exited before ShiftFlow became ready. Check logs in $base\logs." }
    try {
      $response = Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 3
      if ($response.StatusCode -eq 200) {
        Write-Host "ShiftFlow: $url"
        Write-Host "Tomcat PID: $($process.Id)"
        return
      }
    } catch {
      Start-Sleep -Milliseconds 250
    }
  }
  throw "ShiftFlow did not become ready at $url. Check logs in $base\logs."
}

Write-Host "ShiftFlow: $url"
Write-Host "Tomcat is running in this terminal. Press Ctrl+C to stop it."
& (Join-Path $TomcatHome "bin\catalina.bat") run
