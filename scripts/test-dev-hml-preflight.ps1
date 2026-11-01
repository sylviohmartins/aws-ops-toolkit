# Self-contained offline contract tests; never uses real AWS credentials.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$preflight = Join-Path $PSScriptRoot 'dev-hml-preflight.ps1'
$temp = Join-Path ([IO.Path]::GetTempPath()) ('aws-ops-preflight-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp -Force | Out-Null
$inputArgs = @{
    Environment = 'HML'
    AwsProfile = 'synthetic-hml'
    ExpectedAccountId = '123456789012'
    ExpectedRoleName = 'OpsReadOnly'
    Region = 'sa-east-1'
    ApprovedResourceArns = @(
        'arn:aws:dynamodb:sa-east-1:123456789012:table/synthetic-table'
    )
}
function Assert-Fails([scriptblock]$operation, [string]$label) {
    $failed = $false
    try {
        & $operation | Out-Null
    }
    catch {
        $failed = $true
    }
    if (-not $failed) { throw ('Expected fail-closed: ' + $label) }
}
function Assert-True([bool]$condition, [string]$label) {
    if (-not $condition) { throw ('Assertion failed: ' + $label) }
}

try {
    function global:aws { throw 'No AWS CLI invocation allowed in config-only mode' }
    $configDir = Join-Path $temp 'config'
    & $preflight @inputArgs -EvidenceDirectory $configDir | Out-Null
    $files = @(Get-ChildItem $configDir -Filter '*.json')
    Assert-True ($files.Count -eq 1) 'config-only report generated once'
    $report = Get-Content $files[0].FullName -Raw | ConvertFrom-Json
    Assert-True ($report.status -eq 'CONFIG_ONLY') 'config-only status'
    Assert-True (-not $report.cliStsIdentityMatched) 'STS not assumed'
    Assert-True (-not $report.javaSdkProviderVerified) 'SDK not assumed'
    Assert-True (-not $report.awsPermissionsVerified) 'permissions not assumed'
    Assert-True (-not $report.writesAuthorized) 'writes not authorized'
    Assert-True ($report.remoteWriteCalls -eq 0) 'no remote writes'
    Assert-True ($report.approvedResourceCount -eq 1) 'resources counted'
    $rawReport = Get-Content $files[0].FullName -Raw
    Assert-True (-not $rawReport.Contains('123456789012')) 'account absent from evidence'
    Assert-True (-not $rawReport.Contains('synthetic-hml')) 'profile absent from evidence'
    Assert-True (-not $rawReport.Contains('OpsReadOnly')) 'role absent from evidence'

    $invalid = $inputArgs.Clone()
    $invalid.ApprovedResourceArns = @('arn:aws:dynamodb:us-east-1:123456789012:table/synthetic-table')
    Assert-Fails { & $preflight @invalid -EvidenceDirectory (Join-Path $temp 'bad-region') } 'wrong region'

    $invalid = $inputArgs.Clone()
    $invalid.ApprovedResourceArns = @('arn:aws:sqs:sa-east-1:000000000000:other')
    Assert-Fails { & $preflight @invalid -EvidenceDirectory (Join-Path $temp 'bad-account') } 'wrong account'

    $invalid = $inputArgs.Clone()
    $invalid.ApprovedResourceArns = @('arn:aws:dynamodb:sa-east-1:123456789012:table/*')
    Assert-Fails { & $preflight @invalid -EvidenceDirectory (Join-Path $temp 'bad-wildcard') } 'wildcard'

    Assert-Fails { & $preflight @inputArgs -CheckSts -EvidenceDirectory (Join-Path $temp 'sts-error') } 'STS failure'

    function global:aws {
        $global:LASTEXITCODE = 0
        return '{"Account":"123456789012","Arn":"arn:aws:sts::123456789012:assumed-role/OpsReadOnly/test-session","UserId":"AROATEST:test-session"}'
    }
    $stsDir = Join-Path $temp 'sts-good'
    & $preflight @inputArgs -CheckSts -EvidenceDirectory $stsDir | Out-Null
    $stsReport = Get-Content (@(Get-ChildItem $stsDir -Filter '*.json')[0].FullName) -Raw | ConvertFrom-Json
    Assert-True ($stsReport.status -eq 'CLI_STS_IDENTITY_MATCHED') 'STS match status'
    Assert-True ($stsReport.cliStsIdentityMatched) 'STS comparison'
    Assert-True (-not $stsReport.javaSdkProviderVerified) 'CLI not substituted for SDK'
    Assert-True (-not $stsReport.writesAuthorized) 'still read only'

    function global:aws {
        $global:LASTEXITCODE = 0
        return '{"Account":"123456789012","Arn":"arn:aws:sts::123456789012:assumed-role/WrongRole/test-session","UserId":"AROATEST:test-session"}'
    }
    Assert-Fails { & $preflight @inputArgs -CheckSts -EvidenceDirectory (Join-Path $temp 'wrong-role') } 'wrong STS role'
    Write-Output 'DEV_HML_PREFLIGHT_CONTRACT_TESTS=PASS'
}
finally {
    Remove-Item Function:\aws -ErrorAction SilentlyContinue
    Remove-Item $temp -Recurse -Force -ErrorAction SilentlyContinue
}
