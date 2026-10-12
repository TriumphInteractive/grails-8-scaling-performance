param([Parameter(Mandatory)][ValidateSet('G0', 'G1', 'G2', 'G3', 'GBefore')][string]$Config)
$ErrorActionPreference = 'Stop'
$root = '<home>\AppData\Local\Temp\opencode\ladder3'
$repo = '<home>\Documents\IdeaProjects\grails-8-scaling-performance'
$log = "$root\$Config-sql.log"
$warName = if ($Config -eq 'GBefore') { 'ladder2\static.war' } else { 'static-gorm.war' }
function Read-LogLines {
    $stream = [System.IO.File]::Open($log, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
    $reader = [System.IO.StreamReader]::new($stream)
    try {
        $lines = [System.Collections.Generic.List[string]]::new()
        while ($null -ne ($line = $reader.ReadLine())) { $lines.Add($line) }
        return ,$lines.ToArray()
    } finally { $reader.Dispose() }
}
if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) {
    throw 'Port 8080 already occupied; refusing to start another server'
}
$si = New-CimInstance -ClassName Win32_ProcessStartup -ClientOnly -Property @{ ShowWindow = [uint16]0 }
$launch = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
    CommandLine = "cmd.exe /d /c `"`"$root\$Config.cmd`"`""
    CurrentDirectory = "$repo\baseline"
    ProcessStartupInformation = $si
}
if ($launch.ReturnValue -ne 0) { throw "Launch failed: $($launch.ReturnValue)" }
$javaId = $null
try {
    $ready = $false
    for ($i = 0; $i -lt 120; $i++) {
        $code = curl.exe --silent --connect-timeout 2 --max-time 5 --output NUL --write-out '%{http_code}' 'http://localhost:8080/api/ready'
        if ($LASTEXITCODE -eq 0 -and $code -eq '200') { $ready = $true; break }
        Start-Sleep 2
    }
    $children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($launch.ProcessId) AND Name='java.exe'")
    if ($children.Count -ne 1 -or -not $children[0].CommandLine.Contains($warName)) {
        throw 'Cannot establish ownership of launched Java process'
    }
    $javaId = $children[0].ProcessId
    if (-not $ready) { throw "$Config readiness failed" }
    $listeners = @(Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue)
    foreach ($listener in $listeners) {
        if ($listener.OwningProcess -ne $javaId) { throw 'Ready endpoint belongs to another process' }
    }
    "$Config ready javaPid=$javaId"
    $results = [ordered]@{ config = $Config; javaPid = $javaId }
    $endpoints = @(
        @{ name = 'timeline'; url = 'http://localhost:8080/api/timelines/user1?max=20'; file = "$root\$Config-user1.json" },
        @{ name = 'account'; url = 'http://localhost:8080/api/accounts/user1'; file = "$root\$Config-account-user1.json" }
    )
    foreach ($endpoint in $endpoints) {
        $code = curl.exe --silent --show-error --max-time 30 --output NUL --write-out '%{http_code}' $endpoint.url
        if ($LASTEXITCODE -ne 0 -or $code -ne '200') { throw "Warm request failed: $code" }
        Start-Sleep 2
        $before = (Read-LogLines).Length
        $code = curl.exe --silent --show-error --max-time 30 --output $endpoint.file --write-out '%{http_code}' $endpoint.url
        if ($LASTEXITCODE -ne 0 -or $code -ne '200') { throw "Measured request failed: $code" }
        Start-Sleep 2
        $lines = Read-LogLines
        $sql = 0
        for ($line = $before; $line -lt $lines.Length; $line++) {
            if ($lines[$line] -match 'org\.hibernate\.SQL\s*:') { $sql++ }
        }
        if ($sql -eq 0) { throw 'No SQL logger lines found in measurement interval' }
        $hash = (Get-FileHash -LiteralPath $endpoint.file -Algorithm SHA256).Hash
        $results[$endpoint.name] = [ordered]@{ beforeLineCount = $before; afterLineCount = $lines.Length; statements = $sql; sha256 = $hash }
        "$Config $($endpoint.name): statements=$sql lines=$before-$($lines.Length) sha256=$hash"
        if ($Config -ne 'G0') {
            $reference = $endpoint.file.Replace($Config, 'G0')
            $referenceHash = (Get-FileHash -LiteralPath $reference -Algorithm SHA256).Hash
            if ($hash -ne $referenceHash) { throw "JSON mismatch: $($endpoint.file) versus $reference" }
        }
    }
    [System.IO.File]::WriteAllText("$root\$Config-results.json", (ConvertTo-Json -InputObject $results -Depth 4))
} finally {
    if ($null -eq $javaId) {
        $children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($launch.ProcessId) AND Name='java.exe'")
        foreach ($child in $children) {
            if ($child.CommandLine.Contains($warName)) { Stop-Process -Id $child.ProcessId -Force }
        }
    } else {
        Stop-Process -Id $javaId -Force
    }
    for ($i = 0; $i -lt 20; $i++) {
        if (-not (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue)) { break }
        Start-Sleep 1
    }
    "$Config stopped; port8080Listening=$([bool](Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue))"
}
