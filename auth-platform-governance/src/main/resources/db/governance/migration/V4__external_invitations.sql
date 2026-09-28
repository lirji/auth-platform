CREATE TABLE auth_governance.invitation (
    id varchar(36) PRIMARY KEY,
    tenant_id varchar(36) NOT NULL REFERENCES auth_governance.tenant(id),
    sponsor_membership_id varchar(36) NOT NULL,
    operator_ref varchar(160) NOT NULL,
    target_issuer varchar(500) NOT NULL,
    target_subject varchar(500) NOT NULL,
    member_kind varchar(16) NOT NULL CHECK (member_kind IN ('PARTNER', 'GUEST')),
    token_hash char(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[a-f0-9]{64}$'),
    expires_at timestamptz NOT NULL,
    membership_valid_to timestamptz NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('PENDING', 'ACCEPTED', 'REVOKED')),
    version bigint NOT NULL CHECK (version >= 1),
    accepted_membership_id varchar(36),
    accepted_generation bigint,
    accepted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (sponsor_membership_id, tenant_id) REFERENCES auth_governance.membership(id, tenant_id),
    FOREIGN KEY (accepted_membership_id, tenant_id) REFERENCES auth_governance.membership(id, tenant_id),
    CHECK (membership_valid_to > expires_at),
    CHECK ((state = 'ACCEPTED' AND accepted_membership_id IS NOT NULL AND accepted_generation IS NOT NULL
        AND accepted_generation >= 1 AND accepted_at IS NOT NULL)
        OR (state <> 'ACCEPTED' AND accepted_membership_id IS NULL AND accepted_generation IS NULL AND accepted_at IS NULL))
);
COMMENT ON TABLE auth_governance.invitation IS '绑定精确身份的外部单次邀请，不授予应用或业务角色';
COMMENT ON COLUMN auth_governance.invitation.id IS '规范 UUID 邀请标识';
COMMENT ON COLUMN auth_governance.invitation.tenant_id IS '邀请目标企业';
COMMENT ON COLUMN auth_governance.invitation.sponsor_membership_id IS '同企业有效员工负责人，接受时再次验证';
COMMENT ON COLUMN auth_governance.invitation.operator_ref IS '受控配置中的邀请操作者';
COMMENT ON COLUMN auth_governance.invitation.target_issuer IS '目标登录身份的精确发行方';
COMMENT ON COLUMN auth_governance.invitation.target_subject IS '目标登录身份的精确 subject，不使用邮箱合并';
COMMENT ON COLUMN auth_governance.invitation.member_kind IS '受邀成员类型，仅 PARTNER 或 GUEST';
COMMENT ON COLUMN auth_governance.invitation.token_hash IS '随机邀请令牌 SHA-256，仅保存摘要';
COMMENT ON COLUMN auth_governance.invitation.expires_at IS '邀请接受截止时间，UTC，不包含此时刻';
COMMENT ON COLUMN auth_governance.invitation.membership_valid_to IS '接受后成员的有限有效期，UTC';
COMMENT ON COLUMN auth_governance.invitation.state IS '邀请状态 PENDING/ACCEPTED/REVOKED';
COMMENT ON COLUMN auth_governance.invitation.version IS '邀请状态条件更新版本';
COMMENT ON COLUMN auth_governance.invitation.accepted_membership_id IS '首次接受结果成员标识';
COMMENT ON COLUMN auth_governance.invitation.accepted_generation IS '首次接受时成员代际，旧邀请不能指向新代';
COMMENT ON COLUMN auth_governance.invitation.accepted_at IS '接受事务的数据库时刻';
COMMENT ON COLUMN auth_governance.invitation.created_at IS '创建事务的数据库时刻';
CREATE INDEX invitation_tenant_state_idx ON auth_governance.invitation(tenant_id, state, expires_at, id);

CREATE TABLE auth_governance.invitation_audit_detail (
    audit_event_id varchar(36) PRIMARY KEY REFERENCES auth_governance.audit_event(id),
    invitation_id varchar(36) NOT NULL REFERENCES auth_governance.invitation(id),
    reason varchar(1000) NOT NULL CHECK (length(btrim(reason)) > 0),
    previous_state varchar(16) NOT NULL CHECK (previous_state IN ('ABSENT', 'PENDING')),
    resulting_state varchar(16) NOT NULL CHECK (resulting_state IN ('PENDING', 'ACCEPTED', 'REVOKED')),
    previous_version bigint NOT NULL CHECK (previous_version >= 0),
    CHECK ((previous_state = 'ABSENT' AND resulting_state = 'PENDING' AND previous_version = 0)
        OR (previous_state = 'PENDING' AND resulting_state IN ('ACCEPTED', 'REVOKED') AND previous_version >= 1))
);
COMMENT ON TABLE auth_governance.invitation_audit_detail IS '邀请专属追加审计明细，与通用审计及邀请同事务';
COMMENT ON COLUMN auth_governance.invitation_audit_detail.audit_event_id IS '通用审计主键，操作者/时间/命令/结果版本由主记录保存';
COMMENT ON COLUMN auth_governance.invitation_audit_detail.invitation_id IS '对应邀请对象';
COMMENT ON COLUMN auth_governance.invitation_audit_detail.reason IS '操作原因，不保存原始令牌或认证凭据';
COMMENT ON COLUMN auth_governance.invitation_audit_detail.previous_state IS '邀请前状态，创建前为 ABSENT';
COMMENT ON COLUMN auth_governance.invitation_audit_detail.resulting_state IS '邀请后状态';
COMMENT ON COLUMN auth_governance.invitation_audit_detail.previous_version IS '邀请前版本，创建前为零';

ALTER TABLE auth_governance.audit_event DROP CONSTRAINT audit_suspension_details_required_ck;
ALTER TABLE auth_governance.audit_event ADD CONSTRAINT audit_suspension_details_required_ck CHECK (
    operation NOT IN ('SUSPEND_MEMBERSHIP', 'SUSPEND_PRINCIPAL', 'LEAVE_EXTERNAL_MEMBER')
    OR (reason IS NOT NULL AND previous_status IS NOT NULL AND resulting_status IS NOT NULL AND previous_version IS NOT NULL)
);
COMMENT ON CONSTRAINT audit_suspension_details_required_ck ON auth_governance.audit_event
    IS '停用与外部退出命令必须保存原因及前后事实，兼容旧审计行';
