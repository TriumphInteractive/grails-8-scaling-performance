$ErrorActionPreference = 'Continue'
$dir = '<home>\AppData\Local\Temp\opencode\ladder3'
$p = "$dir\progress2.log"
Set-Location '<home>\Documents\IdeaProjects\grails-8-scaling-performance'
while (-not ((Get-Content $p -Raw) -match 'batch done: H0,H1')) { Start-Sleep -Seconds 30 }
& 'C:\Program Files\PowerShell\7\pwsh.exe' -NoProfile -ExecutionPolicy Bypass -File "$dir\run.ps1" -Layers F4b -Hold 180
