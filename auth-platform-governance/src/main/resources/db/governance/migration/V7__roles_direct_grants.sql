CREATE TABLE auth_governance.tenant_application (
    tenant_id varchar(36) NOT NULL REFERENCES auth_governance.tenant(id),
    application_id varchar(100) NOT NULL REFERENCES auth_governance.application_catalog(application_id),
    environment varchar(40) NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    created_by varchar(100) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(tenant_id,application_id,environment)
);
COMMENT ON TABLE auth_governance.tenant_application IS '企业应用环境准入，不等同登录成功';
COMMENT ON COLUMN auth_governance.tenant_application.tenant_id IS '企业';
COMMENT ON COLUMN auth_governance.tenant_application.application_id IS '应用';
COMMENT ON COLUMN auth_governance.tenant_application.environment IS '环境';
COMMENT ON COLUMN auth_governance.tenant_application.enabled IS '显式应用准入';
COMMENT ON COLUMN auth_governance.tenant_application.created_by IS '受控登记操作员';
COMMENT ON COLUMN auth_governance.tenant_application.created_at IS '登记时刻';
CREATE TABLE auth_governance.access_delegation (
    tenant_id varchar(36) NOT NULL,
    application_id varchar(100) NOT NULL,
    environment varchar(40) NOT NULL,
    membership_id varchar(36) NOT NULL REFERENCES auth_governance.membership(id),
    generation bigint NOT NULL CHECK (generation > 0),
    capabilities_json text NOT NULL,
    max_duration_seconds bigint NOT NULL CHECK (max_duration_seconds BETWEEN 1 AND 31536000),
    enabled boolean NOT NULL DEFAULT true,
    created_by varchar(100) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(tenant_id,application_id,environment,membership_id),
    FOREIGN KEY(tenant_id,application_id,environment) REFERENCES auth_governance.tenant_application(tenant_id,application_id,environment)
);
COMMENT ON TABLE auth_governance.access_delegation IS '受控初始化管理上限，与业务访问权限分开';
COMMENT ON COLUMN auth_governance.access_delegation.tenant_id IS '企业';
COMMENT ON COLUMN auth_governance.access_delegation.application_id IS '应用';
COMMENT ON COLUMN auth_governance.access_delegation.environment IS '环境';
COMMENT ON COLUMN auth_governance.access_delegation.membership_id IS '获管理委派的成员';
COMMENT ON COLUMN auth_governance.access_delegation.generation IS '委派绑定成员代际';
COMMENT ON COLUMN auth_governance.access_delegation.capabilities_json IS '最大可授予能力集合';
COMMENT ON COLUMN auth_governance.access_delegation.max_duration_seconds IS '最长授权有效秒数';
COMMENT ON COLUMN auth_governance.access_delegation.enabled IS '委派是否有效';
COMMENT ON COLUMN auth_governance.access_delegation.created_by IS '受控初始化操作员';
COMMENT ON COLUMN auth_governance.access_delegation.created_at IS '创建时刻';
CREATE TABLE auth_governance.role_version (
    id varchar(36) PRIMARY KEY,
    tenant_id varchar(36) NOT NULL,
    application_id varchar(100) NOT NULL,
    environment varchar(40) NOT NULL,
    role_code varchar(100) NOT NULL,
    version bigint NOT NULL CHECK (version > 0),
    capabilities_json text NOT NULL,
    content_hash varchar(64) NOT NULL,
    created_by varchar(36) NOT NULL REFERENCES auth_governance.membership(id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,application_id,environment,role_code,version),
    UNIQUE(id,tenant_id,application_id,environment),
    FOREIGN KEY(tenant_id,application_id,environment) REFERENCES auth_governance.tenant_application(tenant_id,application_id,environment)
);
COMMENT ON TABLE auth_governance.role_version IS '不可变角色版本，新增版本不扩大旧授权';
COMMENT ON COLUMN auth_governance.role_version.id IS '角色版本标识';
COMMENT ON COLUMN auth_governance.role_version.tenant_id IS '企业';
COMMENT ON COLUMN auth_governance.role_version.application_id IS '应用';
COMMENT ON COLUMN auth_governance.role_version.environment IS '环境';
COMMENT ON COLUMN auth_governance.role_version.role_code IS '角色稳定编码';
COMMENT ON COLUMN auth_governance.role_version.version IS '固定版本';
COMMENT ON COLUMN auth_governance.role_version.capabilities_json IS '固定权限集合';
COMMENT ON COLUMN auth_governance.role_version.content_hash IS '固定内容摘要';
COMMENT ON COLUMN auth_governance.role_version.created_by IS '创建成员';
COMMENT ON COLUMN auth_governance.role_version.created_at IS '创建时刻';
CREATE TABLE auth_governance.access_grant (
    id varchar(36) PRIMARY KEY,
    tenant_id varchar(36) NOT NULL,
    application_id varchar(100) NOT NULL,
    environment varchar(40) NOT NULL,
    membership_id varchar(36) NOT NULL REFERENCES auth_governance.membership(id),
    generation bigint NOT NULL CHECK (generation > 0),
    role_id varchar(36) NOT NULL,
    scope varchar(32) NOT NULL CHECK (scope='TENANT_ALL'),
    source_type varchar(16) NOT NULL CHECK (source_type='DIRECT'),
    source_id varchar(100) NOT NULL,
    valid_from timestamptz NOT NULL,
    valid_to timestamptz NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('PENDING','ACTIVE','REVOKED')),
    version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
    zed_token text,
    created_by varchar(36) NOT NULL REFERENCES auth_governance.membership(id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK(valid_to > valid_from),
    FOREIGN KEY(role_id,tenant_id,application_id,environment) REFERENCES auth_governance.role_version(id,tenant_id,application_id,environment),
    UNIQUE(tenant_id,application_id,environment,membership_id,generation,source_type,source_id)
);
COMMENT ON TABLE auth_governance.access_grant IS '直接成员完整授权路径，固定来源与时间窗';
COMMENT ON COLUMN auth_governance.access_grant.id IS '完整授权路径标识';
COMMENT ON COLUMN auth_governance.access_grant.tenant_id IS '企业';
COMMENT ON COLUMN auth_governance.access_grant.application_id IS '应用';
COMMENT ON COLUMN auth_governance.access_grant.environment IS '环境';
COMMENT ON COLUMN auth_governance.access_grant.membership_id IS '目标成员';
COMMENT ON COLUMN auth_governance.access_grant.generation IS '授权绑定成员代际';
COMMENT ON COLUMN auth_governance.access_grant.role_id IS '固定角色版本';
COMMENT ON COLUMN auth_governance.access_grant.scope IS '当前仅明确的企业全范围';
COMMENT ON COLUMN auth_governance.access_grant.source_type IS '当前仅直接授权';
COMMENT ON COLUMN auth_governance.access_grant.source_id IS '来源独立标识，不合并来源';
COMMENT ON COLUMN auth_governance.access_grant.valid_from IS '生效开始UTC';
COMMENT ON COLUMN auth_governance.access_grant.valid_to IS '排他截止UTC';
COMMENT ON COLUMN auth_governance.access_grant.state IS '授权当前状态';
COMMENT ON COLUMN auth_governance.access_grant.version IS '状态乐观版本';
COMMENT ON COLUMN auth_governance.access_grant.zed_token IS '图确认水位，不按字符串比较';
COMMENT ON COLUMN auth_governance.access_grant.created_by IS '授予成员';
COMMENT ON COLUMN auth_governance.access_grant.created_at IS '创建时刻';
COMMENT ON COLUMN auth_governance.access_grant.updated_at IS '最新变更时刻';
CREATE TABLE auth_governance.grant_projection (
    grant_id varchar(36) NOT NULL REFERENCES auth_governance.access_grant(id),
    grant_version bigint NOT NULL,
    operation varchar(16) NOT NULL CHECK(operation IN ('UPSERT','DELETE')),
    completed boolean NOT NULL DEFAULT false,
    attempts integer NOT NULL DEFAULT 0 CHECK(attempts >= 0),
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    last_error varchar(64),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(grant_id,grant_version)
);
COMMENT ON TABLE auth_governance.grant_projection IS '与授权同事务的可靠图投影意图';
COMMENT ON COLUMN auth_governance.grant_projection.grant_id IS '目标授权';
COMMENT ON COLUMN auth_governance.grant_projection.grant_version IS '目标授权版本';
COMMENT ON COLUMN auth_governance.grant_projection.operation IS '稳定投影操作';
COMMENT ON COLUMN auth_governance.grant_projection.completed IS '图写与SQL回执完成';
COMMENT ON COLUMN auth_governance.grant_projection.attempts IS '失败次数';
COMMENT ON COLUMN auth_governance.grant_projection.next_attempt_at IS '下次允许执行时刻';
COMMENT ON COLUMN auth_governance.grant_projection.last_error IS '脱敏稳定错误码';
COMMENT ON COLUMN auth_governance.grant_projection.created_at IS '可靠意图创建时刻';
CREATE INDEX access_grant_member_idx ON auth_governance.access_grant(tenant_id,application_id,environment,membership_id,generation,state,id);
CREATE INDEX projection_due_idx ON auth_governance.grant_projection(next_attempt_at,grant_id,grant_version) WHERE completed=false;
CREATE FUNCTION auth_governance.reject_role_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'immutable role version'; END;
$$;
CREATE TRIGGER immutable_role BEFORE UPDATE OR DELETE ON auth_governance.role_version FOR EACH ROW EXECUTE FUNCTION auth_governance.reject_role_mutation();
