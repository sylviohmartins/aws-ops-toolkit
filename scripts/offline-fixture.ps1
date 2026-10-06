param(
    [ValidateSet('clone','stage','append','start','validate','full-validate','promote','restore-stage','status','stop')]
    [string]$Action = 'status',
    [long]$SourceRecords = 0,
    [long]$AppendFrom = 0,
    [long]$TargetRecords = 0,
    [ValidateRange(1,10000)][int]$BatchSize = 10000,
    [ValidateRange(1,1024)][int]$Samples = 128,
    [ValidateRange(1,1024)][int]$Segments = 128,
    [ValidateRange(1,256)][int]$Workers = 64,
    [ValidateSet('full-scan','structural-api')][string]$ValidationMode = 'full-scan',
    [ValidatePattern('^\d+(,\d+)*$')][string]$CapacityTargets = '50000000,75000000,100000000',
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
$stageMarkerPath = Join-Path $projectRoot '.aws-ops-toolkit\localstack-performance-persistent\offline-stage.json'

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

function Get-HostFreeBytes {
    return [long](Get-PSDrive C).Free
}

function Get-VolumeFileBytes([string]$Path) {
    $value = (& docker run --rm -v "$($volume):/data" --entrypoint sh $image -lc "if [ -f '$Path' ]; then stat -c %s '$Path'; else echo 0; fi").Trim()
    if ($LASTEXITCODE -ne 0 -or $value -notmatch '^\d+$') {
        throw "Could not determine Docker-volume file size for $Path."
    }
    return [long]$value
}

function Assert-HostReserve([long]$AdditionalBytes, [string]$Operation) {
    $reserveBytes = 10GB
    $freeBytes = Get-HostFreeBytes
    Write-Output ("OFFLINE STORAGE PREFLIGHT operation={0} free={1:N2}GB additional={2:N2}GB reserve={3:N0}GB" -f $Operation,($freeBytes/1GB),($AdditionalBytes/1GB),($reserveBytes/1GB))
    if (($freeBytes - $AdditionalBytes) -lt $reserveBytes) {
        throw "$Operation would violate the 10 GB host-disk reserve. Free or move storage before continuing."
    }
}

function Show-CapacityPlan {
    if (!(Test-Path -LiteralPath $manifestPath)) {
        Write-Output 'CAPACITY PLAN unavailable=seed-manifest-missing'
        return
    }
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    $currentRecords = [long]$manifest.primaryRecords
    $liveBytes = Get-VolumeFileBytes $liveDb
    if ($currentRecords -lt 1 -or $liveBytes -lt 1) {
        Write-Output 'CAPACITY PLAN unavailable=live-fixture-missing-or-invalid'
        return
    }

    $reserveBytes = 10GB
    $freeBytes = Get-HostFreeBytes
    $bytesPerRecord = [double]$liveBytes / [double]$currentRecords
    Write-Output ("CAPACITY CURRENT records={0} dbGiB={1:N2} bytesPerRecord={2:N2} freeGiB={3:N2} reserveGiB={4:N2}" -f $currentRecords,($liveBytes/1GB),$bytesPerRecord,($freeBytes/1GB),($reserveBytes/1GB))

    foreach ($targetText in $CapacityTargets.Split(',')) {
        $target = [long]$targetText
        if ($target -le $currentRecords) {
            Write-Output ("CAPACITY TARGET records={0} status=ALREADY_REACHED" -f $target)
            continue
        }
        $projectedBytes = [long][math]::Ceiling($bytesPerRecord * [double]$target)
        $growthBytes = [long][math]::Max([double]0, [double]($projectedBytes - $liveBytes))
        $minimumInitialFree = [long]($growthBytes + $reserveBytes)
        $deficitBytes = [long][math]::Max([double]0, [double]($minimumInitialFree - $freeBytes))
        $status = if ($deficitBytes -eq 0) { 'PASS_WITHOUT_ROLLBACK_BUDGET' } else { 'BLOCKED' }
        Write-Output ("CAPACITY TARGET records={0} projectedDbGiB={1:N2} growthGiB={2:N2} minimumInitialFreeGiB={3:N2} currentFreeGiB={4:N2} deficitGiB={5:N2} status={6}" -f $target,($projectedBytes/1GB),($growthBytes/1GB),($minimumInitialFree/1GB),($freeBytes/1GB),($deficitBytes/1GB),$status)
    }
    Write-Output 'CAPACITY NOTE minimumInitialFree excludes compressed rollback creation; PASS still requires stage/append preflight and rollback budget.'
}

function Restore-PromotionBackup([string]$BackupDb) {
    & docker compose --profile performance-persistent stop aws-perf-persistent | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "CRITICAL: rollback could not stop persistent LocalStack. Backup remains at $BackupDb."
    }
    $restore = "set -eu; gzip -dc '$BackupDb' > '$liveDb'; rm -f '$liveDb-wal' '$liveDb-shm'"
    & docker run --rm -v "$($volume):/data" --entrypoint sh $image -lc $restore | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "CRITICAL: rollback restore failed. Backup remains at $BackupDb; do not continue the lab until recovered."
    }
    & docker compose --profile performance-persistent up -d --wait aws-perf-persistent | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Rollback restored the database, but persistent LocalStack did not become healthy. Backup remains at $BackupDb."
    }
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
    if ($FullScan) {
        $args += '--full-scan'
    } else {
        $args += '--probe-only'
    }
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
            $sourceBytes = Get-VolumeFileBytes $liveDb
            Assert-HostReserve $sourceBytes 'offline clone'
            $py = "import os,sqlite3,time; src='$sourceDb'; dst_dir='$offlineDir'; os.makedirs(dst_dir,exist_ok=True); dst=dst_dir+'/$dbName'; os.path.exists(dst) and os.remove(dst); t=time.perf_counter(); s=sqlite3.connect('file:'+src+'?mode=ro',uri=True); d=sqlite3.connect(dst); s.backup(d,pages=10000); d.close(); s.close(); print('OFFLINE_CLONE_SECONDS',time.perf_counter()-t,'BYTES',os.path.getsize(dst))"
            & docker exec aws-ops-toolkit-aws-perf-persistent-1 python -c $py
            if ($LASTEXITCODE -ne 0) { throw 'Offline fixture clone failed.' }
            & docker run --rm -v "$($volume):/data" -v "$($projectRoot)\lab:/lab:ro" --entrypoint python $image /lab/build_dynamodb_sqlite_fixture.py --db $offlineDb --target $SourceRecords --samples $Samples
            if ($LASTEXITCODE -ne 0) { throw 'Cloned fixture structural validation failed.' }
        }
        'stage' {
            if ($SourceRecords -lt 1) { throw 'SourceRecords must be >= 1 for stage.' }
            Stop-Validator
            if (Test-Path -LiteralPath $stageMarkerPath) {
                throw 'An offline stage is already active. Promote or restore it before staging again.'
            }
            $running = (& docker ps --filter 'name=aws-ops-toolkit-aws-perf-persistent-1' --format '{{.Names}}').Trim()
            if (!$running) { throw 'Start the persistent LocalStack service before stage.' }
            $liveBytes = Get-VolumeFileBytes $liveDb
            if ($liveBytes -lt 1) { throw 'Live fixture database is missing.' }
            Assert-HostReserve $liveBytes 'offline stage rollback budget'

            $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
            $backupDb = "/data/offline-backups/$stamp-$dbName.gz"
            & docker compose --profile performance-persistent stop aws-perf-persistent
            if ($LASTEXITCODE -ne 0) { throw 'Could not stop persistent LocalStack before stage.' }

            try {
                $backup = "set -eu; test -f '$liveDb'; mkdir -p /data/offline-backups; gzip -1 -c '$liveDb' > '$backupDb'; test -s '$backupDb'"
                & docker run --rm -v "$($volume):/data" --entrypoint sh $image -lc $backup
                if ($LASTEXITCODE -ne 0) { throw 'Could not create compressed rollback backup before stage.' }

                $move = "set -eu; test -f '$liveDb'; test ! -e '$offlineDb'; mkdir -p /data/offline-test; rm -f '$liveDb-wal' '$liveDb-shm'; mv '$liveDb' '$offlineDb'; test -f '$offlineDb'; test ! -e '$liveDb'"
                & docker run --rm -v "$($volume):/data" --entrypoint sh $image -lc $move
                if ($LASTEXITCODE -ne 0) { throw 'Could not move live fixture into offline stage.' }

                [ordered]@{
                    sourceRecords = $SourceRecords
                    stagedAt = (Get-Date).ToUniversalTime().ToString('o')
                    backupDb = $backupDb
                    sourceBytes = $liveBytes
                } | ConvertTo-Json | Set-Content -LiteralPath $stageMarkerPath -Encoding UTF8

                & docker run --rm -v "$($volume):/data" -v "$($projectRoot)\lab:/lab:ro" --entrypoint python $image /lab/build_dynamodb_sqlite_fixture.py --db $offlineDb --target $SourceRecords --samples $Samples
                if ($LASTEXITCODE -ne 0) { throw 'Staged fixture structural validation failed.' }
            } catch {
                if ((Get-VolumeFileBytes $liveDb) -lt 1 -and (Get-VolumeFileBytes $offlineDb) -gt 0) {
                    & docker run --rm -v "$($volume):/data" --entrypoint sh $image -lc "mv '$offlineDb' '$liveDb'"
                }
                & docker compose --profile performance-persistent up -d --wait aws-perf-persistent | Out-Null
                if ((Get-VolumeFileBytes $liveDb) -gt 0 -and $backupDb) {
                    & docker run --rm -v "$($volume):/data" --entrypoint sh $image -lc "rm -f '$backupDb'" | Out-Null
                }
                throw
            }
            Write-Output "OFFLINE STAGE READY source=$SourceRecords backup=$backupDb localstack=stopped"
        }
        'append' {
            Assert-PositiveTarget
            if ($AppendFrom -lt 1 -or $TargetRecords -le $AppendFrom) {
                throw 'AppendFrom must be >= 1 and TargetRecords must be greater than AppendFrom.'
            }
            Stop-Validator
            $offlineBytes = Get-VolumeFileBytes $offlineDb
            if ($offlineBytes -lt 1) { throw 'Offline fixture database is missing; clone it before append.' }
            $bytesPerRecord = [double]$offlineBytes / [double]$AppendFrom
            $projectedBytes = [long][math]::Ceiling($bytesPerRecord * [double]$TargetRecords)
            $growthBytes = [long][math]::Max([double]0, [double]($projectedBytes - $offlineBytes))
            Assert-HostReserve $growthBytes 'offline append'
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
                throw 'Promotion requires a recorded validation marker.'
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

            $stageActive = Test-Path -LiteralPath $stageMarkerPath
            $liveBytes = Get-VolumeFileBytes $liveDb
            $offlineBytes = Get-VolumeFileBytes $offlineDb
            if ($offlineBytes -lt 1) { throw 'Offline fixture database is missing before promotion.' }

            if ($stageActive) {
                if ($liveBytes -gt 0) {
                    throw 'Staged promotion expects the live fixture to remain detached.'
                }
                $stageMarker = Get-Content -LiteralPath $stageMarkerPath -Raw | ConvertFrom-Json
                $backupDb = [string]$stageMarker.backupDb
                $promotionSourceRecords = [long]$stageMarker.sourceRecords
                if ((Get-VolumeFileBytes $backupDb) -lt 1) {
                    throw 'Staged promotion rollback backup is missing.'
                }
                Assert-HostReserve 0 'offline staged promotion'
                & docker compose --profile performance-persistent stop aws-perf-persistent | Out-Null
                $move = "set -eu; test -f '$offlineDb'; mkdir -p /data/state/dynamodb; rm -f '$liveDb' '$liveDb-wal' '$liveDb-shm'; mv '$offlineDb' '$liveDb'; test -f '$liveDb'; test ! -e '$offlineDb'"
                & docker run --rm -v "$($volume):/data" --entrypoint sh $image -lc $move
                if ($LASTEXITCODE -ne 0) {
                    Restore-PromotionBackup $backupDb
                    throw 'Staged fixture promotion move failed; compressed rollback backup was restored.'
                }
            } else {
                if ($liveBytes -lt 1) {
                    throw 'Live fixture database is missing before standard promotion.'
                }
                $replacementGrowth = [long][math]::Max([double]0, [double]($offlineBytes - $liveBytes))
                Assert-HostReserve ($liveBytes + $replacementGrowth) 'offline promotion'

                $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
                $backupDb = "/data/offline-backups/$stamp-$dbName.gz"
                $promotionSourceRecords = 0L
                & docker compose --profile performance-persistent stop aws-perf-persistent
                if ($LASTEXITCODE -ne 0) { throw 'Could not stop persistent LocalStack before promotion.' }

                $backup = "set -eu; test -f '$liveDb'; mkdir -p /data/offline-backups; gzip -1 -c '$liveDb' > '$backupDb'; test -s '$backupDb'"
                & docker run --rm -v "$($volume):/data" --entrypoint sh $image -lc $backup
                if ($LASTEXITCODE -ne 0) {
                    & docker compose --profile performance-persistent up -d --wait aws-perf-persistent | Out-Null
                    throw 'Could not create compressed rollback backup before promotion.'
                }

                $copy = "set -eu; test -f '$offlineDb'; rm -f '$liveDb-wal' '$liveDb-shm'; cp '$offlineDb' '$liveDb'"
                & docker run --rm -v "$($volume):/data" --entrypoint sh $image -lc $copy
                $copySucceeded = $LASTEXITCODE -eq 0
                if ($copySucceeded) {
                    $promotedBytes = Get-VolumeFileBytes $liveDb
                    $copySucceeded = $promotedBytes -eq $offlineBytes
                }
                if (-not $copySucceeded) {
                    Restore-PromotionBackup $backupDb
                    throw 'Offline fixture promotion copy failed or size verification mismatched; compressed rollback backup was restored.'
                }
            }

            try {
                & docker compose --profile performance-persistent up -d --wait aws-perf-persistent
                if ($LASTEXITCODE -ne 0) { throw 'LocalStack did not become healthy after promotion.' }
                & docker run --rm --network $network -v "$($projectRoot)\lab:/lab:ro" --entrypoint python $image /lab/validate_offline_fixture.py --endpoint http://aws-perf-persistent:4566 --access-key 123456789012 --expected $TargetRecords --probe-only --read-timeout 60 --max-attempts 2
                if ($LASTEXITCODE -ne 0) { throw 'Promoted fixture failed LocalStack public API probe validation.' }
            } catch {
                Restore-PromotionBackup $backupDb
                throw
            }

            if (!(Test-Path -LiteralPath $manifestPath)) {
                Restore-PromotionBackup $backupDb
                throw 'Seed manifest is missing after promotion validation; rollback backup was restored.'
            }
            $manifestTempPath = "$manifestPath.tmp"
            try {
                $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
                if ($promotionSourceRecords -lt 1) {
                    $promotionSourceRecords = [long]$manifest.primaryRecords
                }
                if (!$manifest.tables -or $manifest.tables.Count -lt 1) {
                    throw 'Seed manifest does not contain the primary table entry.'
                }
                $manifest.primaryRecords = $TargetRecords
                $manifest.tables[0].targetRecords = $TargetRecords
                $manifest | Add-Member -Force NoteProperty fixtureOrigin 'OFFLINE_PROMOTION'
                $manifest | Add-Member -Force NoteProperty offlinePromotion ([ordered]@{
                    promotedAt = (Get-Date).ToUniversalTime().ToString('o')
                    sourceRecords = $promotionSourceRecords
                    targetRecords = $TargetRecords
                    validation = $validationMarker.validator
                    backupDb = $backupDb
                    strategy = $(if ($stageActive) { 'STAGED_MOVE' } else { 'COPY' })
                })
                $manifest | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $manifestTempPath -Encoding UTF8
                Move-Item -LiteralPath $manifestTempPath -Destination $manifestPath -Force
            } catch {
                Remove-Item -LiteralPath $manifestTempPath -Force -ErrorAction SilentlyContinue
                Restore-PromotionBackup $backupDb
                throw
            }

            & docker run --rm -v "$($volume):/data" --entrypoint sh $image -lc 'rm -rf /data/offline-test'
            if ($LASTEXITCODE -ne 0) {
                Write-Warning 'Promotion succeeded, but the duplicate offline fixture could not be removed.'
            }
            if ($stageActive) {
                Remove-Item -LiteralPath $stageMarkerPath -Force -ErrorAction SilentlyContinue
            }
            Write-Output "OFFLINE PROMOTION OK target=$TargetRecords backup=$backupDb strategy=$(if ($stageActive) { 'STAGED_MOVE' } else { 'COPY' }) offlineFixture=removed"
        }
        'restore-stage' {
            if (!(Test-Path -LiteralPath $stageMarkerPath)) {
                throw 'No offline stage marker exists.'
            }
            Stop-Validator
            $stageMarker = Get-Content -LiteralPath $stageMarkerPath -Raw | ConvertFrom-Json
            $backupDb = [string]$stageMarker.backupDb
            if ((Get-VolumeFileBytes $backupDb) -lt 1) {
                throw 'Offline stage rollback backup is missing.'
            }
            Restore-PromotionBackup $backupDb
            & docker run --rm -v "$($volume):/data" --entrypoint sh $image -lc 'rm -rf /data/offline-test'
            if ($LASTEXITCODE -ne 0) { throw 'Rollback succeeded but offline stage cleanup failed.' }
            Remove-Item -LiteralPath $stageMarkerPath -Force
            Remove-Item -LiteralPath $validationMarkerPath -Force -ErrorAction SilentlyContinue
            Write-Output "OFFLINE STAGE RESTORED backup=$backupDb"
        }
        'status' {
            & docker ps -a --filter "name=$validator" --format 'table {{.Names}}\t{{.Status}}'
            & docker run --rm -v "$($volume):/data" --entrypoint sh $image -lc 'du -sh /data/offline-test 2>/dev/null || true'
            Show-CapacityPlan
        }
        'stop' { Stop-Validator }
    }
} finally {
    Pop-Location
}
