$ErrorActionPreference = 'Continue'
$repo = '<home>\Documents\IdeaProjects\grails-8-scaling-performance'
$dir  = '<home>\AppData\Local\Temp\opencode\ladder3'
$wars = '<home>\AppData\Local\Temp\opencode\ladder2'
$j21  = 'C:\Program Files\Amazon Corretto\jdk21.0.11_10\bin'
$j25  = '<home>\.gradle\jdks\amazon_com_inc_-25-amd64-windows.2\bin'
$env:JAVA_HOME = 'C:\Program Files\Amazon Corretto\jdk21.0.11_10'
$env:Path = "$j21;" + $env:Path
Set-Location $repo

$progress = Join-Path $dir 'progress.log'
$csv = Join-Path $dir 'runs.csv'
if (-not (Test-Path $csv)) { 'layer,run,rps,ok,ko,flaky,started,ended' | Set-Content $csv }

function Log([string]$m) { "$(Get-Date -Format o) $m" | Add-Content $progress }

function Test-Flaky {
    foreach ($p in Get-CimInstance Win32_Process -Filter "Name='java.exe'") {
        if ($p.CommandLine -match 'wt\\flaky') { return $true }
    }
    return $false
}

function Wait-Quiet([string]$label) {
    $waited = $false
    while (Test-Flaky) { $waited = $true; Start-Sleep -Seconds 30 }
    Log "$label quiet (waited=$waited)"
}

function Invoke-Reseed([string]$db) {
    docker exec -e PGPASSWORD=chirp grails8-chirp-postgres psql -U chirp -d $db -v ON_ERROR_STOP=1 -q -f /tmp/seed.sql *> $null
    $seed = $LASTEXITCODE
    docker exec -e PGPASSWORD=chirp grails8-chirp-postgres psql -U chirp -d $db -q -c 'ANALYZE;' *> $null
    return "seed=$seed analyze=$LASTEXITCODE"
}

function Get-Number([string]$s) {
    $t = $s.Trim().Replace(',', '')
    if ($t -eq '-' -or $t -eq '') { return '0' }
    return $t
}

function Read-Gatling([string]$log) {
    $lines = Get-Content -LiteralPath $log
    $gi = -1
    for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i] -match 'Global Information') { $gi = $i } }
    $rps = ''; $ok = ''; $ko = ''
    if ($gi -ge 0) {
        for ($i = $gi; $i -lt $lines.Count; $i++) {
            $l = $lines[$i]
            if ($ok -eq '' -and $l -match '^> request count') { $parts = $l.Split('|'); $ok = Get-Number $parts[2]; $ko = Get-Number $parts[3] }
            if ($rps -eq '' -and $l -match '^> mean throughput \(rps\)') { $rps = Get-Number ($l.Split('|')[1]) }
        }
    }
    return @($rps, $ok, $ko)
}

