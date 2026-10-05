param(
    [ValidateSet('up','prepare','seed','benchmark','run','status','down')][string]$Action = 'run',
    [ValidateSet('persistent','memory')][string]$Mode = 'persistent',
    [int]$BaselineRecords = 1000,
    [int]$PrimaryRecords = 10000,
    [ValidateRange(1,64)][int]$SeedWorkers = 32,
    [ValidateRange(1,1000)][int]$CheckpointEveryBatches = 25,
    [switch]$DisablePrimaryGsi,
    [switch]$DirectDdbSeed,
    [switch]$AllowReshard,
    [switch]$AllowLowDisk,
    [ValidateRange(1,256)][int]$ScanWorkers = 32,
    [ValidateRange(1,1024)][int]$ScanSegments = 64,
    [ValidateRange(1,10000)][int]$PageSize = 5000,
    [ValidateRange(1,512)][int]$HttpConnections = 128,
    [ValidateRange(1,300)][int]$HttpSocketTimeoutSeconds = 30,
    [ValidateRange(1,300)][int]$ApiAttemptTimeoutSeconds = 35,
    [ValidateRange(1,600)][int]$ApiTimeoutSeconds = 40,
    [ValidateRange(1,10)][int]$RetryMaxAttempts = 1,
    [switch]$ScaleOnly,
    [switch]$FastScale,
    [switch]$ParallelOnly,
    [switch]$ProjectionOnly,
    [switch]$FreshProjection,
    [switch]$WriteCompatibility,
    [switch]$SkipSweeps,
    [switch]$SegmentProfileOnly,
    [ValidatePattern('^\d+(,\d+)*$')][string]$SegmentProfileValues = '128,256,512'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if ($BaselineRecords -lt 1) { throw 'BaselineRecords must be >= 1.' }
if ($PrimaryRecords -lt $BaselineRecords) { throw 'PrimaryRecords must be >= BaselineRecords.' }
if (!$env:JAVA_HOME -and $Action -in @('benchmark','run')) {
    throw 'Set JAVA_HOME to JDK 25 before benchmarking.'
}

$memory = $Mode -eq 'memory'
$service = if ($memory) { 'aws-perf-memory' } else { 'aws-perf-persistent' }
$seedService = if ($memory) { 'seed-memory' } else { 'seed-persistent' }
$endpoint = if ($memory) { 'http://127.0.0.1:4568' } else { 'http://127.0.0.1:4569' }
$modeLabel = if ($memory) { 'IN_MEMORY' } else { 'PERSISTENT' }
$stateRelative = if ($memory) { '.aws-ops-toolkit/localstack-performance-memory' } else { '.aws-ops-toolkit/localstack-performance-persistent' }
$profileArgs = if ($memory) { @('--profile','performance-memory') } else { @('--profile','performance-persistent') }
$stateDir = Join-Path $projectRoot $stateRelative
$manifestPath = Join-Path $stateDir 'seed-manifest.json'
$outputDir = Join-Path $projectRoot ("target/localstack-performance/" + $Mode)

function Invoke-Compose([string[]]$Arguments) {
    & docker compose @profileArgs @Arguments
    if ($LASTEXITCODE -ne 0) { throw "docker compose failed: $($Arguments -join ' ')" }
}

function Start-Lab {
    Invoke-Compose @('up','-d','--wait',$service)
    Write-Output "LOCALSTACK READY mode=$modeLabel endpoint=$endpoint service=$service"
}

function Assert-StorageHeadroom {
    if ($memory -or $AllowLowDisk -or !(Test-Path -LiteralPath $manifestPath)) { return }
    try {
        $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
        $knownRecords = [long]$manifest.primaryRecords
        if ($knownRecords -lt 1) { return }
        $bytesText = (& docker compose @profileArgs exec -T $service sh -lc 'du -sb /var/lib/localstack/state/dynamodb 2>/dev/null | cut -f1').Trim()
        $currentBytes = [double]$bytesText
        if ($currentBytes -le 0) { return }
        $bytesPerRecord = $currentBytes / $knownRecords
        $projectedBytes = $bytesPerRecord * $PrimaryRecords
        $growthBytes = [math]::Max(0, $projectedBytes - $currentBytes)
        $freeBytes = (Get-PSDrive C).Free
        $reserveBytes = 10GB
        Write-Output ("STORAGE PREFLIGHT current={0:N2}GB projected={1:N2}GB free={2:N2}GB reserve={3:N0}GB" -f ($currentBytes/1GB),($projectedBytes/1GB),($freeBytes/1GB),($reserveBytes/1GB))
        if (($freeBytes - $growthBytes) -lt $reserveBytes) {
            throw 'Projected persistent fixture would violate the 10 GB host-disk reserve. Free/move storage or use -AllowLowDisk explicitly.'
        }
    } catch {
        if ($_.Exception.Message -like 'Projected persistent fixture*') { throw }
        Write-Warning ("Storage preflight could not estimate fixture growth: " + $_.Exception.Message)
    }
}

function Assert-BenchmarkHeadroom {
    if ($memory -or $AllowLowDisk) { return }
    $freeBytes = (Get-PSDrive C).Free
    $reserveBytes = 10GB
    Write-Output ("BENCHMARK STORAGE PREFLIGHT free={0:N2}GB reserve={1:N0}GB" -f ($freeBytes/1GB),($reserveBytes/1GB))
    if ($freeBytes -lt $reserveBytes) {
        throw 'Benchmark would start below the 10 GB host-disk reserve. Free/move storage or use -AllowLowDisk explicitly.'
    }
}

function Seed-Lab {
    Start-Lab
    Assert-StorageHeadroom
    New-Item -ItemType Directory -Force -Path $stateDir | Out-Null
    $primaryGsiValue = if ($DisablePrimaryGsi) { '0' } else { '1' }
    $allowReshardValue = if ($AllowReshard) { '1' } else { '0' }
    $args = @(
        'run','--rm','--no-deps',
        '-e',"LAB_PERF_RECORDS=$BaselineRecords",
        '-e',"LAB_PERF_PRIMARY_RECORDS=$PrimaryRecords",
        '-e',"LAB_PERF_SEED_WORKERS=$SeedWorkers",
        '-e',"LAB_PERF_CHECKPOINT_EVERY_BATCHES=$CheckpointEveryBatches",
        '-e',"LAB_PERF_PRIMARY_GSI=$primaryGsiValue",
        '-e',"LAB_PERF_ALLOW_RESHARD=$allowReshardValue"
    )
    if ($DirectDdbSeed) {
        if ($memory) { throw 'DirectDdbSeed is only supported for the persistent LocalStack fixture.' }
        $processLine = (& docker compose @profileArgs exec -T $service sh -lc "ps -ef | grep '[D]ynamoDBLocal.jar'").Trim()
        if ($LASTEXITCODE -ne 0 -or $processLine -notmatch '-port\s+(\d+)') {
            throw 'Could not discover the internal DynamoDB Local port.'
        }
        $directPort = $Matches[1]
        $directEndpoint = ('http://{0}:{1}' -f $service,$directPort)
        $directAccessKey = '123456789012useast1'
        $args += @(
            '-e',"LAB_PERF_ENDPOINT=$directEndpoint",
            '-e',"LAB_PERF_DDB_ACCESS_KEY=$directAccessKey",
            '-e','LAB_PERF_DIRECT_DDB=1'
        )
        Write-Output "DIRECT DDB SEED endpoint=$directEndpoint workers=$SeedWorkers"
    }
    $args += $seedService
    Invoke-Compose $args
    if (!(Test-Path -LiteralPath $manifestPath)) { throw "Seed manifest missing: $manifestPath" }
    Write-Output "SEED READY manifest=$manifestPath"
}
function Invoke-Benchmark {
    if (!(Test-Path -LiteralPath $manifestPath)) { throw "Seed first: missing $manifestPath" }
    Assert-BenchmarkHeadroom
    New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
    $lockPath = Join-Path $outputDir '.benchmark.lock'
    try {
        $benchmarkLock = [System.IO.File]::Open(
            $lockPath,
            [System.IO.FileMode]::OpenOrCreate,
            [System.IO.FileAccess]::ReadWrite,
            [System.IO.FileShare]::None)
    } catch {
        throw "Another LocalStack performance benchmark is already using $outputDir. Wait for it to finish before starting another run."
    }
    try {
    if ($FreshProjection) {
        $checkpointDir = Join-Path $outputDir ("projection-checkpoints/lab-perf-v3-01-rich-uniform-$PrimaryRecords-segments-$ScanSegments-page-$PageSize")
        if (Test-Path -LiteralPath $checkpointDir) {
            Remove-Item -LiteralPath $checkpointDir -Recurse -Force
            Write-Output "PROJECTION CHECKPOINTS RESET path=$checkpointDir"
        }
    }
    $scaleValue = if ($ScaleOnly) { 'true' } else { 'false' }
    $deepValue = if ($FastScale) { 'false' } else { 'true' }
    $parallelOnlyValue = if ($ParallelOnly) { 'true' } else { 'false' }
    $projectionOnlyValue = if ($ProjectionOnly) { 'true' } else { 'false' }
    $writeCompatibilityValue = if ($WriteCompatibility) { 'true' } else { 'false' }
    $runSweepsValue = if ($SkipSweeps) { 'false' } else { 'true' }
    $segmentProfileOnlyValue = if ($SegmentProfileOnly) { 'true' } else { 'false' }
    $mvnArgs = @(
        '-B','-ntp','test',
        '-Dtoolkit.localstack.performance=true',
        "-Dtoolkit.localstack.endpoint=$endpoint",
        "-Dtoolkit.localstack.mode=$modeLabel",
        "-Dtoolkit.localstack.baseline-records=$BaselineRecords",
        "-Dtoolkit.localstack.primary-records=$PrimaryRecords",
        "-Dtoolkit.localstack.scale-only=$scaleValue",
        "-Dtoolkit.localstack.deep-benchmark=$deepValue",
        "-Dtoolkit.localstack.parallel-only=$parallelOnlyValue",
        "-Dtoolkit.localstack.projection-only=$projectionOnlyValue",
        "-Dtoolkit.localstack.write-compatibility=$writeCompatibilityValue",
        "-Dtoolkit.localstack.run-sweeps=$runSweepsValue",
        "-Dtoolkit.localstack.segment-profile-only=$segmentProfileOnlyValue",
        "-Dtoolkit.localstack.segment-profile-values=$SegmentProfileValues",
        "-Dtoolkit.localstack.page-size=$PageSize",
        "-Dtoolkit.localstack.scan-workers=$ScanWorkers",
        "-Dtoolkit.localstack.scan-segments=$ScanSegments",
        "-Dtoolkit.localstack.http-connections=$HttpConnections",
        "-Dtoolkit.localstack.http-socket-timeout-seconds=$HttpSocketTimeoutSeconds",
        "-Dtoolkit.localstack.api-attempt-timeout-seconds=$ApiAttemptTimeoutSeconds",
        "-Dtoolkit.localstack.api-timeout-seconds=$ApiTimeoutSeconds",
        "-Dtoolkit.localstack.retry-max-attempts=$RetryMaxAttempts",
        "-Dtoolkit.localstack.seed-manifest=$manifestPath",
        "-Dtoolkit.localstack.output-dir=$outputDir",
        '-Dtest=LocalStackDynamoPerformanceTest',
        '-DargLine=-Xmx512m --enable-native-access=ALL-UNNAMED'
    )
    & .\mvnw.cmd @mvnArgs
    if ($LASTEXITCODE -ne 0) { throw 'LocalStack DynamoDB benchmark failed.' }

    $json = Join-Path $outputDir 'localstack-dynamodb.json'
    if (!(Test-Path -LiteralPath $json)) { throw "Benchmark output missing: $json" }
    $commit = (& git rev-parse HEAD).Trim()
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $historyDir = Join-Path $projectRoot ("benchmark-results/localstack/" + $Mode)
    New-Item -ItemType Directory -Force -Path $historyDir | Out-Null
    $historyKind = if ($SegmentProfileOnly) { '-segment-profile' } else { '' }
    $history = Join-Path $historyDir ("$stamp-$($commit.Substring(0,8))-$PrimaryRecords$historyKind.json")
    Copy-Item -LiteralPath $json -Destination $history
    Write-Output "BENCHMARK READY json=$json history=$history"
    } finally {
        $benchmarkLock.Dispose()
    }
}

Push-Location $projectRoot
try {
    switch ($Action) {
        'up' { Start-Lab }
        'prepare' { Seed-Lab }
        'seed' { Seed-Lab }
        'benchmark' { Invoke-Benchmark }
        'run' { Start-Lab; Invoke-Benchmark }
        'status' {
            & docker compose @profileArgs ps $service
            if (Test-Path -LiteralPath $manifestPath) {
                Get-Content -LiteralPath $manifestPath
            } else {
                Write-Output "No seed manifest at $manifestPath"
            }
        }
        'down' { Invoke-Compose @('stop',$service) }
    }
} finally {
    Pop-Location
}
