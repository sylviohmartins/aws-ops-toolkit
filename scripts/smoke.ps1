param([int]$Port = 18081)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $projectRoot 'target/aws-ops-toolkit-0.1.0-SNAPSHOT.jar'
if (!(Test-Path -LiteralPath $jar)) { throw 'Run mvnw verify first.' }
if (!$env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK 25 installation.' }
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$runDirectory = Join-Path $projectRoot ('target/smoke/' + [guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
$oldToken = $env:TOOLKIT_LOCAL_TOKEN
$bytes = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($bytes)
$rng.Dispose()
$env:TOOLKIT_LOCAL_TOKEN = [Convert]::ToBase64String($bytes)
$headers = @{Authorization = 'Bearer ' + $env:TOOLKIT_LOCAL_TOKEN}
$base = "http://127.0.0.1:$Port"
$script:process = $null
$script:launchNumber = 0

function Start-Toolkit {
    $script:launchNumber++
    $arguments = @('-jar', ('"' + $jar + '"'), "--server.port=$Port",
        ('"--toolkit.data-directory=' + (Join-Path $runDirectory 'data') + '"'),
        '--toolkit.minimum-free-bytes=1048576', '--toolkit.max-concurrent-operations=1')
    $script:process = Start-Process -FilePath $java -ArgumentList $arguments -PassThru -WindowStyle Hidden -WorkingDirectory $runDirectory `
        -RedirectStandardOutput (Join-Path $runDirectory "stdout-$script:launchNumber.log") `
        -RedirectStandardError (Join-Path $runDirectory "stderr-$script:launchNumber.log")
    $ready = $false
    for ($i = 0; $i -lt 100; $i++) {
        if ($script:process.HasExited) { throw "Startup failed; inspect $runDirectory" }
        try {
            $health = Invoke-RestMethod -Uri "$base/actuator/health" -Headers $headers
            if ($health.status -eq 'UP') { $ready = $true; break }
        } catch { Start-Sleep -Milliseconds 200 }
    }
    if (!$ready) { throw 'Startup timed out' }
}
function Call-Api($method, $path, $body) {
    $arguments = @{Method=$method; Uri="$base$path"; Headers=$headers}
    if ($null -ne $body) { $arguments.ContentType = 'application/json'; $arguments.Body = ($body | ConvertTo-Json -Depth 5) }
    return Invoke-RestMethod @arguments
}
function Wait-State($id, $expected) {
    for ($i = 0; $i -lt 200; $i++) {
        $state = Call-Api GET "/api/v1/operations/$id" $null
        if ($state.status -eq $expected) { return $state }
        if ($state.status -eq 'FAILED') { throw 'Operation failed' }
        Start-Sleep -Milliseconds 50
    }
    throw "Timed out waiting for $expected"
}
function Expect-Status($expected, [scriptblock]$action) {
    try { & $action | Out-Null } catch {
        if ([int]$_.Exception.Response.StatusCode -eq $expected) { return }
        throw
    }
    throw "Expected HTTP $expected"
}
function Read-TextContent($response) {
    if ($response.Content -is [byte[]]) { return [Text.Encoding]::UTF8.GetString($response.Content) }
    return [string]$response.Content
}
try {
    # Fail before launching if another application owns the chosen port.
    $probe = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, $Port)
    $probe.Start(); $probe.Stop()
    Start-Toolkit
    Expect-Status 401 { Invoke-WebRequest -Uri "$base/actuator/health" -UseBasicParsing }
    Expect-Status 401 { Invoke-WebRequest -Uri "$base/actuator/health" -Headers @{Authorization=$headers.Authorization; Origin='https://example.invalid'} -UseBasicParsing }
    Expect-Status 400 { Call-Api POST '/api/v1/operations/synthetic-inventory' @{mode='EXECUTE';parameters=@{records=10;delayMillis=0}} }
    $created = Call-Api POST '/api/v1/operations/synthetic-inventory' @{mode='DRY_RUN';parameters=@{records=1000;delayMillis=0}}
    $done = Wait-State $created.operationId 'COMPLETED'
    if ($done.cursor -ne 1000) { throw 'Unexpected completed cursor' }
    $csv = Invoke-WebRequest -Uri "$base/api/v1/operations/$($created.operationId)/report" -Headers $headers -UseBasicParsing
    if (([string]$csv.Headers['Content-Type']) -notmatch 'text/csv') { throw 'Incorrect report content type' }
    if (((Read-TextContent $csv) -split "`n" | Where-Object { $_.Trim().Length -gt 0 }).Count -ne 1001) { throw 'Incorrect CSV row count' }
    $job = Call-Api POST '/api/v1/operations/synthetic-inventory' @{mode='DRY_RUN';parameters=@{records=20000;delayMillis=30}}
    Expect-Status 409 { Call-Api POST '/api/v1/operations/synthetic-inventory' @{mode='DRY_RUN';parameters=@{records=10;delayMillis=0}} }
    Call-Api POST "/api/v1/operations/$($job.operationId)/pause" $null | Out-Null
    Wait-State $job.operationId 'PAUSED' | Out-Null
    Call-Api POST "/api/v1/operations/$($job.operationId)/resume" $null | Out-Null
    Start-Sleep -Milliseconds 150
    # Deliberate synthetic crash: only the process started by this script is terminated.
    Stop-Process -Id $script:process.Id -Force
    $script:process.WaitForExit()
    Start-Toolkit
    $interrupted = Call-Api GET "/api/v1/operations/$($job.operationId)" $null
    if ($interrupted.status -ne 'INTERRUPTED') { throw 'Crash was not detected' }
    Call-Api POST "/api/v1/operations/$($job.operationId)/resume" $null | Out-Null
    Wait-State $job.operationId 'COMPLETED' | Out-Null
    $report = Invoke-WebRequest -Uri "$base/api/v1/operations/$($job.operationId)/report" -Headers $headers -UseBasicParsing
    $rows = @((Read-TextContent $report) -split "`n" | Where-Object { $_.Trim().Length -gt 0 })
    if ($rows.Count -ne 20001 -or (@($rows | Select-Object -Unique)).Count -ne 20001) { throw 'Resume duplicated or lost CSV rows' }
    $cancelled = Call-Api POST '/api/v1/operations/synthetic-inventory' @{mode='DRY_RUN';parameters=@{records=20000;delayMillis=30}}
    Call-Api POST "/api/v1/operations/$($cancelled.operationId)/cancel" $null | Out-Null
    Wait-State $cancelled.operationId 'CANCELLED' | Out-Null
    Expect-Status 409 { Call-Api POST "/api/v1/operations/$($cancelled.operationId)/resume" $null }
    Write-Output "SMOKE PASS: auth, origin, write denial, admission, CSV, pause, crash/resume, cancellation. Evidence: $runDirectory"
} finally {
    if ($null -ne $script:process -and !$script:process.HasExited) {
        Stop-Process -Id $script:process.Id -Force
        $script:process.WaitForExit()
    }
    $env:TOOLKIT_LOCAL_TOKEN = $oldToken
}
