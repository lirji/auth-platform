-- 有界角色引用页直接按完整分区、不可变角色与UUID访问，不扫描其他成员来源。
CREATE INDEX access_grant_role_preview_idx ON auth_governance.access_grant(tenant_id,application_id,environment,role_id,id);
COMMENT ON INDEX auth_governance.access_grant_role_preview_idx IS '角色迁移只读引用页：完整分区与旧角色下按UUID稳定分页';
