param([string]$Username='foundation_admin')
$ErrorActionPreference='Stop'
. "$PSScriptRoot/local-common.ps1"
. "$PSScriptRoot/load-env.ps1" -Path $LocalConfig
if ($Username -notmatch '^[A-Za-z][A-Za-z0-9_.-]{2,63}$') { throw 'Invalid login username.' }
$jar=Join-Path $LocalProject 'foundation-service/target/foundation-service-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Build the Foundation service first.' }
$password=Read-Host 'Set initial administrator password (at least 12 characters, at most 72 UTF-8 bytes)' -AsSecureString
$pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($password)
try {
    $taskPassword=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    $codePoints=[regex]::Matches($taskPassword,'[\uD800-\uDBFF][\uDC00-\uDFFF]|[\s\S]').Count
    if($codePoints -lt 12 -or [Text.Encoding]::UTF8.GetByteCount($taskPassword) -gt 72) { throw 'Password must contain at least 12 Unicode characters and at most 72 UTF-8 bytes.' }
    $env:FOUNDATION_BOOTSTRAP_ADMIN_USERNAME=$Username
    $env:FOUNDATION_BOOTSTRAP_ADMIN_PASSWORD=$taskPassword
    # Process environment only. Never write the initialization password into .env, SQL or the repository.
    & "$env:LOCAL_JAVA_HOME/bin/java.exe" '-Dfile.encoding=UTF-8' -jar $jar --server.port=0 --foundation.initialize-only=true
    if($LASTEXITCODE -ne 0) { throw 'Administrator initialization failed.' }
    Write-Host 'Initialization finished. Existing administrator accounts are never overwritten or reset.'
} finally {
    $taskPassword=$null
    Remove-Item Env:FOUNDATION_BOOTSTRAP_ADMIN_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:FOUNDATION_BOOTSTRAP_ADMIN_USERNAME -ErrorAction SilentlyContinue
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    $password.Dispose()
}
