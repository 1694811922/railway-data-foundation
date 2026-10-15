param(
    [Parameter(Mandatory=$true)][string]$CapabilityId,
    [Parameter(Mandatory=$true)][string]$RelationshipId,
    [string]$FoundationUrl='http://127.0.0.1:18080',
    [string]$ProviderOrigin='http://127.0.0.1:18081',
    [string]$Fields='', [string]$From='', [string]$To='',
    [ValidateRange(1,100)][int]$PageSize=20,
    [ValidateRange(1,1000)][int]$MaxPages=5
)
$ErrorActionPreference='Stop'
$nodeCommand=Get-Command node -ErrorAction SilentlyContinue
if (-not $nodeCommand) { throw 'Node.js 20.19+ is required. Open a terminal with Node.js on PATH.' }
$secret=Read-Host '请输入需求方调用令牌（不回显）' -AsSecureString
$pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
$keys=@('DEMAND_CAPABILITY_ID','DEMAND_RELATIONSHIP_ID','DEMAND_FOUNDATION_URL','DEMAND_PROVIDER_ORIGIN','DEMAND_FIELDS','DEMAND_FROM','DEMAND_TO','DEMAND_PAGE_SIZE','DEMAND_MAX_PAGES','DEMAND_CALLER_TOKEN')
$saved=@{}
foreach ($key in $keys) { $saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process') }
try {
    $env:DEMAND_CAPABILITY_ID=$CapabilityId; $env:DEMAND_RELATIONSHIP_ID=$RelationshipId
    $env:DEMAND_FOUNDATION_URL=$FoundationUrl; $env:DEMAND_PROVIDER_ORIGIN=$ProviderOrigin
    $env:DEMAND_FIELDS=$Fields; $env:DEMAND_FROM=$From; $env:DEMAND_TO=$To
    $env:DEMAND_PAGE_SIZE=$PageSize.ToString(); $env:DEMAND_MAX_PAGES=$MaxPages.ToString()
    $env:DEMAND_CALLER_TOKEN=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    & $nodeCommand.Source "$PSScriptRoot/../examples/rest-demand/cli.mjs"
    if ($LASTEXITCODE -ne 0) { throw '需求方接入示例未完成，见上方安全错误信息。' }
} finally {
    foreach ($key in $keys) { [Environment]::SetEnvironmentVariable($key,$saved[$key],'Process') }
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    $secret.Dispose()
}
