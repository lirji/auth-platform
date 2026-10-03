-- 单向门禁和固定预览独立于历史v1快照；旧写节点退出后才能启用。
CREATE TABLE auth_governance.catalog_guard_policy (
    application_id varchar(100) PRIMARY KEY REFERENCES auth_governance.application_catalog(application_id),
    enabled_by varchar(36) NOT NULL REFERENCES auth_governance.principal(id),
    enabled_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    reason varchar(500) NOT NULL,
    command_id varchar(36) NOT NULL,
    command_hash varchar(64) NOT NULL CHECK(command_hash ~ '^[a-f0-9]{64}$')
);
COMMENT ON TABLE auth_governance.catalog_guard_policy IS '不可变受控模式启用事实及命令审计';
COMMENT ON COLUMN auth_governance.catalog_guard_policy.application_id IS '固定受控应用';
COMMENT ON COLUMN auth_governance.catalog_guard_policy.enabled_by IS '当前已认证Owner';
COMMENT ON COLUMN auth_governance.catalog_guard_policy.enabled_at IS 'PG启用时点';
COMMENT ON COLUMN auth_governance.catalog_guard_policy.reason IS '退出旧写节点与调用方升级说明';
COMMENT ON COLUMN auth_governance.catalog_guard_policy.command_id IS '原启用命令';
COMMENT ON COLUMN auth_governance.catalog_guard_policy.command_hash IS '原命令体摘要';
CREATE TRIGGER catalog_guard_policy_immutable BEFORE UPDATE OR DELETE ON auth_governance.catalog_guard_policy FOR EACH ROW EXECUTE FUNCTION auth_governance.reject_catalog_release_mutation();
CREATE TABLE auth_governance.catalog_release_preview (
    id varchar(36) PRIMARY KEY,
    application_id varchar(100) NOT NULL REFERENCES auth_governance.application_catalog(application_id),
    owner_principal varchar(36) NOT NULL REFERENCES auth_governance.principal(id),
    candidate_json text NOT NULL CHECK(octet_length(candidate_json)<=141312 AND jsonb_typeof(candidate_json::jsonb)='object'),
    candidate_hash varchar(64) NOT NULL CHECK(candidate_hash ~ '^[a-f0-9]{64}$'),
    preview_json text NOT NULL CHECK(octet_length(preview_json)<=1048576 AND jsonb_typeof(preview_json::jsonb)='object'),
    base_version bigint NOT NULL CHECK(base_version>=0),
    base_content_hash varchar(64) CHECK(base_content_hash ~ '^[a-f0-9]{64}$'),
    base_presentation_hash varchar(64) CHECK(base_presentation_hash ~ '^[a-f0-9]{64}$'),
    impact_json text CHECK(octet_length(impact_json)<=4096 AND jsonb_typeof(impact_json::jsonb)='object'),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    CHECK(expires_at=created_at+interval '10 minutes'),
    CHECK((base_version=0 AND base_content_hash IS NULL AND base_presentation_hash IS NULL) OR (base_version>0 AND base_content_hash IS NOT NULL AND base_presentation_hash IS NOT NULL))
);
COMMENT ON TABLE auth_governance.catalog_release_preview IS '固定10分钟发布候选及依据，发布时重新授权';
COMMENT ON COLUMN auth_governance.catalog_release_preview.id IS '服务器固定预览ID，不是权限凭据';
COMMENT ON COLUMN auth_governance.catalog_release_preview.application_id IS '当前Owner应用';
COMMENT ON COLUMN auth_governance.catalog_release_preview.owner_principal IS '生成预览的当前Owner';
COMMENT ON COLUMN auth_governance.catalog_release_preview.candidate_json IS '完整规范化候选与来源原因';
COMMENT ON COLUMN auth_governance.catalog_release_preview.candidate_hash IS '规范化候选语义摘要';
COMMENT ON COLUMN auth_governance.catalog_release_preview.preview_json IS '固定原预览差异，不根据新当前版本重算';
COMMENT ON COLUMN auth_governance.catalog_release_preview.base_version IS '发布基础版本，首次为零';
COMMENT ON COLUMN auth_governance.catalog_release_preview.base_content_hash IS '发布基础权限摘要';
COMMENT ON COLUMN auth_governance.catalog_release_preview.base_presentation_hash IS '发布基础展示摘要';
COMMENT ON COLUMN auth_governance.catalog_release_preview.impact_json IS '显式分区完整影响依据，无跨企业推断';
COMMENT ON COLUMN auth_governance.catalog_release_preview.created_at IS 'PG固定创建时点';
COMMENT ON COLUMN auth_governance.catalog_release_preview.expires_at IS '固定10分钟有效期，已成功原命令重试可过期读取';
CREATE TRIGGER catalog_release_preview_immutable BEFORE UPDATE OR DELETE ON auth_governance.catalog_release_preview FOR EACH ROW EXECUTE FUNCTION auth_governance.reject_catalog_release_mutation();
CREATE INDEX catalog_preview_application_time_idx ON auth_governance.catalog_release_preview(application_id,created_at,id);
