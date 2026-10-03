. "$PSScriptRoot/local-common.ps1"
. "$PSScriptRoot/load-env.ps1" -Path $LocalConfig
if ($env:FOUNDATION_DB_URL -notmatch '/foundation_db\?') { throw 'Cleanup is restricted to the local Foundation development schema.' }
$fixturePath=Join-Path $LocalProject 'foundation-web/.runtime/source-live-fixtures.json'
if (-not (Test-Path -LiteralPath $fixturePath)) { throw 'Source UI fixture manifest not found.' }
$fixtures=Get-Content -LiteralPath $fixturePath -Raw -Encoding UTF8 | ConvertFrom-Json
Push-Location $LocalRuntime
try {
foreach ($fixture in $fixtures) {
    $sourceId=$fixture.source_id
    $credentialRef=$fixture.credential_ref
    if ($sourceId -notmatch '^SRC_[a-f0-9]{32}$' -or $credentialRef -notmatch '^CRED_[a-f0-9]{32}$') { throw 'Invalid fixture identity; cleanup refused.' }
    # Remove only this run's named test metadata. Never delete a resource or a business table.
    $guard="source_id='$sourceId' AND credential_ref='$credentialRef' AND LEFT(source_name,11)='UI_DYNAMIC_'"
    $resourceCount=& "$env:LOCAL_MYSQL_HOME/bin/mysql.exe" --defaults-extra-file=client.cnf -N -s -e "SELECT COUNT(*) FROM foundation_db.data_resource WHERE source_id='$sourceId';"
    if ($LASTEXITCODE -ne 0 -or [int]$resourceCount -ne 0) { throw 'Fixture has resources or cannot be verified; cleanup refused.' }
    $sql=@"
START TRANSACTION;
SET @ui_ref=(SELECT credential_ref FROM foundation_db.source_system WHERE $guard);
DELETE FROM foundation_db.metadata_scan_field WHERE task_id IN (SELECT task_id FROM foundation_db.metadata_scan_task WHERE source_id='$sourceId') AND @ui_ref IS NOT NULL;
DELETE FROM foundation_db.metadata_scan_table WHERE task_id IN (SELECT task_id FROM foundation_db.metadata_scan_task WHERE source_id='$sourceId') AND @ui_ref IS NOT NULL;
DELETE FROM foundation_db.metadata_scan_task WHERE source_id='$sourceId' AND @ui_ref IS NOT NULL;
DELETE FROM foundation_db.source_system WHERE $guard AND @ui_ref IS NOT NULL;
DELETE FROM foundation_db.source_credential WHERE credential_ref=@ui_ref AND NOT EXISTS (SELECT 1 FROM foundation_db.source_system WHERE credential_ref=@ui_ref);
COMMIT;
"@
    $sql | & "$env:LOCAL_MYSQL_HOME/bin/mysql.exe" --defaults-extra-file=client.cnf
    if ($LASTEXITCODE -ne 0) { throw 'Fixture cleanup failed.' }
}
} finally { Pop-Location }
Write-Host "Cleaned $($fixtures.Count) guarded Source UI fixtures; business data preserved."
