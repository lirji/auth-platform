CREATE TABLE auth_governance.portal_diagnostic_audit (
    id varchar(36) PRIMARY KEY,
    tenant_id varchar(36) NOT NULL,
    application_id varchar(100) NOT NULL,
    environment varchar(40) NOT NULL,
    operator_ref varchar(160) NOT NULL,
    operation varchar(64) NOT NULL,
    target_id varchar(36),
    outcome varchar(16) NOT NULL CHECK (outcome IN ('ALLOWED','DENIED')),
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
COMMENT ON TABLE auth_governance.portal_diagnostic_audit IS '门户诊断访问追加审计；拒绝请求记录所请求范围，不表示具有该范围权限';
COMMENT ON COLUMN auth_governance.portal_diagnostic_audit.id IS '审计事件UUID';
COMMENT ON COLUMN auth_governance.portal_diagnostic_audit.tenant_id IS '请求诊断的租户UUID，不作为授权依据';
COMMENT ON COLUMN auth_governance.portal_diagnostic_audit.application_id IS '请求诊断的应用稳定编码';
COMMENT ON COLUMN auth_governance.portal_diagnostic_audit.environment IS '请求诊断的环境编码';
COMMENT ON COLUMN auth_governance.portal_diagnostic_audit.operator_ref IS '已验证登录对应主体ID，不含Token或身份凭据';
COMMENT ON COLUMN auth_governance.portal_diagnostic_audit.operation IS '稳定诊断操作编码';
COMMENT ON COLUMN auth_governance.portal_diagnostic_audit.target_id IS '被请求Grant UUID，查询审计页时为空';
COMMENT ON COLUMN auth_governance.portal_diagnostic_audit.outcome IS '访问结果ALLOWED或DENIED，不是业务资源授权结果';
COMMENT ON COLUMN auth_governance.portal_diagnostic_audit.occurred_at IS '数据库记录访问的UTC时刻';
CREATE INDEX portal_diagnostic_partition_id_idx ON auth_governance.portal_diagnostic_audit(tenant_id,application_id,environment,id);
CREATE INDEX audit_event_tenant_id_idx ON auth_governance.audit_event(tenant_id,id);
