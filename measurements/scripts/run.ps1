param([string[]]$Layers, [int]$Runs = 3, [int]$Hold = 60)
$ErrorActionPreference = 'Continue'
$Layers = @($Layers | ForEach-Object { $_ -split ',' } | Where-Object { $_ })
$repo = '<home>\Documents\IdeaProjects\grails-8-scaling-performance'
$dir  = '<home>\AppData\Local\Temp\opencode\ladder3'
$w2   = '<home>\AppData\Local\Temp\opencode\ladder2'
$j21  = 'C:\Program Files\Amazon Corretto\jdk21.0.11_10\bin'
$j25  = '<home>\.gradle\jdks\amazon_com_inc_-25-amd64-windows.2\bin'
$env:JAVA_HOME = 'C:\Program Files\Amazon Corretto\jdk21.0.11_10'
$env:Path = "$j21;" + $env:Path
Set-Location $repo

$progress = Join-Path $dir 'progress2.log'
$csv = Join-Path $dir 'runs2.csv'
if (-not (Test-Path $csv)) { 'layer,run,rps,ok,ko,flaky,hold,started,ended' | Set-Content $csv }

function Log([string]$m) { "$(Get-Date -Format o) $m" | Add-Content $progress }

function Test-Flaky {
    foreach ($p in Get-CimInstance Win32_Process -Filter "Name='java.exe'") {
        if ($p.CommandLine -match 'AppData\\Local\\Temp\\wt\\') { return $true }
    }
    return $false
}

function Test-OtherApp {
    foreach ($p in Get-CimInstance Win32_Process -Filter "Name='java.exe'") {
        if ($p.CommandLine -match 'chirp-|ladder\d?\\') { return $true }
    }
    return $false
}

