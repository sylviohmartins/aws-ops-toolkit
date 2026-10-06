param(
    [ValidateRange(1, 2000000000)][int]$TargetRecords = 25000000,
    [ValidateRange(1, 1000000)][int]$PointSamples = 1000,
    [ValidateRange(1, 10000000)][int]$SequentialLimit = 100000,
    [ValidateRange(1, 1000000)][int]$Tenants = 1000,
    [string]$Table = 'lab-perf-v3-01-rich-uniform',
    [string]$Volume = 'aws-ops-toolkit_localstack-performance-persistent',
    [string]$Image = 'localstack/localstack:4.14.0'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$projectRoot = Split-Path -Parent $PSScriptRoot
$db = '/data/state/dynamodb/123456789012useast1_us-east-1.db'
$profiler = '/lab/profile_dynamodb_sqlite.py'

Push-Location $projectRoot
try {
    $commit = (& git rev-parse HEAD).Trim()
    if (-not $commit) { throw 'Could not resolve git commit.' }
    $dirty = [bool]((& git status --porcelain --untracked-files=normal) -join '')
    $dirtyValue = if ($dirty) { 'true' } else { 'false' }

    Write-Output "SQLITE DIAGNOSTIC SOURCE commit=$commit dirty=$dirtyValue"
    Write-Output "SQLITE DIAGNOSTIC target=$TargetRecords table=$Table pointSamples=$PointSamples sequentialLimit=$SequentialLimit"

    $dockerArgs = @(
        'run','--rm',
        '--network','none',
        '--read-only',
        '--tmpfs','/tmp',
        '-e','PYTHONDONTWRITEBYTECODE=1',
        '-v',"$($Volume):/data:ro",
        '-v',"$projectRoot\lab:/lab:ro",
        '--entrypoint','python',
        $Image,
        $profiler,
        '--db',$db,
        '--table',$Table,
        '--target',"$TargetRecords",
        '--point-samples',"$PointSamples",
        '--sequential-limit',"$SequentialLimit",
        '--tenants',"$Tenants",
        '--git-commit',$commit
    )
    if ($dirty) { $dockerArgs += '--git-dirty' }

    $raw = & docker @dockerArgs
    if ($LASTEXITCODE -ne 0) {
        throw "SQLite diagnostic failed with exit code $LASTEXITCODE."
    }
    $jsonText = $raw -join [Environment]::NewLine
    $report = $jsonText | ConvertFrom-Json
    if ($report.kind -ne 'LOCALSTACK_SQLITE_BACKING_STORE_DIAGNOSTIC') {
        throw "Unexpected diagnostic kind: $($report.kind)"
    }
    if ($report.readOnly -ne $true -or $report.managedDynamoDbEquivalent -ne $false) {
        throw 'SQLite diagnostic safety metadata is invalid.'
    }
    if ($report.cacheControl -ne 'NONE' -or $report.coldCacheGuaranteed -ne $false) {
        throw 'SQLite diagnostic cache semantics are invalid.'
    }
    if ([int64]$report.count.rows -ne $TargetRecords) {
        throw "Unexpected SQLite row count: $($report.count.rows)"
    }
    if ($report.gitCommit -ne $commit -or [bool]$report.gitDirty -ne $dirty) {
        throw 'SQLite diagnostic provenance mismatch.'
    }

    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $dirtyKind = if ($dirty) { '-dirty' } else { '' }
    $historyDir = Join-Path $projectRoot 'benchmark-results\localstack\sqlite-diagnostics'
    New-Item -ItemType Directory -Force -Path $historyDir | Out-Null
    $history = Join-Path $historyDir ("$stamp-$($commit.Substring(0,8))$dirtyKind-$TargetRecords-sqlite-diagnostic.json")
    [System.IO.File]::WriteAllText(
        $history,
        $jsonText + [Environment]::NewLine,
        [System.Text.UTF8Encoding]::new($false))

    Write-Output "SQLITE DIAGNOSTIC READY history=$history"
} finally {
    Pop-Location
}