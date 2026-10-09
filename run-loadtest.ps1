<#
Run order (from the repository root):
1. A human or later step starts containers, NOT this harness-building task:
   docker compose -p grails8-chirp up -d
2. In separate terminals, a human boots each app in production with the loadtest profile:
   .\gradlew.bat :baseline:bootRun '-Dgrails.env=loadtest' '--args=--spring.profiles.active=loadtest'
   .\gradlew.bat :optimized:bootRun '-Dgrails.env=loadtest' '--args=--spring.profiles.active=loadtest'
   Use grails.env=loadtest, not production. The production block keeps the Forge empty password and would point at port 5432.
   JDBC URLs: jdbc:postgresql://localhost:5438/chirp_baseline
              jdbc:postgresql://localhost:5438/chirp_optimized
   Database user: chirp. Database password: chirp.
 3. Wait for /api/ready on the app you are about to seed.
 4. Seed one database or both. This DELETES existing app data. It never applies schema DDL.
    .\run-loadtest.ps1 -Stage Seed -SeedTarget Baseline
    .\run-loadtest.ps1 -Stage Seed -SeedTarget Optimized
    .\run-loadtest.ps1 -Stage Seed -SeedTarget Both
    Baseline waits only for port 8080 and writes only chirp_baseline.
    Both waits for 8080 and 8081, then seeds each database once.
5. Warmup is a SEPARATE invocation against each app. Discard at least 60 seconds.
   Do not compare a cold JVM, and do not reseed between warmup and measurement.
   Measure ONE app at a time. Two JVMs plus Gatling plus Postgres on one laptop
   will steal the result. Stop the app you are not measuring.
6. Measured run, same command, after warmup:
   .\run-loadtest.ps1 -BaseUrl http://localhost:8080
   .\run-loadtest.ps1 -BaseUrl http://localhost:8081
   The script passes -DbaseUrl to :load-tests:gatlingRun.
   Override durations with -RampSeconds, -HoldSeconds, and concurrency with -Users.
   Report successful requests/s. Timeouts and KO requests are not throughput.
7. Talk ladder, same seed, same heap, G1, no SQL logging. Toggle only one thing:
   - baseline on JDK 21
   - optimized with CHIRP_CACHE_ENABLED=false (one native timeline query plus one account lookup, two statements, no cache)
   - optimized with cache on (expireAfterWrite 20s, refreshAfterWrite 10s)
   Pool size is CHIRP_POOL_SIZE (default 16), applied in ChirpHikariDataSource.
   YAML dataSource.properties does not reach Hikari. Do not set the pool equal to Gatling users.
   Optional, rehearsed, optimized JVM only: JAVA_TOOL_OPTIONS=-XX:+UseCompactObjectHeaders
   For the live number, prefer java -jar of bootWar/bootJar over bootRun so dev reload
   is not in the measurement. bootRun above is the iterate path.
Prerequisites: PowerShell 7, JDK 21 for Gatling, JDK 25 to run optimized, psql on PATH for Seed.
Remote: https://github.com/TriumphInteractive/grails-8-scaling-performance (private).
This script never starts Docker or an application. Stop human-started apps and run
docker compose -p grails8-chirp down when finished; keep the volume unless resetting data.
#>
[CmdletBinding()]
param(
    [ValidateSet('Seed', 'Run')]
    [string] $Stage = 'Run',
    [ValidateSet('Baseline', 'Optimized', 'Both')]
    [string] $SeedTarget = 'Both',
    [string] $BaseUrl = 'http://localhost:8080',
    [int] $Users = 40,
    [int] $RampSeconds = 15,
    [int] $HoldSeconds = 45
)

$ErrorActionPreference = 'Stop'

function Wait-Ready([string] $Url) {
    $deadline = [DateTime]::UtcNow.AddMinutes(3)
    do {
        try {
            $response = Invoke-WebRequest -Uri "$($Url.TrimEnd('/'))/api/ready" -TimeoutSec 5
            if ($response.StatusCode -eq 200) { return }
        } catch {
            if ([DateTime]::UtcNow -ge $deadline) { throw "Readiness timed out for $Url" }
        }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Readiness timed out for $Url"
}

if ($Stage -eq 'Seed') {
    $targets = switch ($SeedTarget) {
        'Baseline' { @(@{ Url = 'http://localhost:8080'; Database = 'chirp_baseline' }) }
        'Optimized' { @(@{ Url = 'http://localhost:8081'; Database = 'chirp_optimized' }) }
        'Both' {
            @(
                @{ Url = 'http://localhost:8080'; Database = 'chirp_baseline' },
                @{ Url = 'http://localhost:8081'; Database = 'chirp_optimized' }
            )
        }
    }
    foreach ($target in $targets) {
        Wait-Ready $target.Url
    }
    $previousPassword = $env:PGPASSWORD
    try {
        $env:PGPASSWORD = 'chirp'
        foreach ($target in $targets) {
            & psql -h localhost -p 5438 -U chirp -d $target.Database -v ON_ERROR_STOP=1 -f (Join-Path $PSScriptRoot 'db/seed.sql')
            if ($LASTEXITCODE -ne 0) { throw "Seeding $($target.Database) failed with exit code $LASTEXITCODE" }
        }
    } finally {
        $env:PGPASSWORD = $previousPassword
    }
    return
}

Wait-Ready $BaseUrl
& (Join-Path $PSScriptRoot 'gradlew.bat') '-p' $PSScriptRoot ':load-tests:gatlingRun' '--simulation=demo.chirp.ChirpSimulation' "-DbaseUrl=$BaseUrl" "-Dusers=$Users" "-DrampSeconds=$RampSeconds" "-DholdSeconds=$HoldSeconds"
if ($LASTEXITCODE -ne 0) { throw "Gatling failed with exit code $LASTEXITCODE" }
