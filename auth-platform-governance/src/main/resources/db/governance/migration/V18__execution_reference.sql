CREATE TABLE auth_governance.execution_reference (
 id varchar(36) PRIMARY KEY,
 caller varchar(100) NOT NULL,
 application varchar(100) NOT NULL,
 environment varchar(40) NOT NULL,
 member varchar(36) NOT NULL REFERENCES auth_governance.membership(id),
 request_id varchar(36) NOT NULL,
 fingerprint varchar(64) NOT NULL,
 context_json text NOT NULL CHECK (octet_length(context_json) <= 4096),
 capability varchar(100) NOT NULL,
 resource_type varchar(100) NOT NULL,
 paths_json text NOT NULL CHECK (octet_length(paths_json) <= 262144),
 expires_at timestamptz NOT NULL,
 UNIQUE(caller, request_id)
);
COMMENT ON TABLE auth_governance.execution_reference IS '不含Token的不可变执行引用及签发记录；每次使用重新判权，期限不代表授权有效期';
COMMENT ON COLUMN auth_governance.execution_reference.id IS '服务端随机执行引用UUID';
COMMENT ON COLUMN auth_governance.execution_reference.caller IS '签发调用服务，检查必须保持同一服务';
COMMENT ON COLUMN auth_governance.execution_reference.application IS '凭据固定应用';
COMMENT ON COLUMN auth_governance.execution_reference.environment IS '凭据固定环境';
COMMENT ON COLUMN auth_governance.execution_reference.member IS '签发时已验证成员UUID';
COMMENT ON COLUMN auth_governance.execution_reference.request_id IS '调用服务内签发幂等UUID';
COMMENT ON COLUMN auth_governance.execution_reference.fingerprint IS '身份版本、能力和期限的命令摘要';
COMMENT ON COLUMN auth_governance.execution_reference.context_json IS '已验证身份版本及代际，不含登录凭据';
COMMENT ON COLUMN auth_governance.execution_reference.capability IS '唯一签发能力编码';
COMMENT ON COLUMN auth_governance.execution_reference.resource_type IS '固定资源类型';
COMMENT ON COLUMN auth_governance.execution_reference.paths_json IS '签发时同Grant完整范围路径，后续新授权不能扩张引用';
COMMENT ON COLUMN auth_governance.execution_reference.expires_at IS '执行引用到期时刻，过期永久拒绝';
CREATE INDEX execution_member_expiry_idx ON auth_governance.execution_reference(member,expires_at);