function Invoke-Layer([string]$layer, [string]$jdk, [string]$war, [string]$app, [int]$port, [string]$db, [hashtable]$vars, [string[]]$extra, [int]$runs) {
    Wait-Quiet "$layer pre-layer"
    foreach ($k in $vars.Keys) { Set-Item -Path "Env:$k" -Value $vars[$k] }
    $javaArgs = @('-Dgrails.env=loadtest', '-Xms1g', '-Xmx1g', '-XX:+UseG1GC', '-XX:ActiveProcessorCount=12', '-jar', $war, '--spring.profiles.active=loadtest') + $extra
    $out = Join-Path $dir "$layer-app.out.log"
    $err = Join-Path $dir "$layer-app.err.log"
    $proc = Start-Process -FilePath (Join-Path $jdk 'java.exe') -ArgumentList $javaArgs -WorkingDirectory (Join-Path $repo $app) -NoNewWindow -PassThru -RedirectStandardOutput $out -RedirectStandardError $err
    foreach ($k in $vars.Keys) { Remove-Item -Path "Env:$k" -ErrorAction SilentlyContinue }
    $ready = $false
    for ($i = 0; $i -lt 120; $i++) {
        $code = curl.exe --silent --output NUL --write-out '%{http_code}' --connect-timeout 2 --max-time 5 "http://localhost:$port/api/ready"
        if ($code -eq '200') { $ready = $true; break }
        Start-Sleep -Seconds 2
    }
    Log "$layer started pid=$($proc.Id) ready=$ready war=$war jdk=$jdk extra=$($extra -join ' ') vars=$(($vars.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ' ')"
    if (-not $ready) { Log "$layer NOT READY, skipping"; Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue; return }
    $vm = (& (Join-Path $jdk 'jcmd.exe') $proc.Id VM.version) -join ' | '
    Log "$layer vm: $vm"
    Start-Sleep -Seconds 3
    $conns = (docker exec -e PGPASSWORD=chirp grails8-chirp-postgres psql -U chirp -d postgres -t -A -c "SELECT count(*) FROM pg_stat_activity WHERE datname = '$db';") -join ''
    Log "$layer db connections=$conns"
    if ($extra -contains '--spring.threads.virtual.enabled=true') {
        $dump = (& (Join-Path $jdk 'jcmd.exe') $proc.Id Thread.print) -join "`n"
        $exec = ([regex]::Matches($dump, 'http-nio-\d+-exec-')).Count
        Log "$layer platform http-nio exec threads=$exec"
    }
    for ($r = 1; $r -le $runs; $r++) {
        Wait-Quiet "$layer run $r"
        $started = Get-Date -Format o
        $seed = Invoke-Reseed $db
        $warmLog = Join-Path $dir "$layer-run$r-warm.log"
        & .\gradlew.bat warm "-PbaseUrl=http://localhost:$port" -Paccounts=2000 -Pconcurrency=20 --console=plain *> $warmLog
        $warmExit = $LASTEXITCODE
        $loadLog = Join-Path $dir "$layer-run$r-load.log"
        & .\gradlew.bat loadTest "-PbaseUrl=http://localhost:$port" -Pusers=40 -PrampSeconds=15 -PholdSeconds=60 --console=plain *> $loadLog
        $loadExit = $LASTEXITCODE
        $ended = Get-Date -Format o
        $flaky = Test-Flaky
        $g = Read-Gatling $loadLog
        "$layer,$r,$($g[0]),$($g[1]),$($g[2]),$flaky,$started,$ended" | Add-Content $csv
        Log "$layer run $r rps=$($g[0]) ok=$($g[1]) ko=$($g[2]) $seed warm=$warmExit load=$loadExit flakyAtEnd=$flaky"
    }
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 3
    $timeouts = 0
    foreach ($f in @($out, $err)) { if (Test-Path $f) { $timeouts += ([regex]::Matches((Get-Content -LiteralPath $f -Raw), 'Connection is not available')).Count } }
    Log "$layer stopped; pool timeout lines=$timeouts"
}

$pool = @{ CHIRP_POOL_SIZE = '48'; CHIRP_MIN_IDLE = '48'; CHIRP_CONNECTION_TIMEOUT = '2000' }
function With([hashtable]$more) { $h = $pool.Clone(); foreach ($k in $more.Keys) { $h[$k] = $more[$k] }; return $h }

Log 'ladder start'
docker cp (Join-Path $repo 'db\seed.sql') grails8-chirp-postgres:/tmp/seed.sql *> $null
Log "seed copied exit=$LASTEXITCODE"

Invoke-Layer 'L0'  $j21 "$wars\naive.war"      'baseline'  8080 'chirp_baseline'  (With @{}) @() 3
Invoke-Layer 'L1'  $j25 "$wars\naive.war"      'baseline'  8080 'chirp_baseline'  (With @{}) @() 3
Invoke-Layer 'L1i' $j25 "$wars\naive-indy.war" 'baseline'  8080 'chirp_baseline'  (With @{}) @() 3
Invoke-Layer 'L2'  $j25 "$wars\static.war"     'baseline'  8080 'chirp_baseline'  (With @{}) @() 3
Invoke-Layer 'L3'  $j25 "$wars\static.war"     'baseline'  8080 'chirp_baseline'  (With @{}) @('--spring.threads.virtual.enabled=true') 3
Invoke-Layer 'L4'  $j25 "$wars\optimized.war"  'optimized' 8081 'chirp_optimized' (With @{ CHIRP_CACHE_ENABLED = 'false'; CHIRP_VIRTUAL_THREADS = 'true' }) @() 3
Invoke-Layer 'L5'  $j25 "$wars\optimized.war"  'optimized' 8081 'chirp_optimized' (With @{ CHIRP_CACHE_ENABLED = 'true'; CHIRP_VIRTUAL_THREADS = 'true' }) @() 3
Invoke-Layer 'L1r' $j25 "$wars\naive.war"      'baseline'  8080 'chirp_baseline'  (With @{}) @() 3
Invoke-Layer 'L2r' $j25 "$wars\static.war"     'baseline'  8080 'chirp_baseline'  (With @{}) @() 3
Log 'ladder done'
