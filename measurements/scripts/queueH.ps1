$ErrorActionPreference = 'Continue'
$repo = '<home>\Documents\IdeaProjects\grails-8-scaling-performance'
$dir = '<home>\AppData\Local\Temp\opencode\ladder3'
$p = "$dir\progress2.log"
Set-Location $repo
while (-not ((Get-Content $p -Raw) -match 'batch done: F0,F1')) { Start-Sleep -Seconds 30 }
"$(Get-Date -Format o) queueH: F series done, building dynamic GORM war" | Add-Content $p
$env:JAVA_HOME = 'C:\Program Files\Amazon Corretto\jdk21.0.11_10'
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
& .\gradlew.bat :baseline:bootWar -PchirpCompileStatic=false -PgroovyIndy=false --rerun-tasks --console=plain *> "$dir\build-dyn-gorm.log"
"$(Get-Date -Format o) queueH: build exit=$LASTEXITCODE" | Add-Content $p
Copy-Item -LiteralPath "$repo\baseline\build\libs\chirp-baseline-0.1.war" -Destination "$dir\dyn-gorm.war" -Force
& 'C:\Program Files\PowerShell\7\pwsh.exe' -NoProfile -ExecutionPolicy Bypass -File "$dir\run.ps1" -Layers H0,H1,H2,H3,H4 -Hold 60