function Wait-Quiet([string]$label, [switch]$NoApps) {
    $waited = $false
    while ((Test-Flaky) -or ($NoApps -and (Test-OtherApp))) { $waited = $true; Start-Sleep -Seconds 15 }
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

$sampler = {
    param($appPid, $file, $seconds)
    for ($i = 0; $i -lt [int]($seconds / 5); $i++) {
        $t = Get-Date
        $app = (Get-Process -Id $appPid -ErrorAction SilentlyContinue).TotalProcessorTime.TotalSeconds
        $gat = 0.0
        foreach ($p in Get-CimInstance Win32_Process -Filter "Name='java.exe'") {
            if ($p.CommandLine -match 'io\.gatling\.app|io\.gatling\.plugin') {
                $gp = Get-Process -Id $p.ProcessId -ErrorAction SilentlyContinue
                if ($gp) { $gat += $gp.TotalProcessorTime.TotalSeconds }
            }
        }
        $pg = (docker stats --no-stream --format '{{.CPUPerc}}' grails8-chirp-postgres) -join ''
        "$($t.ToString('o')),$app,$gat,$($pg.Trim().TrimEnd('%'))" | Add-Content $file
        Start-Sleep -Seconds 4
    }
}

function Read-Samples([string]$file) {
    if (-not (Test-Path $file)) { return 'cpu=n/a' }
    $rows = @(Get-Content $file | ForEach-Object { $c = $_.Split(','); [pscustomobject]@{ t = [datetime]$c[0]; app = [double]$c[1]; gat = [double]$c[2]; pg = [double]$c[3] } })
    $appCores = @(); $gatCores = @(); $pg = @()
    for ($i = 1; $i -lt $rows.Count; $i++) {
        $dt = ($rows[$i].t - $rows[$i - 1].t).TotalSeconds
        if ($dt -le 0) { continue }
        $a = ($rows[$i].app - $rows[$i - 1].app) / $dt
        $g = ($rows[$i].gat - $rows[$i - 1].gat) / $dt
        if ($a -gt 0.05) { $appCores += $a; $gatCores += [Math]::Max(0, $g); $pg += $rows[$i].pg / 100 }
    }
    function Med($xs) { if ($xs.Count -eq 0) { return 0 } $s = $xs | Sort-Object; return [Math]::Round($s[[int][Math]::Floor($s.Count / 2)], 2) }
    return "appCores=$(Med $appCores) gatlingCores=$(Med $gatCores) postgresCores=$(Med $pg) samples=$($appCores.Count)"
}

$pool = @{ CHIRP_POOL_SIZE = '48'; CHIRP_MIN_IDLE = '48'; CHIRP_CONNECTION_TIMEOUT = '2000' }
function With([hashtable]$more) { $h = $pool.Clone(); foreach ($k in $more.Keys) { $h[$k] = $more[$k] }; return $h }

$batch = '--hibernate.default_batch_fetch_size=50'
$cache0 = @{ CHIRP_CACHE_ENABLED = 'false'; CHIRP_VIRTUAL_THREADS = 'false' }
$def = @{
    # Stock starting point: naive app on Corretto 21 with Hikari's defaults (max 10, min idle = max, 30 s timeout)
    'S0' = @{ jdk = $j21; war = "$w2\naive.war"; app = 'baseline'; port = 8080; db = 'chirp_baseline'; vars = @{ CHIRP_POOL_SIZE = '10'; CHIRP_MIN_IDLE = '10'; CHIRP_CONNECTION_TIMEOUT = '30000' }; jvm = @(); extra = @() }
    # Same war after the pool fix, to repeat L0 alongside S0
    'S1' = @{ jdk = $j21; war = "$w2\naive.war"; app = 'baseline'; port = 8080; db = 'chirp_baseline'; vars = (With @{}); jvm = @(); extra = @() }
    # GORM tuning on the naive app, compileStatic, Corretto 25, platform threads
    'G0' = @{ jdk = $j25; war = "$dir\static-gorm.war"; app = 'baseline'; port = 8080; db = 'chirp_baseline'; vars = (With @{}); jvm = @(); extra = @() }
    'G1' = @{ jdk = $j25; war = "$dir\static-gorm.war"; app = 'baseline'; port = 8080; db = 'chirp_baseline'; vars = (With @{}); jvm = @(); extra = @($batch) }
    'G2' = @{ jdk = $j25; war = "$dir\static-gorm.war"; app = 'baseline'; port = 8080; db = 'chirp_baseline'; vars = (With @{}); jvm = @('-Dchirp.gorm.entityBatchSize=50'); extra = @($batch) }
    'G3' = @{ jdk = $j25; war = "$dir\static-gorm.war"; app = 'baseline'; port = 8080; db = 'chirp_baseline'; vars = (With @{}); jvm = @('-Dchirp.gorm.entityBatchSize=50', '-Dchirp.gorm.groupedCounts=true'); extra = @($batch) }
    # Platform layers on the optimized one-query path, cache off
    'P0' = @{ jdk = $j21; war = "$dir\optimized-j21-dyn.war";    app = 'optimized'; port = 8081; db = 'chirp_optimized'; vars = (With $cache0); jvm = @(); extra = @() }
    'P1' = @{ jdk = $j25; war = "$dir\optimized-j21-dyn.war";    app = 'optimized'; port = 8081; db = 'chirp_optimized'; vars = (With $cache0); jvm = @(); extra = @() }
    'P2' = @{ jdk = $j25; war = "$dir\optimized-j21-static.war"; app = 'optimized'; port = 8081; db = 'chirp_optimized'; vars = (With $cache0); jvm = @(); extra = @() }
    'P3' = @{ jdk = $j25; war = "$dir\optimized-j21-static.war"; app = 'optimized'; port = 8081; db = 'chirp_optimized'; vars = (With @{ CHIRP_CACHE_ENABLED = 'false'; CHIRP_VIRTUAL_THREADS = 'true' }); jvm = @(); extra = @() }
    'P4' = @{ jdk = $j25; war = "$dir\optimized-j21-static.war"; app = 'optimized'; port = 8081; db = 'chirp_optimized'; vars = (With $cache0); jvm = @('-XX:+UseCompactObjectHeaders'); extra = @() }
    # Platform switches on step 5 (all three GORM switches on)
    'H0' = @{ jdk = $j21; war = "$dir\dyn-gorm.war";    app = 'baseline'; port = 8080; db = 'chirp_baseline'; vars = (With @{}); jvm = @('-Dchirp.gorm.entityBatchSize=50', '-Dchirp.gorm.groupedCounts=true'); extra = @($batch) }
    'H1' = @{ jdk = $j25; war = "$dir\dyn-gorm.war";    app = 'baseline'; port = 8080; db = 'chirp_baseline'; vars = (With @{}); jvm = @('-Dchirp.gorm.entityBatchSize=50', '-Dchirp.gorm.groupedCounts=true'); extra = @($batch) }
    'H2' = @{ jdk = $j25; war = "$dir\static-gorm.war"; app = 'baseline'; port = 8080; db = 'chirp_baseline'; vars = (With @{}); jvm = @('-Dchirp.gorm.entityBatchSize=50', '-Dchirp.gorm.groupedCounts=true'); extra = @($batch) }
    'H3' = @{ jdk = $j25; war = "$dir\static-gorm.war"; app = 'baseline'; port = 8080; db = 'chirp_baseline'; vars = (With @{}); jvm = @('-Dchirp.gorm.entityBatchSize=50', '-Dchirp.gorm.groupedCounts=true'); extra = @($batch, '--spring.threads.virtual.enabled=true') }
    'H4' = @{ jdk = $j25; war = "$dir\static-gorm.war"; app = 'baseline'; port = 8080; db = 'chirp_baseline'; vars = (With @{}); jvm = @('-XX:+UseCompactObjectHeaders', '-Dchirp.gorm.entityBatchSize=50', '-Dchirp.gorm.groupedCounts=true'); extra = @($batch) }
    # Platform switches on the final cached app
    'F0' = @{ jdk = $j21; war = "$dir\optimized-j21-dyn.war";    app = 'optimized'; port = 8081; db = 'chirp_optimized'; vars = (With @{ CHIRP_CACHE_ENABLED = 'true'; CHIRP_VIRTUAL_THREADS = 'false' }); jvm = @(); extra = @() }
    'F1' = @{ jdk = $j25; war = "$dir\optimized-j21-dyn.war";    app = 'optimized'; port = 8081; db = 'chirp_optimized'; vars = (With @{ CHIRP_CACHE_ENABLED = 'true'; CHIRP_VIRTUAL_THREADS = 'false' }); jvm = @(); extra = @() }
    'F2' = @{ jdk = $j25; war = "$dir\optimized-j21-static.war"; app = 'optimized'; port = 8081; db = 'chirp_optimized'; vars = (With @{ CHIRP_CACHE_ENABLED = 'true'; CHIRP_VIRTUAL_THREADS = 'false' }); jvm = @(); extra = @() }
    'F3' = @{ jdk = $j25; war = "$dir\optimized-j21-static.war"; app = 'optimized'; port = 8081; db = 'chirp_optimized'; vars = (With @{ CHIRP_CACHE_ENABLED = 'true'; CHIRP_VIRTUAL_THREADS = 'true' }); jvm = @(); extra = @() }
    'F4' = @{ jdk = $j25; war = "$dir\optimized-j21-static.war"; app = 'optimized'; port = 8081; db = 'chirp_optimized'; vars = (With @{ CHIRP_CACHE_ENABLED = 'true'; CHIRP_VIRTUAL_THREADS = 'false' }); jvm = @('-XX:+UseCompactObjectHeaders'); extra = @() }
    # Cache on top of the best one-query config
    'C0' = @{ jdk = $j25; war = "$dir\optimized-j21-static.war"; app = 'optimized'; port = 8081; db = 'chirp_optimized'; vars = (With @{ CHIRP_CACHE_ENABLED = 'true'; CHIRP_VIRTUAL_THREADS = 'false' }); jvm = @(); extra = @() }
    'C1' = @{ jdk = $j25; war = "$dir\optimized-j21-static.war"; app = 'optimized'; port = 8081; db = 'chirp_optimized'; vars = (With @{ CHIRP_CACHE_ENABLED = 'true'; CHIRP_VIRTUAL_THREADS = 'false' }); jvm = @('-XX:+UseCompactObjectHeaders'); extra = @() }
    'C2' = @{ jdk = $j25; war = "$dir\optimized-j21-static.war"; app = 'optimized'; port = 8081; db = 'chirp_optimized'; vars = (With @{ CHIRP_CACHE_ENABLED = 'true'; CHIRP_VIRTUAL_THREADS = 'true' }); jvm = @(); extra = @() }
}

function Invoke-Layer([string]$layer) {
    # A trailing letter after the layer id (G2b, P1b) reruns the same config under a new label.
    $c = $def[($layer -replace '^([A-Z]\d+)[a-z]$', '$1')]
    if (-not $c) { Log "$layer UNKNOWN"; return }
    Wait-Quiet "$layer pre-layer" -NoApps
    foreach ($k in $c.vars.Keys) { Set-Item -Path "Env:$k" -Value $c.vars[$k] }
    $javaArgs = @('-Dgrails.env=loadtest', '-Xms1g', '-Xmx1g', '-XX:+UseG1GC', '-XX:ActiveProcessorCount=12') + $c.jvm + @('-jar', $c.war, '--spring.profiles.active=loadtest') + $c.extra
    $out = Join-Path $dir "$layer-app.out.log"
    $err = Join-Path $dir "$layer-app.err.log"
    $proc = Start-Process -FilePath (Join-Path $c.jdk 'java.exe') -ArgumentList $javaArgs -WorkingDirectory (Join-Path $repo $c.app) -NoNewWindow -PassThru -RedirectStandardOutput $out -RedirectStandardError $err
    foreach ($k in $c.vars.Keys) { Remove-Item -Path "Env:$k" -ErrorAction SilentlyContinue }
    $ready = $false
    for ($i = 0; $i -lt 120; $i++) {
        $code = curl.exe --silent --output NUL --write-out '%{http_code}' --connect-timeout 2 --max-time 5 "http://localhost:$($c.port)/api/ready"
        if ($code -eq '200') { $ready = $true; break }
        Start-Sleep -Seconds 2
    }
    Log "$layer started pid=$($proc.Id) ready=$ready args=$($javaArgs -join ' ') vars=$(($c.vars.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ' ')"
    if (-not $ready) { Log "$layer NOT READY, skipping"; Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue; return }
    $vm = (& (Join-Path $c.jdk 'jcmd.exe') $proc.Id VM.version) -join ' | '
    Log "$layer vm: $vm"
    $flags = (& (Join-Path $c.jdk 'jcmd.exe') $proc.Id VM.flags) -join ' '
    if ($c.jvm -contains '-XX:+UseCompactObjectHeaders') { Log "$layer compactHeaders=$($flags.Contains('+UseCompactObjectHeaders'))" }
    Start-Sleep -Seconds 3
    $conns = (docker exec -e PGPASSWORD=chirp grails8-chirp-postgres psql -U chirp -d postgres -t -A -c "SELECT count(*) FROM pg_stat_activity WHERE datname = '$($c.db)';") -join ''
    Log "$layer db connections=$conns"
    $dump = (& (Join-Path $c.jdk 'jcmd.exe') $proc.Id Thread.print) -join "`n"
    Log "$layer platform http-nio exec threads=$(([regex]::Matches($dump, 'http-nio-\d+-exec-')).Count)"
    for ($r = 1; $r -le $Runs; $r++) {
        Wait-Quiet "$layer run $r"
        $started = Get-Date -Format o
        $seed = Invoke-Reseed $c.db
        $warmLog = Join-Path $dir "$layer-run$r-warm.log"
        & .\gradlew.bat warm "-PbaseUrl=http://localhost:$($c.port)" -Paccounts=2000 -Pconcurrency=20 --console=plain *> $warmLog
        $warmExit = $LASTEXITCODE
        $loadLog = Join-Path $dir "$layer-run$r-load.log"
        $cpuFile = Join-Path $dir "$layer-run$r-cpu.csv"
        Remove-Item -LiteralPath $cpuFile -ErrorAction SilentlyContinue
        $job = Start-Job -ScriptBlock $sampler -ArgumentList $proc.Id, $cpuFile, ($Hold + 40)
        & .\gradlew.bat loadTest "-PbaseUrl=http://localhost:$($c.port)" -Pusers=40 -PrampSeconds=15 "-PholdSeconds=$Hold" --console=plain *> $loadLog
        $loadExit = $LASTEXITCODE
        Stop-Job $job -ErrorAction SilentlyContinue; Remove-Job $job -Force -ErrorAction SilentlyContinue
        $cpu = Read-Samples $cpuFile
        $ended = Get-Date -Format o
        $flaky = Test-Flaky
        $g = Read-Gatling $loadLog
        "$layer,$r,$($g[0]),$($g[1]),$($g[2]),$flaky,$Hold,$started,$ended" | Add-Content $csv
        Log "$layer run $r rps=$($g[0]) ok=$($g[1]) ko=$($g[2]) $seed warm=$warmExit load=$loadExit flakyAtEnd=$flaky $cpu"
    }
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 3
    $timeouts = 0
    foreach ($f in @($out, $err)) { if (Test-Path $f) { $timeouts += ([regex]::Matches((Get-Content -LiteralPath $f -Raw), 'Connection is not available')).Count } }
    Log "$layer stopped; pool timeout lines=$timeouts"
}

Log "batch start: $($Layers -join ',') runs=$Runs hold=$Hold"
docker cp (Join-Path $repo 'db\seed.sql') grails8-chirp-postgres:/tmp/seed.sql *> $null
foreach ($l in $Layers) { Invoke-Layer $l }
Log "batch done: $($Layers -join ',')"
