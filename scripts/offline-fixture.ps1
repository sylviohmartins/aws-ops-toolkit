param(
    [ValidateSet('clone','append','start','validate','full-validate','promote','status','stop')]
    [string]$Action = 'status',
    [long]$SourceRecords = 0,
    [long]$AppendFrom = 0,
    [long]$TargetRecords = 0,
    [ValidateRange(1,10000)][int]$BatchSize = 10000,
    [ValidateRange(1,1024)][int]$Samples = 128,
    [ValidateRange(1,1024)][int]$Segments = 128,
    [ValidateRange(1,256)][int]$Workers = 64,
    [ValidateSet('full-scan','structural-api')][string]$ValidationMode = 'full-scan',
    [switch]$ValidatedOffline
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$volume = 'aws-ops-toolkit_localstack-performance-persistent'
$validator = 'ddb-offline-validate'
$image = 'localstack/localstack:4.14.0'
$dbName = '123456789012useast1_us-east-1.db'
$sourceDb = "/var/lib/localstack/state/dynamodb/$dbName"
$offlineDir = '/var/lib/localstack/offline-test'
$offlineDb = "/data/offline-test/$dbName"
$liveDb = "/data/state/dynamodb/$dbName"
$network = 'aws-ops-toolkit-lab'
$manifestPath = Join-Path $projectRoot '.aws-ops-toolkit\localstack-performance-persistent\seed-manifest.json'
$validationMarkerPath = Join-Path $projectRoot '.aws-ops-toolkit\localstack-performance-persistent\offline-full-validation.json'

function Stop-Validator {
    $existing = @(& docker ps -a --filter "name=$validator" --format '{{.Names}}')
    if ($existing -contains $validator) {
        & docker rm -f $validator | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not remove offline validator container.' }
    }
}

function Start-Validator {
    Stop-Validator
    $args = @(
        'run','-d','--name',$validator,'--no-healthcheck',
        '--memory','5g','--cpus','6',
        '-v',"$($volume):/data",
        '-v',"$($projectRoot)\lab:/lab:ro",
        '--entrypoint','sh',$image,'-lc',
        '/usr/lib/localstack/java/21/bin/java -Xmx3072m -Djava.library.path=/usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib -jar /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal.jar -port 8000 -dbPath /data/offline-test'
    )
    & docker @args | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start offline DynamoDB Local validator.' }
    Start-Sleep -Seconds 3
    Write-Output 'OFFLINE VALIDATOR READY endpoint=http://127.0.0.1:8000 (container-local)'
}

function Assert-PositiveTarget {
    if ($TargetRecords -lt 1) { throw 'TargetRecords must be >= 1 for this action.' }
}

function Invoke-ApiValidation([switch]$FullScan) {
    Assert-PositiveTarget
    $args = @(
        'exec',$validator,'python','/lab/validate_offline_fixture.py',
        '--endpoint','http://127.0.0.1:8000',
        '--access-key','123456789012useast1',
        '--expected',"$TargetRecords",
        '--segments',"$Segments",
        '--workers',"$Workers"
    )
    if ($FullScan) { $args += '--full-scan' }
    & docker @args
    if ($LASTEXITCODE -ne 0) { throw 'Offline fixture API validation failed.' }
}

Push-Location $projectRoot
try {
    switch ($Action) {
        'clone' {
            if ($SourceRecords -lt 1) { throw 'SourceRecords must be >= 1 for clone.' }
            Stop-Validator
            $running = (& docker ps --filter 'name=aws-ops-toolkit-aws-perf-persistent-1' --format '{{.Names}}').Trim()
            if (!$running) { throw 'Start the persistent LocalStack service before clone.' }
            $py = "import os,sqlite3,time; src='$sourceDb'; dst_dir='$offlineDir'; os.makedirs(dst_dir,exist_ok=True); dst=dst_dir+'/$dbName'; os.path.exists(dst) and os.remove(dst); t=time.perf_counter(); s=sqlite3.connect('file:'+src+'?mode=ro',uri=True); d=sqlite3.connect(dst); s.backup(d,pages=10000); d.close(); s.close(); print('OFFLINE_CLONE_SECONDS',time.perf_counter()-t,'BYTES',os.path.getsize(dst))"
            & docker exec aws-ops-toolkit-aws-perf-persistent-1 python -c $py
            if ($LASTEXITCODE -ne 0) { throw 'Offline fixture clone failed.' }
            & docker run --rm -v "$($volume):/data" -v "$($projectRoot)\lab:/lab:ro" --entrypoint python $image /lab/build_dynamodb_sqlite_fixture.py --db $offlineDb --target $SourceRecords --samples $Samples
            if ($LASTEXITCODE -ne 0) { throw 'Cloned fixture structural validation failed.' }
        }
        'append' {
            Assert-PositiveTarget
            if ($AppendFrom -lt 1 -or $TargetRecords -le $AppendFrom) {
                throw 'AppendFrom must be >= 1 and TargetRecords must be greater than AppendFrom.'
            }
            Stop-Validator
            & docker run --rm -v "$($volume):/data" -v "$($projectRoot)\lab:/lab:ro" --entrypoint python $image /lab/build_dynamodb_sqlite_fixture.py --db $offlineDb --append-from $AppendFrom --target $TargetRecords --batch-size $BatchSize
            if ($LASTEXITCODE -ne 0) { throw 'Offline fixture append failed.' }
            & docker run --rm -v "$($volume):/data" -v "$($projectRoot)\lab:/lab:ro" --entrypoint python $image /lab/build_dynamodb_sqlite_fixture.py --db $offlineDb --target $TargetRecords --samples $Samples
            if ($LASTEXITCODE -ne 0) { throw 'Offline fixture structural validation failed.' }
        }
        'start' { Start-Validator }
        'validate' {
            Start-Validator
            Invoke-ApiValidation
            [ordered]@{
                targetRecords = $TargetRecords
                validatedAt = (Get-Date).ToUniversalTime().ToString('o')
                validator = 'STRUCTURAL_PLUS_DYNAMODB_LOCAL_PUBLIC_API_CARDINALITY_PROBES_QUERY'
                validationMode = 'structural-api'
                segments = $Segments
                workers = $Workers
            } | ConvertTo-Json | Set-Content -LiteralPath $validationMarkerPath
            Write-Output "OFFLINE API VALIDATION RECORDED target=$TargetRecords marker=$validationMarkerPath"
        }
        'full-validate' {
            Start-Validator
            Invoke-ApiValidation -FullScan
            [ordered]@{
                targetRecords = $TargetRecords
                validatedAt = (Get-Date).ToUniversalTime().ToString('o')
                validator = 'DYNAMODB_LOCAL_PUBLIC_API_FULL_SCAN'
                validationMode = 'full-scan'
                segments = $Segments
                workers = $Workers
            } | ConvertTo-Json | Set-Content -LiteralPath $validationMarkerPath
            Write-Output "OFFLINE FULL VALIDATION RECORDED target=$TargetRecords marker=$validationMarkerPath"
        }
        'promote' {
            Assert-PositiveTarget
            if (!$ValidatedOffline) {
                throw 'Promotion requires -ValidatedOffline after a recorded validation of the same target.'
            }
            if (!(Test-Path -LiteralPath $validationMarkerPath)) {
                throw 'Promotion requires a recorded full-validate marker.'
            }
            $validationMarker = Get-Content -LiteralPath $validationMarkerPath -Raw | ConvertFrom-Json
            if ([long]$validationMarker.targetRecords -ne $TargetRecords) {
                throw "Validation marker target $($validationMarker.targetRecords) does not match promotion target $TargetRecords."
            }
            if ($validationMarker.validationMode -ne $ValidationMode) {
                throw "Validation marker mode $($validationMarker.validationMode) does not match requested promotion mode $ValidationMode."
            }
            Stop-Validator
            & docker run --rm -v "$($volume):/data" -v "$($projectRoot)\lab:/lab:ro" --entrypoint python $image /lab/build_dynamodb_sqlite_fixture.py --db $offlineDb --target $TargetRecords --samples $Samples
            if ($LASTEXITCODE -ne 0) { throw 'Offline fixture structural validation failed before promotion.' }

            $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
            $backupDb = "/data/offline-backups/$stamp-$dbName"
            & docker compose --profile performance-persistent stop aws-perf-persistent
            if ($LASTEXITCODE -ne 0) { throw 'Could not stop persistent LocalStack before promotion.' }

            $copy = "set -eu; test -f '$liveDb'; test -f '$offlineDb'; mkdir -p /data/offline-backups; cp '$liveDb' '$backupDb'; rm -f '$liveDb-wal' '$liveDb-shm'; cp '$offlineDb' '$liveDb'"
            & docker run --rm -v "$($volume):/data" --entrypoint sh $image -lc $copy
            if ($LASTEXITCODE -ne 0) {
                & docker compose --profile performance-persistent up -d --wait aws-perf-persistent
                throw 'Offline fixture promotion copy failed; original live database was not replaced.'
            }

            try {
                & docker compose --profile performance-persistent up -d --wait aws-perf-persistent
                if ($LASTEXITCODE -ne 0) { throw 'LocalStack did not become healthy after promotion.' }
                & docker run --rm --network $network -v "$($projectRoot)\lab:/lab:ro" --entrypoint python $image /lab/validate_offline_fixture.py --endpoint http://aws-perf-persistent:4566 --access-key 123456789012 --expected $TargetRecords
                if ($LASTEXITCODE -ne 0) { throw 'Promoted fixture failed LocalStack API validation.' }
            } catch {
                & docker compose --profile performance-persistent stop aws-perf-persistent | Out-Null
                $restore = "set -eu; cp '$backupDb' '$liveDb'; rm -f '$liveDb-wal' '$liveDb-shm'"
                & docker run --rm -v "$($volume):/data" --entrypoint sh $image -lc $restore | Out-Null
                & docker compose --profile performance-persistent up -d --wait aws-perf-persistent | Out-Null
                throw
            }

            if (Test-Path -LiteralPath $manifestPath) {
                $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
                $sourceRecords = [long]$manifest.primaryRecords
                $manifest.primaryRecords = $TargetRecords
                if ($manifest.tables -and $manifest.tables.Count -gt 0) {
                    $manifest.tables[0].targetRecords = $TargetRecords
                }
                $manifest | Add-Member -Force NoteProperty fixtureOrigin 'OFFLINE_PROMOTION'
                $manifest | Add-Member -Force NoteProperty offlinePromotion ([ordered]@{
                    promotedAt = (Get-Date).ToUniversalTime().ToString('o')
                    sourceRecords = $sourceRecords
                    targetRecords = $TargetRecords
                    validation = $validationMarker.validator
                    backupDb = $backupDb
                })
                $manifest | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $manifestPath
            }
            Write-Output "OFFLINE PROMOTION OK target=$TargetRecords backup=$backupDb"
        }
        'status' {
            & docker ps -a --filter "name=$validator" --format 'table {{.Names}}\t{{.Status}}'
            & docker run --rm -v "$($volume):/data" --entrypoint sh $image -lc 'du -sh /data/offline-test 2>/dev/null || true'
        }
        'stop' { Stop-Validator }
    }
} finally {
    Pop-Location
}
