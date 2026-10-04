-- 旧委派／策略没有通用连续行版本；独立退出审计保存真实摘要，不伪造版本。
CREATE TABLE auth_governance.retirement_reference_exit (
 tenant_id varchar(36) NOT NULL,
 application_id varchar(100) NOT NULL,
 environment varchar(40) NOT NULL,
 kind varchar(16) NOT NULL CHECK(kind IN ('DELEGATION','POLICY')),
 target_id varchar(36) NOT NULL,
 command_id varchar(36) NOT NULL,
 operator_ref varchar(100) NOT NULL,
 reason varchar(500) NOT NULL CHECK(length(btrim(reason))>0),
 before_hash varchar(64) NOT NULL CHECK(length(before_hash)=64),
 after_hash varchar(64) NOT NULL CHECK(length(after_hash)=64 AND after_hash<>before_hash),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,operator_ref,command_id),
 FOREIGN KEY(tenant_id,application_id,environment) REFERENCES auth_governance.tenant_application(tenant_id,application_id,environment)
);
COMMENT ON TABLE auth_governance.retirement_reference_exit IS '受控运维单向退出委派或策略；原行摘要、原因与幂等命令同事务审计';
COMMENT ON COLUMN auth_governance.retirement_reference_exit.tenant_id IS '固定企业范围';
COMMENT ON COLUMN auth_governance.retirement_reference_exit.application_id IS '固定应用范围';
COMMENT ON COLUMN auth_governance.retirement_reference_exit.environment IS '固定环境范围';
COMMENT ON COLUMN auth_governance.retirement_reference_exit.kind IS 'DELEGATION管理委派或POLICY申请策略';
COMMENT ON COLUMN auth_governance.retirement_reference_exit.target_id IS '原委派成员UUID或策略UUID';
COMMENT ON COLUMN auth_governance.retirement_reference_exit.command_id IS '原受控运维幂等命令UUID';
COMMENT ON COLUMN auth_governance.retirement_reference_exit.operator_ref IS '来自0600受控配置的操作人';
COMMENT ON COLUMN auth_governance.retirement_reference_exit.reason IS '真实单向退出原因';
COMMENT ON COLUMN auth_governance.retirement_reference_exit.before_hash IS '退出前完整原行摘要，不冒充连续版本';
COMMENT ON COLUMN auth_governance.retirement_reference_exit.after_hash IS '退出后真实完整行摘要';
COMMENT ON COLUMN auth_governance.retirement_reference_exit.created_at IS '原提交UTC时间';
CREATE TRIGGER immutable_retirement_exit BEFORE UPDATE OR DELETE ON auth_governance.retirement_reference_exit FOR EACH ROW EXECUTE FUNCTION auth_governance.reject_role_mutation();
