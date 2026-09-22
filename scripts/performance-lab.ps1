param(
    [int]$ItemBytes = 1024,
    [int]$PageSize = 1000,
    [int]$Concurrency = 8,
    [int]$SelectivityBasisPoints = 1000
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (!$env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 25.' }
if ($ItemBytes -lt 1 -or $ItemBytes -gt 409600) { throw 'ItemBytes must be 1..409600.' }
if ($PageSize -lt 1 -or $PageSize -gt 10000) { throw 'PageSize must be 1..10000.' }
if ($Concurrency -lt 1 -or $Concurrency -gt 256) { throw 'Concurrency must be 1..256.' }
if ($SelectivityBasisPoints -lt 0 -or $SelectivityBasisPoints -gt 10000) {
    throw 'SelectivityBasisPoints must be 0..10000.'
}
Push-Location $projectRoot
try {
    $commit = (& git rev-parse HEAD).Trim()
    Write-Output "=== PERFORMANCE LAB commit=$commit ==="
    $mvnArgs = @(
        '-B', '-ntp', 'test',
        '-Dtoolkit.performance.lab=true',
        "-Dtoolkit.performance.commit=$commit",
        "-Dtoolkit.performance.item-bytes=$ItemBytes",
        "-Dtoolkit.performance.page-size=$PageSize",
        "-Dtoolkit.performance.concurrency=$Concurrency",
        "-Dtoolkit.performance.selectivity-bp=$SelectivityBasisPoints",
        '-Dtest=PerformanceLabBenchmarkTest',
        '-DargLine=-Xmx256m --enable-native-access=ALL-UNNAMED'
    )
    & .\mvnw.cmd @mvnArgs
    if ($LASTEXITCODE -ne 0) { throw 'Performance lab benchmark failed.' }

    $jsonPath = Join-Path $projectRoot 'target/performance-lab/performance-lab.json'
    $result = Get-Content -Raw -LiteralPath $jsonPath | ConvertFrom-Json
    $hundred = $result.volumeSweep | Where-Object { $_.recordsTarget -eq 100000000 }
    $bestConcurrency = $result.concurrencySweep |
        Sort-Object recordsPerSecond -Descending | Select-Object -First 1
    $bestPage = $result.pageSizeSweep |
        Sort-Object recordsPerSecond -Descending | Select-Object -First 1
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $shortCommit = $commit.Substring(0, 8)
    $historyDir = Join-Path $projectRoot 'benchmark-results/performance-lab'
    New-Item -ItemType Directory -Force -Path $historyDir | Out-Null
    $historyPath = Join-Path $historyDir "$stamp-$shortCommit.json"
    Copy-Item -LiteralPath $jsonPath -Destination $historyPath

    $reportPath = Join-Path $projectRoot 'target/performance-lab/report.md'
    $lines = @(
        '# Performance Lab — resultado local',
        '',
        "Commit: $commit",
        "Escopo: $($result.scope)",
        '',
        '## 100 milhões — medido localmente',
        '',
        '| Métrica | Valor |',
        '| --- | ---: |',
        "| Registros examinados | $($hundred.recordsScanned) |",
        "| Tempo | $([math]::Round($hundred.elapsedMillis / 1000, 3)) s |",
        "| Throughput | $([math]::Round($hundred.recordsPerSecond, 0)) registros/s |",
        "| CPU estimada | $([math]::Round($hundred.estimatedMachineCpuPercent, 2))% |",
        "| Heap observado | $([math]::Round($hundred.heapObservedPeakBytes / 1MB, 2)) MiB |",
        "| GC | $($hundred.gcCollections) coleções / $($hundred.gcCollectionMillis) ms |",
        "| p95 por página | $([math]::Round($hundred.p95PageMillis, 4)) ms |",
        "| Work rounds/item | $($hundred.syntheticWorkRounds) |",
        '',
        '## Sweet spots do simulador',
        '',
        "Concorrência de maior throughput: $($bestConcurrency.concurrency) " +
            "($([math]::Round($bestConcurrency.recordsPerSecond, 0)) registros/s).",
        "Page size de maior throughput: $($bestPage.pageSize) " +
            "($([math]::Round($bestPage.recordsPerSecond, 0)) registros/s).",
        '',
        '> Esses sweet spots pertencem ao simulador local e não são recomendação AWS.',
        '',
        '## Validação AWS pendente',
        '',
        'Query/Scan/PartiQL, RCU/WCU, partições, throttling, retries, pool e rede devem ser',
        'medidos em DEV/HML autorizado. Nenhum número deste relatório é apresentado como',
        'throughput real do DynamoDB.'
    )
    Set-Content -LiteralPath $reportPath -Value $lines -Encoding UTF8
    Write-Output "JSON: $jsonPath"
    Write-Output "History: $historyPath"
    Write-Output "Report: $reportPath"
    Get-Content -LiteralPath $reportPath
}
finally {
    Pop-Location
}
