. "$PSScriptRoot/local-common.ps1"
. "$PSScriptRoot/load-env.ps1" -Path $LocalConfig
if ($env:FOUNDATION_DB_URL -notmatch '/foundation_db\?') { throw 'Cleanup is restricted to the local Foundation development schema.' }
$fixturePath=Join-Path $LocalProject 'foundation-web/.runtime/subject-live-fixtures.json'
if (-not (Test-Path -LiteralPath $fixturePath)) { throw 'Subject UI fixture manifest not found.' }
$fixtures=@(Get-Content -LiteralPath $fixturePath -Raw -Encoding UTF8 | ConvertFrom-Json)
Push-Location $LocalRuntime
try {
    foreach ($fixture in $fixtures) {
        $subjectCode=$fixture.subject_code
        if ($subjectCode -cnotmatch '^UI_V03_[A-F0-9]{32}$') { throw 'Invalid fixture identity; cleanup refused.' }
        # Exact random code recorded before the UI request; never remove ordinary subjects or source data.
        & "$env:LOCAL_MYSQL_HOME/bin/mysql.exe" --defaults-extra-file=client.cnf -e "DELETE FROM foundation_db.sharing_subject WHERE subject_code='$subjectCode' AND LEFT(subject_name,7)='UI_V03_';"
        if ($LASTEXITCODE -ne 0) { throw 'Subject fixture cleanup failed.' }
    }
} finally { Pop-Location }
Write-Host "Cleaned $($fixtures.Count) guarded Subject UI fixture codes; business data preserved."
