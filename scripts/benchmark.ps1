param(
    [int[]]$Records = @(1000000, 5000000, 10000000),
    [int]$PageSize = 1000,
    [switch]$SkipConcurrencySweep,
    [switch]$AllowLargeLedger
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (!$env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 25.' }
if ($Records.Count -lt 1) { throw 'Provide at least one record count.' }
if ($Records | Where-Object { $_ -lt 1 -or $_ -gt 100000000 }) {
    throw 'Record counts must be between 1 and 100,000,000.'
}
if (!$AllowLargeLedger -and ($Records | Where-Object { $_ -gt 30000000 })) {
    throw 'Ledger runs above 30M require -AllowLargeLedger because they consume substantial disk/time.'
}
if ($PageSize -lt 1 -or $PageSize -gt 10000) { throw 'PageSize must be 1..10,000.' }

Push-Location $projectRoot
try {
    foreach ($recordCount in $Records) {
        Write-Output "=== LEDGER BENCHMARK records=$recordCount pageSize=$PageSize ==="
        & .\mvnw.cmd -B -ntp test `
            '-Dtoolkit.benchmark=true' `
            "-Dtoolkit.benchmark.records=$recordCount" `
            "-Dtoolkit.benchmark.page-size=$PageSize" `
            '-Dtest=LedgerBenchmarkTest' `
            '-DargLine=-Xmx256m --enable-native-access=ALL-UNNAMED'
        if ($LASTEXITCODE -ne 0) { throw "Ledger benchmark failed for $recordCount records." }
        Get-Content -LiteralPath (Join-Path $projectRoot "target/benchmark/ledger-$recordCount.json")
    }

    if (!$SkipConcurrencySweep) {
        Write-Output '=== LOCAL CONCURRENCY SWEEP ==='
        & .\mvnw.cmd -B -ntp test `
            '-Dtoolkit.benchmark=true' `
            '-Dtest=ConcurrencyBenchmarkTest' `
            '-DargLine=-Xmx256m --enable-native-access=ALL-UNNAMED'
        if ($LASTEXITCODE -ne 0) { throw 'Local concurrency sweep failed.' }
        Get-Content -LiteralPath (Join-Path $projectRoot 'target/benchmark/concurrency-sweep.json')
    }
} finally { Pop-Location }
