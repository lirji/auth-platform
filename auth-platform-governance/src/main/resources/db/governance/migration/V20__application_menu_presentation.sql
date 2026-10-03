-- 展示快照独立于权限JSON，旧应用可继续读取原v1清单并忽略此增量表。
CREATE TABLE auth_governance.application_manifest_presentation (
    application_id varchar(100) NOT NULL,
    version bigint NOT NULL CHECK (version > 0),
    presentation_hash varchar(64) NOT NULL CHECK (presentation_hash ~ '^[a-f0-9]{64}$'),
    presentation_json text NOT NULL CHECK (octet_length(presentation_json) <= 131072 AND jsonb_typeof(presentation_json::jsonb) = 'array' AND jsonb_array_length(presentation_json::jsonb) BETWEEN 1 AND 100),
    published_by varchar(36) NOT NULL REFERENCES auth_governance.principal(id),
    published_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (application_id, version),
    FOREIGN KEY (application_id, version) REFERENCES auth_governance.application_manifest(application_id, version)
);
COMMENT ON TABLE auth_governance.application_manifest_presentation IS '应用Owner不可变菜单名称与顺序快照，不承担权限判定';
COMMENT ON COLUMN auth_governance.application_manifest_presentation.application_id IS '所属应用，必须对应同版本权限清单';
COMMENT ON COLUMN auth_governance.application_manifest_presentation.version IS '固定应用清单版本，显示变更同样发布新版本';
COMMENT ON COLUMN auth_governance.application_manifest_presentation.presentation_hash IS '归一化显示内容SHA256，防止同版改名重试';
COMMENT ON COLUMN auth_governance.application_manifest_presentation.presentation_json IS '经过严格引用与数量校验的菜单编码、中文名称和顺序列表';
COMMENT ON COLUMN auth_governance.application_manifest_presentation.published_by IS '当前已认证且有效的应用拥有者主体';
COMMENT ON COLUMN auth_governance.application_manifest_presentation.published_at IS '与权限发布同事务提交的UTC时刻';
