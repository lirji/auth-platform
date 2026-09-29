CREATE TABLE auth_governance.request_policy (
    id varchar(36) PRIMARY KEY,
    tenant_id varchar(36) NOT NULL,
    application_id varchar(100) NOT NULL,
    environment varchar(40) NOT NULL,
    role_id varchar(36) NOT NULL,
    scope_json text NOT NULL,
    max_duration_seconds bigint NOT NULL CHECK(max_duration_seconds BETWEEN 1 AND 31536000),
    approver_membership_id varchar(36) NOT NULL,
    approver_generation bigint NOT NULL CHECK(approver_generation>0),
    delegation_hash varchar(64) NOT NULL,
    policy_version bigint NOT NULL CHECK(policy_version>0),
    content_hash varchar(64) NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    created_by varchar(36) NOT NULL REFERENCES auth_governance.membership(id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(id,tenant_id,application_id,environment),
    FOREIGN KEY(role_id,tenant_id,application_id,environment) REFERENCES auth_governance.role_version(id,tenant_id,application_id,environment),
    FOREIGN KEY(approver_membership_id,tenant_id) REFERENCES auth_governance.membership(id,tenant_id)
);
COMMENT ON TABLE auth_governance.request_policy IS '不可变可申请策略；与业务访问权分开';
COMMENT ON COLUMN auth_governance.request_policy.id IS '策略版本标识';
COMMENT ON COLUMN auth_governance.request_policy.tenant_id IS '企业';
COMMENT ON COLUMN auth_governance.request_policy.application_id IS '应用';
COMMENT ON COLUMN auth_governance.request_policy.environment IS '环境';
COMMENT ON COLUMN auth_governance.request_policy.role_id IS '固定角色版本';
COMMENT ON COLUMN auth_governance.request_policy.scope_json IS '固定范围快照';
COMMENT ON COLUMN auth_governance.request_policy.max_duration_seconds IS '最长授权秒数';
COMMENT ON COLUMN auth_governance.request_policy.approver_membership_id IS '指定审批成员';
COMMENT ON COLUMN auth_governance.request_policy.approver_generation IS '审批成员代际';
COMMENT ON COLUMN auth_governance.request_policy.delegation_hash IS '固定管理上限摘要';
COMMENT ON COLUMN auth_governance.request_policy.policy_version IS '审批策略版本';
COMMENT ON COLUMN auth_governance.request_policy.content_hash IS '固定策略摘要';
COMMENT ON COLUMN auth_governance.request_policy.enabled IS '是否允许使用此策略';
COMMENT ON COLUMN auth_governance.request_policy.created_by IS '登记成员';
COMMENT ON COLUMN auth_governance.request_policy.created_at IS '创建UTC时间';
CREATE TABLE auth_governance.access_request (
    id varchar(36) PRIMARY KEY,
    tenant_id varchar(36) NOT NULL,
    application_id varchar(100) NOT NULL,
    environment varchar(40) NOT NULL,
    requester_principal varchar(36) NOT NULL REFERENCES auth_governance.principal(id),
    membership_id varchar(36) NOT NULL,
    generation bigint NOT NULL CHECK(generation>0),
    policy_id varchar(36) NOT NULL,
    role_id varchar(36) NOT NULL,
    capabilities_json text NOT NULL,
    scope_json text NOT NULL,
    policy_hash varchar(64) NOT NULL,
    member_valid_to timestamptz,
    valid_from timestamptz NOT NULL,
    valid_to timestamptz NOT NULL,
    reason varchar(1000) NOT NULL,
    request_version bigint NOT NULL DEFAULT 1 CHECK(request_version=1),
    snapshot_hash varchar(64) NOT NULL,
    state varchar(20) NOT NULL DEFAULT 'SUBMITTED' CHECK(state IN ('SUBMITTED','IN_REVIEW','APPROVED','REJECTED','CANCELLED')),
    state_version bigint NOT NULL DEFAULT 1 CHECK(state_version>0),
    approval_instance_id varchar(100),
    grant_id varchar(36) REFERENCES auth_governance.access_grant(id),
    command_id varchar(36) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(id,tenant_id,application_id,environment),
    FOREIGN KEY(membership_id,tenant_id) REFERENCES auth_governance.membership(id,tenant_id),
    FOREIGN KEY(policy_id,tenant_id,application_id,environment) REFERENCES auth_governance.request_policy(id,tenant_id,application_id,environment),
    FOREIGN KEY(role_id,tenant_id,application_id,environment) REFERENCES auth_governance.role_version(id,tenant_id,application_id,environment),
    CHECK(valid_to>valid_from AND (member_valid_to IS NULL OR valid_to<=member_valid_to)),
    UNIQUE(membership_id,generation,command_id)
);
COMMENT ON TABLE auth_governance.access_request IS '固定申请快照；审批状态不代替授权投影状态';
COMMENT ON COLUMN auth_governance.access_request.id IS '申请标识';
COMMENT ON COLUMN auth_governance.access_request.tenant_id IS '企业';
COMMENT ON COLUMN auth_governance.access_request.application_id IS '应用';
COMMENT ON COLUMN auth_governance.access_request.environment IS '环境';
COMMENT ON COLUMN auth_governance.access_request.requester_principal IS '可信申请主体';
COMMENT ON COLUMN auth_governance.access_request.membership_id IS '受益成员也是申请成员';
COMMENT ON COLUMN auth_governance.access_request.generation IS '固定受益代际';
COMMENT ON COLUMN auth_governance.access_request.policy_id IS '固定申请策略版本';
COMMENT ON COLUMN auth_governance.access_request.role_id IS '固定角色版本';
COMMENT ON COLUMN auth_governance.access_request.capabilities_json IS '固定能力集合';
COMMENT ON COLUMN auth_governance.access_request.scope_json IS '固定范围快照';
COMMENT ON COLUMN auth_governance.access_request.policy_hash IS '固定管理策略摘要';
COMMENT ON COLUMN auth_governance.access_request.member_valid_to IS '申请时成员到期上限';
COMMENT ON COLUMN auth_governance.access_request.valid_from IS '固定生效开始UTC';
COMMENT ON COLUMN auth_governance.access_request.valid_to IS '固定排他截止UTC';
COMMENT ON COLUMN auth_governance.access_request.reason IS '申请理由';
COMMENT ON COLUMN auth_governance.access_request.request_version IS '内容修订；首版改变内容须新申请';
COMMENT ON COLUMN auth_governance.access_request.snapshot_hash IS '申请快照摘要';
COMMENT ON COLUMN auth_governance.access_request.state IS '审批生命周期状态';
COMMENT ON COLUMN auth_governance.access_request.state_version IS '状态CAS版本';
COMMENT ON COLUMN auth_governance.access_request.approval_instance_id IS 'OA持久实例引用';
COMMENT ON COLUMN auth_governance.access_request.grant_id IS '本申请唯一授权路径';
COMMENT ON COLUMN auth_governance.access_request.command_id IS '初始提交幂等键';
COMMENT ON COLUMN auth_governance.access_request.created_at IS '创建UTC时间';
COMMENT ON COLUMN auth_governance.access_request.updated_at IS '最后状态推进UTC时间';
CREATE TABLE auth_governance.request_start_outbox (
    request_id varchar(36) PRIMARY KEY REFERENCES auth_governance.access_request(id),
    state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','RUNNING','DONE','DEAD')),
    attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 5),
    lease_token varchar(36),
    lease_until timestamptz,
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    last_error varchar(80),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
COMMENT ON TABLE auth_governance.request_start_outbox IS '申请与OA启动意图原子持久化；领取后进程外调用';
COMMENT ON COLUMN auth_governance.request_start_outbox.request_id IS '申请及稳定业务幂等键';
COMMENT ON COLUMN auth_governance.request_start_outbox.state IS '独立技术状态';
COMMENT ON COLUMN auth_governance.request_start_outbox.attempts IS '已领取次数上限五';
COMMENT ON COLUMN auth_governance.request_start_outbox.lease_token IS '本次领取防旧执行器令牌';
COMMENT ON COLUMN auth_governance.request_start_outbox.lease_until IS '租约UTC截止';
COMMENT ON COLUMN auth_governance.request_start_outbox.next_attempt_at IS '下次允许尝试时刻';
COMMENT ON COLUMN auth_governance.request_start_outbox.last_error IS '稳定脱敏错误编码';
COMMENT ON COLUMN auth_governance.request_start_outbox.created_at IS '意图创建UTC';
COMMENT ON COLUMN auth_governance.request_start_outbox.updated_at IS '最后尝试UTC';
CREATE INDEX request_policy_partition_idx ON auth_governance.request_policy(tenant_id,application_id,environment,id) WHERE enabled;
CREATE INDEX access_request_owner_idx ON auth_governance.access_request(tenant_id,application_id,environment,membership_id,generation,id);
CREATE INDEX request_start_due_idx ON auth_governance.request_start_outbox(next_attempt_at,request_id) WHERE state IN ('PENDING','RUNNING');
CREATE FUNCTION auth_governance.keep_request_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'request history is immutable'; END IF;
    IF TG_TABLE_NAME='request_policy' THEN
        IF (to_jsonb(NEW)-'enabled') IS DISTINCT FROM (to_jsonb(OLD)-'enabled') THEN
            RAISE EXCEPTION 'request policy is immutable';
        END IF;
    ELSE
        IF (to_jsonb(NEW)-ARRAY['state','state_version','approval_instance_id','grant_id','updated_at']) IS DISTINCT FROM
           (to_jsonb(OLD)-ARRAY['state','state_version','approval_instance_id','grant_id','updated_at']) THEN
            RAISE EXCEPTION 'request snapshot is immutable';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER request_policy_immutable BEFORE UPDATE OR DELETE ON auth_governance.request_policy
    FOR EACH ROW EXECUTE FUNCTION auth_governance.keep_request_snapshot();
CREATE TRIGGER access_request_immutable BEFORE UPDATE OR DELETE ON auth_governance.access_request
    FOR EACH ROW EXECUTE FUNCTION auth_governance.keep_request_snapshot();
