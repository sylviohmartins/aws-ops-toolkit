# Read-only readiness gate for explicitly authorized DEV/HML resources.
# CLI STS is opt-in and does NOT verify the Java SDK's credentials provider.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('DEV', 'HML')]
    [string]$Environment,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9._-]+$')]
    [string]$AwsProfile,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9]{12}$')]
    [string]$ExpectedAccountId,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9+=,.@_-]{1,64}$')]
    [string]$ExpectedRoleName,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z]{2}(-gov)?-[a-z]+-[0-9]+$')]
    [string]$Region,

    [Parameter(Mandatory = $true)]
    [ValidateCount(1, 50)]
    [string[]]$ApprovedResourceArns,

    [switch]$CheckSts,

    [string]$EvidenceDirectory = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# Only region/account-scoped ARNs: no PROD, S3, wildcards or other unverified targets.
$arnPattern = '^arn:(aws|aws-us-gov|aws-cn):(dynamodb|sqs|sns|lambda):([a-z0-9-]+):([0-9]{12}):(.+)$'
$partitions = [System.Collections.Generic.HashSet[string]]::new()
foreach ($resource in $ApprovedResourceArns) {
    $match = [regex]::Match($resource, $arnPattern)
    if (-not $match.Success -or $resource.Contains('*')) {
        throw 'Unsupported resource ARN. Only explicit DynamoDB, SQS, SNS and Lambda ARNs are accepted.'
    }
    if ($match.Groups[3].Value -cne $Region -or $match.Groups[4].Value -cne $ExpectedAccountId) {
        throw 'A resource ARN does not match the explicitly approved account and region.'
    }
    [void]$partitions.Add($match.Groups[1].Value)
}
if ($partitions.Count -ne 1) {
    throw 'All resources must belong to the same AWS partition.'
}
$partition = $partitions | Select-Object -First 1

$identityMatch = $false
if ($CheckSts) {
    if (-not (Get-Command aws -ErrorAction SilentlyContinue)) {
        throw 'AWS CLI unavailable; STS identity was not verified.'
    }
    try {
        $raw = & aws sts get-caller-identity --profile $AwsProfile --region $Region --output json 2>$null
        if ($null -ne $LASTEXITCODE -and $LASTEXITCODE -ne 0) {
            throw 'CLI returned an unsuccessful exit code.'
        }
        $identity = ($raw -join [Environment]::NewLine) | ConvertFrom-Json -ErrorAction Stop
    }
    catch {
        throw 'STS identity could not be verified with the selected CLI profile; no writes attempted.'
    }
    $caller = [regex]::Match(
        [string]$identity.Arn,
        '^arn:(aws|aws-us-gov|aws-cn):sts::([0-9]{12}):assumed-role/([^/]+)/[^/]+$'
    )
    if (-not $caller.Success -or
        $caller.Groups[1].Value -cne $partition -or
        $caller.Groups[2].Value -cne $ExpectedAccountId -or
        $caller.Groups[3].Value -cne $ExpectedRoleName -or
        [string]$identity.Account -cne $ExpectedAccountId) {
        throw 'STS identity does not match the approved account, partition and role.'
    }
    $identityMatch = $true
}

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $EvidenceDirectory) {
    $EvidenceDirectory = Join-Path $projectRoot 'target/dev-hml-preflight'
}
$commit = 'UNKNOWN'
$dirty = $true
if (Get-Command git -ErrorAction SilentlyContinue) {
    $resolved = & git -C $projectRoot rev-parse HEAD 2>$null
    if ($LASTEXITCODE -eq 0 -and $resolved) {
        $commit = ([string]($resolved | Select-Object -First 1)).Trim()
        $changes = & git -C $projectRoot status --porcelain --untracked-files=normal 2>$null
        $dirty = [bool]($changes -join '')
    }
}

$report = [ordered]@{
    schemaVersion = 1
    kind = 'DEV_HML_READ_ONLY_PREFLIGHT'
    checkedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    environment = $Environment
    status = $(if ($identityMatch) { 'CLI_STS_IDENTITY_MATCHED' } else { 'CONFIG_ONLY' })
    approvedResourceCount = $ApprovedResourceArns.Count
    approvedResourcesAccountRegionMatched = $true
    cliStsRequested = [bool]$CheckSts
    cliStsIdentityMatched = $identityMatch
    javaSdkProviderVerified = $false
    awsPermissionsVerified = $false
    writesAuthorized = $false
    remoteWriteCalls = 0
    gitCommit = $commit
    gitDirty = $dirty
}
New-Item -ItemType Directory -Path $EvidenceDirectory -Force | Out-Null
$filename = 'preflight-{0}-{1}.json' -f $Environment.ToLowerInvariant(), [Guid]::NewGuid().ToString('N')
$path = Join-Path $EvidenceDirectory $filename
[IO.File]::WriteAllText(
    $path,
    (($report | ConvertTo-Json -Depth 4) + [Environment]::NewLine),
    [Text.UTF8Encoding]::new($false)
)
Write-Output ("PREFLIGHT status={0} evidence={1}" -f $report.status, $path)
