-- OA申请仍是同一Grant模型，来源独立且只能绑定固定成员代际。
ALTER TABLE auth_governance.access_grant DROP CONSTRAINT grant_subject_check;
ALTER TABLE auth_governance.access_grant ADD CONSTRAINT grant_subject_check CHECK(
 (source_type IN ('DIRECT','OA_REQUEST') AND membership_id IS NOT NULL AND generation>0 AND group_id IS NULL) OR
 (source_type='GROUP' AND membership_id IS NULL AND generation=0 AND group_id IS NOT NULL));
COMMENT ON COLUMN auth_governance.access_grant.source_type IS '固定来源DIRECT、GROUP或OA_REQUEST；取消申请只撤销本来源';
CREATE UNIQUE INDEX grant_oa_request_unique ON auth_governance.access_grant(tenant_id,application_id,environment,source_id) WHERE source_type='OA_REQUEST';
