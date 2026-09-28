CREATE TABLE auth_governance.principal (
    id varchar(36) PRIMARY KEY,
    kind varchar(16) NOT NULL CHECK (kind IN ('HUMAN', 'SERVICE')),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    version bigint NOT NULL CHECK (version >= 1),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE auth_governance.principal IS '跨企业稳定主体，登录不会恢复停用状态';
COMMENT ON COLUMN auth_governance.principal.id IS '规范 UUID 主体标识';
COMMENT ON COLUMN auth_governance.principal.kind IS '主体种类 HUMAN 人类或 SERVICE 服务';
COMMENT ON COLUMN auth_governance.principal.status IS '当前全局状态';
COMMENT ON COLUMN auth_governance.principal.version IS '当前状态版本，条件更新防并发覆盖';
COMMENT ON COLUMN auth_governance.principal.created_at IS '治理库创建时间，UTC 时刻';

CREATE TABLE auth_governance.login_identity (
    issuer varchar(500) NOT NULL,
    subject varchar(500) NOT NULL,
    principal_id varchar(36) NOT NULL REFERENCES auth_governance.principal(id),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (issuer, subject)
);
COMMENT ON TABLE auth_governance.login_identity IS '发行方与 subject 精确身份映射，不以邮箱合并';
COMMENT ON COLUMN auth_governance.login_identity.issuer IS 'Token 发行方原始精确标识';
COMMENT ON COLUMN auth_governance.login_identity.subject IS '发行方 subject 原始标识';
COMMENT ON COLUMN auth_governance.login_identity.principal_id IS '受控绑定的全局主体';
COMMENT ON COLUMN auth_governance.login_identity.created_at IS '受控绑定提交时间';
CREATE INDEX login_identity_principal_idx ON auth_governance.login_identity(principal_id);

CREATE TABLE auth_governance.tenant (
    id varchar(36) PRIMARY KEY,
    code varchar(100) NOT NULL UNIQUE,
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    version bigint NOT NULL CHECK (version >= 1),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE auth_governance.tenant IS '治理业务企业隔离边界，不等同认证组织';
COMMENT ON COLUMN auth_governance.tenant.id IS '规范 UUID 企业标识';
COMMENT ON COLUMN auth_governance.tenant.code IS '唯一企业业务编码';
COMMENT ON COLUMN auth_governance.tenant.status IS '企业当前全局状态';
COMMENT ON COLUMN auth_governance.tenant.version IS '企业状态条件更新版本';
COMMENT ON COLUMN auth_governance.tenant.created_at IS '企业治理记录创建时间';

CREATE TABLE auth_governance.membership (
    id varchar(36) PRIMARY KEY,
    tenant_id varchar(36) NOT NULL REFERENCES auth_governance.tenant(id),
    principal_id varchar(36) NOT NULL REFERENCES auth_governance.principal(id),
    member_kind varchar(16) NOT NULL CHECK (member_kind IN ('EMPLOYEE', 'PARTNER', 'GUEST')),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'LEFT')),
    generation bigint NOT NULL CHECK (generation >= 1),
    version bigint NOT NULL CHECK (version >= 1),
    valid_from timestamptz NOT NULL,
    valid_to timestamptz,
    sponsor_membership_id varchar(36),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, principal_id),
    UNIQUE (id, tenant_id),
    UNIQUE (id, principal_id, tenant_id),
    FOREIGN KEY (sponsor_membership_id, tenant_id) REFERENCES auth_governance.membership(id, tenant_id),
    CHECK (valid_to IS NULL OR valid_to > valid_from),
    CHECK (member_kind = 'EMPLOYEE' OR sponsor_membership_id IS NOT NULL),
    CHECK (sponsor_membership_id IS NULL OR sponsor_membership_id <> id)
);
COMMENT ON TABLE auth_governance.membership IS '主体在企业的唯一当前成员关系，重新加入增加代际';
COMMENT ON COLUMN auth_governance.membership.id IS '规范 UUID 成员标识，生命周期中保留';
COMMENT ON COLUMN auth_governance.membership.tenant_id IS '成员所属企业';
COMMENT ON COLUMN auth_governance.membership.principal_id IS '成员对应全局主体';
COMMENT ON COLUMN auth_governance.membership.member_kind IS '员工或受邀合作方/访客类型';
COMMENT ON COLUMN auth_governance.membership.status IS '当前成员状态，暂停与离开不同';
COMMENT ON COLUMN auth_governance.membership.generation IS '重新加入递增代际，旧授权不得复活';
COMMENT ON COLUMN auth_governance.membership.version IS '生命周期条件更新版本';
COMMENT ON COLUMN auth_governance.membership.valid_from IS '成员有效起始时刻，包含';
COMMENT ON COLUMN auth_governance.membership.valid_to IS '成员有效终止时刻，不包含，空表示无指定终止';
COMMENT ON COLUMN auth_governance.membership.sponsor_membership_id IS '受邀成员的同企业负责人成员标识';
COMMENT ON COLUMN auth_governance.membership.created_at IS '成员记录创建时刻';
COMMENT ON COLUMN auth_governance.membership.updated_at IS '最后生命周期变更时刻';
CREATE INDEX membership_principal_idx ON auth_governance.membership(principal_id, tenant_id);

