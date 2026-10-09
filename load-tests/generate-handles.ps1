$ErrorActionPreference = 'Stop'
$resources = Join-Path $PSScriptRoot 'src/gatling/resources'
if (-not (Test-Path -LiteralPath $resources)) {
    throw "Feeder resources directory is missing: $resources"
}
$rows = [System.Collections.Generic.List[string]]::new()
$rows.Add('handle')
for ($accountNumber = 1; $accountNumber -le 2000; $accountNumber++) {
    $weight = if ($accountNumber -le 20) { 100 } elseif ($accountNumber -le 200) { 5 } else { 1 }
    for ($repeat = 0; $repeat -lt $weight; $repeat++) {
        $rows.Add("user$accountNumber")
    }
}
[System.IO.File]::WriteAllLines((Join-Path $resources 'handles.csv'), $rows, [System.Text.UTF8Encoding]::new($false))
"Generated $($rows.Count - 1) weighted handle records"
