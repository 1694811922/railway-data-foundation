param([ValidateSet('rest-live-fixtures.json','resource-live-fixtures.json','schema-comparison-live-fixtures.json','history-live-fixtures.json')][string]$ManifestName='rest-live-fixtures.json')
. "$PSScriptRoot/local-common.ps1"
. "$PSScriptRoot/load-env.ps1" -Path $LocalConfig
if ($env:FOUNDATION_DB_URL -notmatch '^jdbc:mysql://127\.0\.0\.1:[0-9]+/foundation_db\?') { throw 'Cleanup is restricted to the local Foundation development schema.' }
$manifest=Join-Path $LocalProject "foundation-web/.runtime/$ManifestName"
if (-not (Test-Path -LiteralPath $manifest)) { throw 'REST UI fixture manifest not found.' }
# Windows PowerShell 5.1 emits a JSON array as one pipeline object. Wrapping that
# pipeline in @() creates a nested array and silently skips multi-fixture cleanup.
$fixtures=Get-Content -LiteralPath $manifest -Raw -Encoding UTF8 | ConvertFrom-Json
Push-Location $LocalRuntime
try {
    foreach ($fixture in $fixtures) {
        $sourceId=$fixture.source_id
        $ref=$fixture.credential_ref
        if ($sourceId -isnot [string] -or $ref -isnot [string] -or $sourceId -notmatch '^SRC_[a-f0-9]{32}$' -or $ref -notmatch '^CRED_[a-f0-9]{32}$') { throw 'Invalid fixture identity; cleanup refused.' }
        $guard="s.source_id='$sourceId' AND s.credential_ref='$ref' AND LEFT(s.source_name,8)='UI_REST_'"
        $foreignResource="SELECT 1 FROM foundation_db.data_resource r WHERE r.source_id='$sourceId' AND LEFT(r.resource_name,8)<>'UI_REST_'"
        $foreignCapability="SELECT 1 FROM foundation_db.capability c JOIN foundation_db.data_resource r ON r.resource_id=c.resource_id WHERE r.source_id='$sourceId' AND LEFT(c.capability_name,8)<>'UI_REST_'"
        $sql=@"
START TRANSACTION;
SET @ui_ref=(SELECT s.credential_ref FROM foundation_db.source_system s WHERE $guard AND NOT EXISTS ($foreignResource) AND NOT EXISTS ($foreignCapability) FOR UPDATE);
DELETE FROM foundation_db.capability WHERE resource_id IN (SELECT resource_id FROM foundation_db.data_resource WHERE source_id='$sourceId') AND @ui_ref IS NOT NULL;
DELETE FROM foundation_db.resource_field WHERE resource_id IN (SELECT resource_id FROM foundation_db.data_resource WHERE source_id='$sourceId') AND @ui_ref IS NOT NULL;
DELETE FROM foundation_db.data_resource WHERE source_id='$sourceId' AND @ui_ref IS NOT NULL;
DELETE FROM foundation_db.metadata_scan_field WHERE task_id IN (SELECT task_id FROM foundation_db.metadata_scan_task WHERE source_id='$sourceId') AND @ui_ref IS NOT NULL;
DELETE FROM foundation_db.metadata_scan_table WHERE task_id IN (SELECT task_id FROM foundation_db.metadata_scan_task WHERE source_id='$sourceId') AND @ui_ref IS NOT NULL;
DELETE FROM foundation_db.metadata_scan_task WHERE source_id='$sourceId' AND @ui_ref IS NOT NULL;
DELETE FROM foundation_db.source_system WHERE source_id='$sourceId' AND credential_ref=@ui_ref;
DELETE FROM foundation_db.source_credential WHERE credential_ref=@ui_ref AND NOT EXISTS (SELECT 1 FROM foundation_db.source_system WHERE credential_ref=@ui_ref);
COMMIT;
SELECT COUNT(*) FROM foundation_db.source_system WHERE source_id='$sourceId';
"@
        $remaining=$sql | & "$env:LOCAL_MYSQL_HOME/bin/mysql.exe" --defaults-extra-file=client.cnf -N -s
        if ($LASTEXITCODE -ne 0 -or [int]$remaining -ne 0) { throw 'Fixture cleanup failed or guard refused unrelated metadata.' }
    }
} finally { Pop-Location }
Write-Host "Cleaned $($fixtures.Count) guarded REST UI fixtures; original metadata and business rows preserved."
