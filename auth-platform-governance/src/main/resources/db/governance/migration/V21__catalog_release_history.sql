-- 只追加来源元数据，不回填旧快照或改变原v1摘要。
CREATE TABLE auth_governance.catalog_release (
    application_id varchar(100) NOT NULL,
    version bigint NOT NULL CHECK(version>0),
    content_hash varchar(64) NOT NULL CHECK(content_hash ~ '^[a-f0-9]{64}$'),
    presentation_hash varchar(64) NOT NULL CHECK(presentation_hash ~ '^[a-f0-9]{64}$'),
    published_by varchar(36) NOT NULL REFERENCES auth_governance.principal(id),
    command_id varchar(100) NOT NULL,
    command_hash varchar(64) NOT NULL CHECK(command_hash ~ '^[a-f0-9]{64}$'),
    source_commit varchar(64) CHECK(source_commit ~ '^([a-f0-9]{40}|[a-f0-9]{64})$'),
    artifact_hash varchar(64) CHECK(artifact_hash ~ '^[a-f0-9]{64}$'),
    reason varchar(500),
    decision varchar(50) CHECK(decision IN ('KEEP_CURRENT_GRANTS','SEPARATE_AUTHORIZATION_REVIEW')),
    base_version bigint NOT NULL CHECK(base_version>=0),
    base_content_hash varchar(64) CHECK(base_content_hash ~ '^[a-f0-9]{64}$'),
    base_presentation_hash varchar(64) CHECK(base_presentation_hash ~ '^[a-f0-9]{64}$'),
    preview_json text NOT NULL CHECK(octet_length(preview_json)<=1048576 AND jsonb_typeof(preview_json::jsonb)='object'),
    PRIMARY KEY(application_id,version),
    UNIQUE(application_id,command_id),
    FOREIGN KEY(application_id,version) REFERENCES auth_governance.application_manifest(application_id,version),
    CHECK((source_commit IS NULL)=(artifact_hash IS NULL)),
    CHECK(base_version<version)
);
COMMENT ON TABLE auth_governance.catalog_release IS '不可变发布来源和原回执，与目录快照及指针同事务';
COMMENT ON COLUMN auth_governance.catalog_release.application_id IS '所属应用';
COMMENT ON COLUMN auth_governance.catalog_release.version IS '固定清单版本';
COMMENT ON COLUMN auth_governance.catalog_release.content_hash IS '原权限摘要';
COMMENT ON COLUMN auth_governance.catalog_release.presentation_hash IS '原显示摘要，空显示亦有摘要';
COMMENT ON COLUMN auth_governance.catalog_release.published_by IS '已认证发布主体';
COMMENT ON COLUMN auth_governance.catalog_release.command_id IS '应用内原发布命令';
COMMENT ON COLUMN auth_governance.catalog_release.command_hash IS '归一化候选及来源处理决定摘要';
COMMENT ON COLUMN auth_governance.catalog_release.source_commit IS '固定源码提交，未知保持空';
COMMENT ON COLUMN auth_governance.catalog_release.artifact_hash IS '原始声明制品摘要，未知保持空';
COMMENT ON COLUMN auth_governance.catalog_release.reason IS '业务发布原因，旧入口保持空';
COMMENT ON COLUMN auth_governance.catalog_release.decision IS '授权处理决定，不改变授权';
COMMENT ON COLUMN auth_governance.catalog_release.base_version IS '原发布基础版本';
COMMENT ON COLUMN auth_governance.catalog_release.base_content_hash IS '原基础权限摘要，首次发布空';
COMMENT ON COLUMN auth_governance.catalog_release.base_presentation_hash IS '原基础展示摘要，首次发布空';
COMMENT ON COLUMN auth_governance.catalog_release.preview_json IS '固定原预览回执，重试不重算当前差异';
CREATE FUNCTION auth_governance.reject_catalog_release_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'catalog release is immutable'; END;
$$;
COMMENT ON FUNCTION auth_governance.reject_catalog_release_mutation() IS '阻止发布来源历史更新或删除，程序回退也保留事实';
CREATE TRIGGER catalog_release_immutable BEFORE UPDATE OR DELETE ON auth_governance.catalog_release
    FOR EACH ROW EXECUTE FUNCTION auth_governance.reject_catalog_release_mutation();