CREATE TABLE auth_governance.legacy_identity_binding (
    source_system varchar(100) NOT NULL,
    source_tenant_ref varchar(160) NOT NULL,
    source_subject_ref varchar(160) NOT NULL,
    principal_id varchar(36) NOT NULL,
    membership_id varchar(36) NOT NULL,
    tenant_id varchar(36) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (source_system, source_tenant_ref, source_subject_ref),
    FOREIGN KEY (membership_id, principal_id, tenant_id)
        REFERENCES auth_governance.membership(id, principal_id, tenant_id)
);
COMMENT ON TABLE auth_governance.legacy_identity_binding IS '旧身份显式映射，保留旧系统主键和冲突边界';
COMMENT ON COLUMN auth_governance.legacy_identity_binding.source_system IS '旧身份来源系统与环境受控标识';
COMMENT ON COLUMN auth_governance.legacy_identity_binding.source_tenant_ref IS '旧企业主键引用';
COMMENT ON COLUMN auth_governance.legacy_identity_binding.source_subject_ref IS '旧人员/登录主键引用';
COMMENT ON COLUMN auth_governance.legacy_identity_binding.principal_id IS '对应全局主体';
COMMENT ON COLUMN auth_governance.legacy_identity_binding.membership_id IS '对应成员关系';
COMMENT ON COLUMN auth_governance.legacy_identity_binding.tenant_id IS '对应治理企业，用 FK 防跨企业绑定';
COMMENT ON COLUMN auth_governance.legacy_identity_binding.created_at IS '映射创建时刻';

CREATE TABLE auth_governance.command_record (
    operator_ref varchar(160) NOT NULL,
    tenant_id varchar(36) NOT NULL,
    operation varchar(64) NOT NULL,
    command_id varchar(36) NOT NULL,
    payload_hash char(64) NOT NULL,
    result_ref varchar(36),
    completed boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (operator_ref, tenant_id, operation, command_id),
    CHECK ((completed AND result_ref IS NOT NULL) OR (NOT completed AND result_ref IS NULL))
);
COMMENT ON TABLE auth_governance.command_record IS '与业务同事务的命令去重，未完成占位不得独立提交';
COMMENT ON COLUMN auth_governance.command_record.operator_ref IS '受控操作身份，离线运维或已验证主体';
COMMENT ON COLUMN auth_governance.command_record.tenant_id IS '命令目标企业，创建企业时允许尚未存在';
COMMENT ON COLUMN auth_governance.command_record.operation IS '有界操作类型';
COMMENT ON COLUMN auth_governance.command_record.command_id IS '调用方稳定 UUID 幂等标识';
COMMENT ON COLUMN auth_governance.command_record.payload_hash IS '规范输入 SHA-256，异内容重用命令拒绝';
COMMENT ON COLUMN auth_governance.command_record.result_ref IS '首次完成结果对象，重放保留同一引用';
COMMENT ON COLUMN auth_governance.command_record.completed IS '事务内完成标记';
COMMENT ON COLUMN auth_governance.command_record.created_at IS '首次接受命令时刻';

CREATE TABLE auth_governance.audit_event (
    id varchar(36) PRIMARY KEY,
    operator_ref varchar(160) NOT NULL,
    tenant_id varchar(36) NOT NULL,
    operation varchar(64) NOT NULL,
    target_id varchar(36) NOT NULL,
    target_version bigint NOT NULL CHECK (target_version >= 1),
    command_id varchar(36) NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE auth_governance.audit_event IS '治理追加审计，与业务原子提交，不保存凭据';
COMMENT ON COLUMN auth_governance.audit_event.id IS '审计事件 UUID';
COMMENT ON COLUMN auth_governance.audit_event.operator_ref IS '受控操作身份';
COMMENT ON COLUMN auth_governance.audit_event.tenant_id IS '目标企业';
COMMENT ON COLUMN auth_governance.audit_event.operation IS '治理操作类型';
COMMENT ON COLUMN auth_governance.audit_event.target_id IS '被操作对象引用';
COMMENT ON COLUMN auth_governance.audit_event.target_version IS '命令提交后的对象版本';
COMMENT ON COLUMN auth_governance.audit_event.command_id IS '关联原始命令';
COMMENT ON COLUMN auth_governance.audit_event.occurred_at IS '审计随事务提交的数据库时刻';
CREATE INDEX audit_event_tenant_time_idx ON auth_governance.audit_event(tenant_id, occurred_at, id);
