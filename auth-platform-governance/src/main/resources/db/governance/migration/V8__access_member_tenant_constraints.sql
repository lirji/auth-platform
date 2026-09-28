-- 即使未来出现新写入入口，数据库仍拒绝把其他企业的成员写入当前授权分区。
ALTER TABLE auth_governance.access_delegation ADD CONSTRAINT access_delegation_member_tenant_fk
    FOREIGN KEY(membership_id,tenant_id) REFERENCES auth_governance.membership(id,tenant_id);
COMMENT ON CONSTRAINT access_delegation_member_tenant_fk ON auth_governance.access_delegation IS '管理委派与成员企业一致';
ALTER TABLE auth_governance.access_grant ADD CONSTRAINT access_grant_member_tenant_fk
    FOREIGN KEY(membership_id,tenant_id) REFERENCES auth_governance.membership(id,tenant_id);
COMMENT ON CONSTRAINT access_grant_member_tenant_fk ON auth_governance.access_grant IS '业务授权与成员企业一致';
