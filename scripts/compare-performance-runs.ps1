param(
    [Parameter(Mandatory = $true)][string]$Before,
    [Parameter(Mandatory = $true)][string]$After,
    [double]$ThresholdPercent = 5.0,
    [switch]$AllowMethodologyChange
)
$ErrorActionPreference = 'Stop'

function Read-Lab([string]$Path) {
    if (!(Test-Path -LiteralPath $Path)) { throw "Performance result not found: $Path" }
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Get-100M($Document) {
    $row = $Document.volumeSweep | Where-Object { $_.recordsTarget -eq 100000000 } |
        Select-Object -First 1
    if ($null -eq $row) { throw 'Result does not contain the 100M volume run.' }
    return $row
}

function Delta([double]$Old, [double]$New) {
    if ($Old -eq 0) { return 0.0 }
    return (($New / $Old) - 1.0) * 100.0
}

function Classification([double]$DeltaValue, [bool]$HigherIsBetter) {
    $effective = if ($HigherIsBetter) { $DeltaValue } else { -$DeltaValue }
    if ($effective -gt $ThresholdPercent) { return 'IMPROVEMENT' }
    if ($effective -lt -$ThresholdPercent) { return 'REGRESSION' }
    return 'NEUTRAL'
}

$beforeDoc = Read-Lab $Before
$afterDoc = Read-Lab $After
$before100 = Get-100M $beforeDoc
$after100 = Get-100M $afterDoc

if (!$AllowMethodologyChange) {
    $beforeMethod = $beforeDoc.methodology | ConvertTo-Json -Depth 10 -Compress
    $afterMethod = $afterDoc.methodology | ConvertTo-Json -Depth 10 -Compress
    if ($beforeMethod -ne $afterMethod) {
        throw 'Methodology differs between runs. Use -AllowMethodologyChange only for intentional non-regression comparisons.'
    }
    foreach ($field in @('pageSize', 'concurrency', 'itemBytes', 'syntheticWorkRounds', 'repetitions')) {
        if ($before100.$field -ne $after100.$field) {
            throw "100M configuration differs at '$field'."
        }
    }
}

$metrics = @(
    @{ Name = 'recordsPerSecond'; Higher = $true },
    @{ Name = 'elapsedMillis'; Higher = $false },
    @{ Name = 'estimatedMachineCpuPercent'; Higher = $false },
    @{ Name = 'heapObservedPeakBytes'; Higher = $false },
    @{ Name = 'gcCollectionMillis'; Higher = $false },
    @{ Name = 'p95PageMillis'; Higher = $false }
)

$rows = foreach ($metric in $metrics) {
    $name = $metric.Name
    $old = [double]$before100.$name
    $new = [double]$after100.$name
    $delta = Delta $old $new
    [PSCustomObject]@{
        Metric = $name
        Before = $old
        After = $new
        DeltaPercent = [math]::Round($delta, 2)
        Classification = Classification $delta ([bool]$metric.Higher)
    }
}

$overall = if ($rows.Classification -contains 'REGRESSION') {
    'REVIEW_REQUIRED'
} elseif ($rows.Classification -contains 'IMPROVEMENT') {
    'IMPROVEMENT'
} else {
    'NEUTRAL'
}

Write-Output "=== PERFORMANCE RUN COMPARISON ==="
Write-Output "Before: $Before"
Write-Output "After : $After"
Write-Output "Overall: $overall"
$rows | Format-Table -AutoSize
