-- 扩展式迁移；旧读取只识别TENANT_ALL，不能把新细范围当成全范围。
ALTER TABLE auth_governance.access_grant DROP CONSTRAINT access_grant_scope_check;
ALTER TABLE auth_governance.access_grant ADD CONSTRAINT access_grant_scope_check CHECK(scope IN ('TENANT_ALL','SCOPED'));
ALTER TABLE auth_governance.access_grant ADD CONSTRAINT access_grant_partition_key UNIQUE(id,tenant_id,application_id,environment);
COMMENT ON COLUMN auth_governance.access_grant.scope IS 'TENANT_ALL为旧协议；SCOPED必须读取固定grant_scope，不允许全范围回退';

CREATE TABLE auth_governance.grant_scope (
    grant_id varchar(36) PRIMARY KEY,
    tenant_id varchar(36) NOT NULL,
    application_id varchar(100) NOT NULL,
    environment varchar(40) NOT NULL,
    scope_version bigint NOT NULL CHECK(scope_version=1),
    resource_type varchar(100) NOT NULL,
    rule_json text NOT NULL CHECK(octet_length(rule_json)<=65536 AND jsonb_typeof(rule_json::jsonb)='object'),
    content_hash varchar(64) NOT NULL CHECK(length(content_hash)=64),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(grant_id,tenant_id,application_id,environment)
        REFERENCES auth_governance.access_grant(id,tenant_id,application_id,environment)
);
COMMENT ON TABLE auth_governance.grant_scope IS '完整Grant绑定的不可变有限范围快照，不保存SQL或可执行脚本';
COMMENT ON COLUMN auth_governance.grant_scope.grant_id IS '所属完整授权路径';
COMMENT ON COLUMN auth_governance.grant_scope.tenant_id IS '授权企业，复合外键防跨租户引用';
COMMENT ON COLUMN auth_governance.grant_scope.application_id IS '资源Owner应用';
COMMENT ON COLUMN auth_governance.grant_scope.environment IS '资源隔离环境';
COMMENT ON COLUMN auth_governance.grant_scope.scope_version IS '固定范围协议版本';
COMMENT ON COLUMN auth_governance.grant_scope.resource_type IS '显式业务资源类型';
COMMENT ON COLUMN auth_governance.grant_scope.rule_json IS '规范化受控条件，路径内部AND';
COMMENT ON COLUMN auth_governance.grant_scope.content_hash IS '范围内容摘要用于审计和幂等追溯';
COMMENT ON COLUMN auth_governance.grant_scope.created_at IS '创建UTC时刻，后续禁止修改';
CREATE TRIGGER immutable_scope BEFORE UPDATE OR DELETE ON auth_governance.grant_scope
    FOR EACH ROW EXECUTE FUNCTION auth_governance.reject_role_mutation();

-- 延迟约束允许同一事务先插Grant再插范围，但禁止提交半条路径。
CREATE FUNCTION auth_governance.require_grant_scope() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.scope='SCOPED' AND NOT EXISTS(SELECT 1 FROM auth_governance.grant_scope WHERE grant_id=NEW.id) THEN
        RAISE EXCEPTION 'scoped grant requires immutable rule';
    END IF;
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER scoped_grant_complete AFTER INSERT OR UPDATE ON auth_governance.access_grant
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION auth_governance.require_grant_scope();
