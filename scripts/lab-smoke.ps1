param([int]$Port = 18082)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $projectRoot 'target/aws-ops-toolkit-0.1.0-SNAPSHOT.jar'
if (!$env:JAVA_HOME -or !(Test-Path -LiteralPath $jar)) { throw 'Build with JDK 25 and run docker compose up first.' }
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$runDirectory = Join-Path $projectRoot ('target/lab-smoke/' + [guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
$oldToken = $env:TOOLKIT_CORE_LOCAL_TOKEN
$bytes = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($bytes)
$rng.Dispose()
$env:TOOLKIT_CORE_LOCAL_TOKEN = [Convert]::ToBase64String($bytes)
$headers = @{Authorization = 'Bearer ' + $env:TOOLKIT_CORE_LOCAL_TOKEN}
$base = "http://127.0.0.1:$Port"
$script:process = $null
$script:launch = 0
function Start-LabToolkit {
    $script:launch++
    $arguments = @('--enable-native-access=ALL-UNNAMED', '-jar', ('"' + $jar + '"'), '--spring.profiles.active=lab', "--server.port=$Port",
        ('"--toolkit.core.data-directory=' + (Join-Path $runDirectory 'data') + '"'),
        '--toolkit.core.minimum-free-space=1MB', '--toolkit.operations.requests-per-second=20', '--toolkit.operations.page-size=5')
    $script:process = Start-Process -FilePath $java -ArgumentList $arguments -PassThru -WindowStyle Hidden -WorkingDirectory $runDirectory `
        -RedirectStandardOutput (Join-Path $runDirectory "stdout-$script:launch.log") `
        -RedirectStandardError (Join-Path $runDirectory "stderr-$script:launch.log")
    for ($i = 0; $i -lt 150; $i++) {
        if ($script:process.HasExited) { throw "Startup failed; inspect $runDirectory" }
        try { if ((Invoke-RestMethod -Uri "$base/actuator/health" -Headers $headers).status -eq 'UP') { return } } catch { Start-Sleep -Milliseconds 200 }
    }
    throw 'Startup deadline exceeded.'
}
function Api($method, $path, $body) {
    $arguments = @{Method=$method; Uri="$base/api/v1/jobs$path"; Headers=$headers}
    if ($null -ne $body) { $arguments.ContentType='application/json'; $arguments.Body=($body | ConvertTo-Json -Depth 12) }
    Invoke-RestMethod @arguments
}
function Wait-Job($id, $state) {
    for ($i = 0; $i -lt 800; $i++) {
        $result = Api GET "/$id" $null
        if ($result.state -eq $state) { return $result }
        if ($result.state -in @('FAILED','RECONCILIATION_REQUIRED','AUTHENTICATION_REQUIRED','BUDGET_EXCEEDED')) { throw "Unexpected job state $($result.state)" }
        Start-Sleep -Milliseconds 100
    }
    throw "Deadline waiting for $state. Evidence: $runDirectory"
}
try {
    $probe = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, $Port)
    $probe.Start(); $probe.Stop()
    Push-Location $projectRoot
    try {
        & docker compose exec -T aws python /lab/bootstrap.py | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not reset deterministic laboratory fixtures.' }
    } finally { Pop-Location }
    Start-LabToolkit
    $dryRequest = @{
        operation='payment-repair'
        parameters=@{
            table='lab-payments'
            queue='http://127.0.0.1:4566/123456789012/lab-events'
            topic='arn:aws:sns:us-east-1:123456789012:lab-events'
            function='lab-reconcile:approved'
            evidenceBucket='lab-evidence'
        }
        mode='DRY_RUN';maxRecords=1000;maxCalls=10000;maxSeconds=600;canaryRecords=10
        maxConflicts=0;maxErrors=0;maxErrorRate=0.0;minErrorSample=100;segments=4
        visibilityImpactAccepted=$false;sharedConsumerImpactAccepted=$false
    }
    $dry = Api POST '' $dryRequest
    $dryDone = Wait-Job $dry.id 'DRY_RUN_COMPLETE'
    if ($dryDone.done -ne 0) { throw 'DRY_RUN executed task effects.' }
    $drySummary = Api GET "/$($dry.id)/dry-run" $null
    $drySummary | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $runDirectory 'dry-run.json') -Encoding UTF8
    if ($drySummary.proposedChanges -lt 1 -or !$drySummary.planHash) { throw 'DRY_RUN summary is incomplete.' }
    $dryPlanResponse = Api GET "/$($dry.id)/plan" $null
    $dryPlanPath = Join-Path $runDirectory 'dry-plan.json'
    $dryPlanResponse | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $dryPlanPath -Encoding UTF8
    $sampleRecord = [string]$drySummary.samples[0].record
    $dryPlanText = Get-Content -LiteralPath $dryPlanPath -Raw
    if ($sampleRecord -notmatch '^[0-9a-f]{16}$' -or
        $dryPlanText -notmatch '"record"\s*:\s*"[0-9a-f]{16}"') {
        throw 'DRY_RUN plan redaction failed.'
    }

    $request = @{operation='dynamodb-inventory';parameters=@{table='lab-payments'};mode='EXECUTE';incidentId='INC-LAB-SMOKE';changeId='CHG-LAB-SMOKE';reason='validated local inventory execution';explicitConfirmation=$true;maxRecords=10000;maxCalls=10000;maxSeconds=600;canaryRecords=10;maxConflicts=0;maxErrors=0;maxErrorRate=0.0;minErrorSample=100;segments=4;visibilityImpactAccepted=$false;sharedConsumerImpactAccepted=$false}
    $job = Api POST '' $request
    Api POST "/$($job.id)/pause" $null | Out-Null
    Wait-Job $job.id 'PAUSED' | Out-Null
    Api POST "/$($job.id)/resume-plan" $null | Out-Null
    # Kill only this script's process during active paginated planning.
    Stop-Process -Id $script:process.Id -Force
    $script:process.WaitForExit()
    Start-LabToolkit
    if ((Api GET "/$($job.id)" $null).state -ne 'INTERRUPTED') { throw 'Active plan was not recovered as interrupted.' }
    Api POST "/$($job.id)/resume-plan" $null | Out-Null
    $planned = Wait-Job $job.id 'READY'
    if ($planned.records -lt 20) { throw 'Run lab/bootstrap.py to seed the fixtures.' }
    Api POST "/$($job.id)/approve" @{hash=$planned.hash;reason='LAB-SMOKE reviewed local canary';promote=$false} | Out-Null
    $canary = Wait-Job $job.id 'CANARY_COMPLETE'
    if ($canary.done -ne 10) { throw 'Incorrect canary size.' }
    Api POST "/$($job.id)/approve" @{hash=$planned.hash;reason='LAB-SMOKE reviewed promotion';promote=$true} | Out-Null
    $completed = Wait-Job $job.id 'COMPLETED'
    if ($completed.done -ne $completed.records) { throw 'Incomplete execution reported as completed.' }
    $csvPath = Join-Path $runDirectory 'report.csv'
    $xlsxPath = Join-Path $runDirectory 'report.xlsx'
    Invoke-WebRequest -Uri "$base/api/v1/jobs/$($job.id)/report.csv" -Headers $headers -OutFile $csvPath -UseBasicParsing
    Invoke-WebRequest -Uri "$base/api/v1/jobs/$($job.id)/report.xlsx" -Headers $headers -OutFile $xlsxPath -UseBasicParsing
    if ((Get-Content -LiteralPath $csvPath).Count -ne $completed.records + 1) { throw 'CSV cardinality mismatch.' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($xlsxPath)
    try {
        $reader = [IO.StreamReader]::new($zip.GetEntry('xl/worksheets/sheet1.xml').Open())
        try { $xml = $reader.ReadToEnd() } finally { $reader.Dispose() }
        if ([regex]::Matches($xml, '<row ').Count -ne $completed.records + 1) { throw 'XLSX cardinality mismatch.' }
    } finally { $zip.Dispose() }
    $manifest = Api GET "/$($job.id)/manifest" $null
    $manifest | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $runDirectory 'manifest.json') -Encoding utf8
    if ($manifest.job.hash -ne $planned.hash) { throw 'Plan changed during execution.' }
    Write-Output "LAB SMOKE PASS: durable planning, pause, forced restart, approval, canary, promotion, CSV/XLSX and manifest. Evidence: $runDirectory"
} finally {
    if ($script:process -and !$script:process.HasExited) { Stop-Process -Id $script:process.Id -Force; $script:process.WaitForExit() }
    $env:TOOLKIT_CORE_LOCAL_TOKEN = $oldToken
}
