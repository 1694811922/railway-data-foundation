param([ValidateSet('sharing-live-fixtures.json','demand-rest-fixtures.json')][string]$FixtureManifest='sharing-live-fixtures.json')
. "$PSScriptRoot/local-common.ps1"
. "$PSScriptRoot/load-env.ps1" -Path $LocalConfig
if ($env:FOUNDATION_DB_URL -notmatch '^jdbc:mysql://127\.0\.0\.1:[0-9]+/foundation_db\?') { throw 'Cleanup is restricted to the local Foundation development schema.' }
$manifest=if ($FixtureManifest -eq 'demand-rest-fixtures.json') { Join-Path $LocalProject '.runtime/demand-rest-fixtures.json' } else { Join-Path $LocalProject 'foundation-web/.runtime/sharing-live-fixtures.json' }
if (-not (Test-Path -LiteralPath $manifest)) { throw 'Sharing UI fixture manifest not found.' }
$fixtures=Get-Content -LiteralPath $manifest -Raw -Encoding UTF8 | ConvertFrom-Json
Push-Location $LocalRuntime
try {
    foreach ($fixture in $fixtures) {
        $run=$fixture.run
        if ($run -isnot [string] -or $run -cnotmatch '^[A-F0-9]{32}$') { throw 'Invalid fixture identity; cleanup refused.' }
        # Exact random names were recorded before creation. Refuse cleanup if a
        # user has attached other resources, policies, relations or capabilities.
        $sql=@"
USE foundation_db;
START TRANSACTION;
SET @ui_source=(SELECT source_id FROM foundation_db.source_system WHERE source_name='UI_SHARE_source_$run' FOR UPDATE);
SET @ui_ref=(SELECT credential_ref FROM foundation_db.source_system WHERE source_id=@ui_source);
SET @ui_provider=(SELECT subject_id FROM foundation_db.sharing_subject WHERE subject_code='UI_SHARE_PROVIDER_$run' AND subject_name='UI_SHARE_PROVIDER_$run' AND subject_type='PROVIDER' FOR UPDATE);
SET @ui_demand=(SELECT subject_id FROM foundation_db.sharing_subject WHERE subject_code='UI_SHARE_DEMAND_$run' AND subject_name='UI_SHARE_DEMAND_$run' AND subject_type='DEMAND' FOR UPDATE);
SET @ui_safe=(
 NOT EXISTS(SELECT 1 FROM foundation_db.data_resource WHERE source_id=@ui_source AND resource_name<>'UI_SHARE_resource_$run')
 AND NOT EXISTS(SELECT 1 FROM foundation_db.capability c JOIN foundation_db.data_resource r ON r.resource_id=c.resource_id WHERE r.source_id=@ui_source AND c.capability_name<>'UI_SHARE_capability_$run')
 AND NOT EXISTS(SELECT 1 FROM foundation_db.sharing_relationship x JOIN foundation_db.data_resource r ON r.resource_id=x.resource_id WHERE (r.source_id=@ui_source OR x.provider_subject_id=@ui_provider OR x.demand_subject_id=@ui_demand) AND (x.relationship_name<>'UI_SHARE_relationship_$run' OR NOT(r.source_id <=> @ui_source) OR NOT(x.provider_subject_id <=> @ui_provider) OR NOT(x.demand_subject_id <=> @ui_demand)))
 AND NOT EXISTS(SELECT 1 FROM foundation_db.sharing_relationship x JOIN foundation_db.access_policy a ON a.policy_id=x.policy_id JOIN foundation_db.data_resource r ON r.resource_id=a.resource_id WHERE r.source_id=@ui_source AND x.resource_id<>a.resource_id)
 AND NOT EXISTS(SELECT 1 FROM foundation_db.caller_credential WHERE subject_id IN (@ui_provider,@ui_demand) AND (subject_id<>@ui_demand OR credential_name NOT IN ('UI_SHARE_credential_$run','UI_SHARE_expiring_$run')))
);
DELETE e FROM foundation_db.caller_credential_event e JOIN foundation_db.caller_credential c ON c.credential_id=e.credential_id WHERE c.subject_id=@ui_demand AND @ui_safe;
DELETE FROM foundation_db.caller_credential WHERE subject_id=@ui_demand AND @ui_safe;
DELETE e FROM foundation_db.sharing_relationship_event e JOIN foundation_db.sharing_relationship x ON x.relationship_id=e.relationship_id JOIN foundation_db.data_resource r ON r.resource_id=x.resource_id WHERE r.source_id=@ui_source AND @ui_safe;
DELETE x FROM foundation_db.sharing_relationship x JOIN foundation_db.data_resource r ON r.resource_id=x.resource_id WHERE r.source_id=@ui_source AND @ui_safe;
DELETE a FROM foundation_db.access_policy a JOIN foundation_db.data_resource r ON r.resource_id=a.resource_id WHERE r.source_id=@ui_source AND @ui_safe;
DELETE c FROM foundation_db.capability c JOIN foundation_db.data_resource r ON r.resource_id=c.resource_id WHERE r.source_id=@ui_source AND @ui_safe;
DELETE f FROM foundation_db.resource_field f JOIN foundation_db.data_resource r ON r.resource_id=f.resource_id WHERE r.source_id=@ui_source AND @ui_safe;
DELETE FROM foundation_db.data_resource WHERE source_id=@ui_source AND @ui_safe;
DELETE f FROM foundation_db.metadata_scan_field f JOIN foundation_db.metadata_scan_task t ON t.task_id=f.task_id WHERE t.source_id=@ui_source AND @ui_safe;
DELETE m FROM foundation_db.metadata_scan_table m JOIN foundation_db.metadata_scan_task t ON t.task_id=m.task_id WHERE t.source_id=@ui_source AND @ui_safe;
DELETE FROM foundation_db.metadata_scan_task WHERE source_id=@ui_source AND @ui_safe;
DELETE FROM foundation_db.source_system WHERE source_id=@ui_source AND @ui_safe;
DELETE FROM foundation_db.source_credential WHERE credential_ref=@ui_ref AND @ui_safe AND NOT EXISTS(SELECT 1 FROM foundation_db.source_system WHERE credential_ref=@ui_ref);
DELETE FROM foundation_db.sharing_subject WHERE subject_id IN (@ui_provider,@ui_demand) AND @ui_safe AND NOT EXISTS(SELECT 1 FROM foundation_db.sharing_relationship WHERE provider_subject_id=subject_id OR demand_subject_id=subject_id);
COMMIT;
SELECT (SELECT COUNT(*) FROM foundation_db.source_system WHERE source_name='UI_SHARE_source_$run')+(SELECT COUNT(*) FROM foundation_db.sharing_subject WHERE subject_code IN ('UI_SHARE_PROVIDER_$run','UI_SHARE_DEMAND_$run'));
"@
        $remaining=$sql | & "$env:LOCAL_MYSQL_HOME/bin/mysql.exe" --defaults-extra-file=client.cnf -N -s
        if ($LASTEXITCODE -ne 0 -or [int]$remaining -ne 0) { throw 'Cleanup failed or guard refused unrelated metadata.' }
    }
} finally { Pop-Location }
Write-Host "Cleaned $($fixtures.Count) guarded sharing fixtures; original metadata and Source business data preserved."
