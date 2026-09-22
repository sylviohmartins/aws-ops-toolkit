param([ValidateSet('up', 'test', 'down', 'status')][string]$Action = 'up')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    if ($Action -eq 'down') {
        & docker compose down
        if ($LASTEXITCODE -ne 0) { throw 'Could not stop laboratory containers.' }
        return
    }
    if ($Action -eq 'status') {
        & docker compose ps
        if ($LASTEXITCODE -ne 0) { throw 'Could not inspect laboratory containers.' }
        return
    }
    & docker compose up -d --wait
    if ($LASTEXITCODE -ne 0) { throw 'Docker laboratory did not become healthy.' }
    & docker compose exec -T aws python /lab/bootstrap.py
    if ($LASTEXITCODE -ne 0) { throw 'Could not initialize synthetic AWS fixtures.' }
    if ($Action -eq 'test') {
        if (!$env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 25 before running tests.' }
        & .\mvnw.cmd -B -ntp verify -Pstatic-analysis '-Dtoolkit.lab=true'
        if ($LASTEXITCODE -ne 0) { throw 'Docker integration verification failed.' }
        & .\scripts\smoke.ps1
        & .\scripts\lab-smoke.ps1
    } else {
        Write-Output 'Laboratory ready: AWS http://127.0.0.1:4566; payments http://127.0.0.1:8091.'
        Write-Output 'Start the toolkit with profile lab and a randomly generated TOOLKIT_LOCAL_TOKEN.'
    }
} finally { Pop-Location }
