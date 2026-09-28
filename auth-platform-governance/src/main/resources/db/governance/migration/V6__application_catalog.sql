CREATE TABLE auth_governance.application_catalog (
    application_id varchar(100) PRIMARY KEY,
    owner_principal_id varchar(36) NOT NULL REFERENCES auth_governance.principal(id),
    entry_origin varchar(500) NOT NULL,
    manifest_version bigint NOT NULL DEFAULT 0 CHECK (manifest_version >= 0),
    created_by varchar(100) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
COMMENT ON TABLE auth_governance.application_catalog IS '应用能力清单权威，应用拥有者不能跨应用覆盖';
COMMENT ON COLUMN auth_governance.application_catalog.application_id IS '稳定应用编码，能力编码的命名空间';
COMMENT ON COLUMN auth_governance.application_catalog.owner_principal_id IS '受控登记的应用拥有者主体';
COMMENT ON COLUMN auth_governance.application_catalog.entry_origin IS '可信入口源，菜单仅使用站内路径';
COMMENT ON COLUMN auth_governance.application_catalog.manifest_version IS '当前已发布清单版本，零表示尚未发布';
COMMENT ON COLUMN auth_governance.application_catalog.created_by IS '登记操作员，来自受控引导配置';
COMMENT ON COLUMN auth_governance.application_catalog.created_at IS '登记时刻，UTC语义';
CREATE TABLE auth_governance.application_manifest (
    application_id varchar(100) NOT NULL REFERENCES auth_governance.application_catalog(application_id),
    version bigint NOT NULL CHECK (version > 0),
    content_hash varchar(64) NOT NULL,
    manifest_json text NOT NULL,
    published_by varchar(36) NOT NULL REFERENCES auth_governance.principal(id),
    published_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(application_id, version)
);
COMMENT ON TABLE auth_governance.application_manifest IS '不可变应用清单历史，发布不修改现有角色版本';
COMMENT ON COLUMN auth_governance.application_manifest.application_id IS '所属应用';
COMMENT ON COLUMN auth_governance.application_manifest.version IS '应用内单调清单版本';
COMMENT ON COLUMN auth_governance.application_manifest.content_hash IS '归一化清单SHA256摘要';
COMMENT ON COLUMN auth_governance.application_manifest.manifest_json IS '经过严格校验的清单快照';
COMMENT ON COLUMN auth_governance.application_manifest.published_by IS '当前已认证且有效的拥有者主体';
COMMENT ON COLUMN auth_governance.application_manifest.published_at IS '发布时刻，UTC语义';
CREATE TABLE auth_governance.catalog_audit (
    id varchar(36) PRIMARY KEY,
    application_id varchar(100) NOT NULL REFERENCES auth_governance.application_catalog(application_id),
    operator_ref varchar(100) NOT NULL,
    operation varchar(32) NOT NULL CHECK (operation IN ('REGISTER','PUBLISH')),
    manifest_version bigint NOT NULL,
    command_id varchar(100) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
COMMENT ON TABLE auth_governance.catalog_audit IS '清单登记与发布的事务审计，不包含登录凭据';
COMMENT ON COLUMN auth_governance.catalog_audit.id IS '审计标识';
COMMENT ON COLUMN auth_governance.catalog_audit.application_id IS '所属应用';
COMMENT ON COLUMN auth_governance.catalog_audit.operator_ref IS '可信操作员或已验证主体';
COMMENT ON COLUMN auth_governance.catalog_audit.operation IS '稳定操作编码';
COMMENT ON COLUMN auth_governance.catalog_audit.manifest_version IS '本次登记或发布版本';
COMMENT ON COLUMN auth_governance.catalog_audit.command_id IS '调用方命令标识，用于关联操作';
COMMENT ON COLUMN auth_governance.catalog_audit.created_at IS '审计时刻，UTC语义';
