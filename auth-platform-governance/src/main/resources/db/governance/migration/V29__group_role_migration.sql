-- 已有计划真实类型为DIRECT；仅追加来源形状，不改V26及原业务来源。
ALTER TABLE auth_governance.role_migration_item
 ADD COLUMN source_type varchar(32) NOT NULL DEFAULT 'DIRECT' CHECK(source_type IN ('DIRECT','GROUP')),
 ADD COLUMN group_id varchar(36),
 ALTER COLUMN member_id DROP NOT NULL,
 DROP CONSTRAINT role_migration_item_generation_check,
 ADD CONSTRAINT role_migration_item_recipient_shape CHECK(
  (source_type='DIRECT' AND member_id IS NOT NULL AND generation>0 AND group_id IS NULL)
  OR (source_type='GROUP' AND member_id IS NULL AND generation=0 AND group_id IS NOT NULL)),
 ADD CONSTRAINT role_migration_item_group_fk FOREIGN KEY(group_id,tenant_id)
  REFERENCES auth_governance.directory_group(id,tenant_id);
COMMENT ON COLUMN auth_governance.role_migration_item.source_type IS '固定DIRECT或GROUP来源类型，不能转成个人授权';
COMMENT ON COLUMN auth_governance.role_migration_item.group_id IS '固定原目录组，成员资格始终动态读取';
COMMENT ON COLUMN auth_governance.role_migration_item.member_id IS 'DIRECT固定目标成员；GROUP为空，不展开个人';
COMMENT ON COLUMN auth_governance.role_migration_item.generation IS 'DIRECT固定成员代际；GROUP为0并依赖当前目录资格';
COMMENT ON TABLE auth_governance.role_migration_task IS '显式直接或组授权迁移任务；取消不回滚已提交效果';

-- 旧SQL写节点也不能在创建时调换受益人、原版本、角色或范围。
CREATE FUNCTION auth_governance.guard_role_migration_plan_insert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.stage<>'READY_TO_REVOKE' OR NEW.version<>1 OR NEW.new_grant_id IS NOT NULL
  OR NEW.revoke_receipt_id IS NOT NULL OR NEW.new_receipt_id IS NOT NULL OR NOT EXISTS(
  SELECT 1 FROM auth_governance.access_grant g
  JOIN auth_governance.role_migration_task t ON t.id=NEW.task_id
  JOIN auth_governance.role_version old_role ON old_role.id=t.old_role_id
  JOIN auth_governance.role_version new_role ON new_role.id=t.new_role_id
  LEFT JOIN auth_governance.grant_scope s ON s.grant_id=g.id
  WHERE t.state='RUNNING' AND g.id=NEW.old_grant_id AND g.version=NEW.old_version AND g.state='ACTIVE'
   AND g.role_id=t.old_role_id AND g.tenant_id=NEW.tenant_id AND g.application_id=NEW.application_id
   AND g.environment=NEW.environment AND g.source_type=NEW.source_type
   AND g.membership_id IS NOT DISTINCT FROM NEW.member_id AND g.generation=NEW.generation
   AND g.group_id IS NOT DISTINCT FROM NEW.group_id AND g.source_id=NEW.old_source_id
   AND g.scope=NEW.scope AND g.valid_from=NEW.valid_from AND g.valid_to=NEW.valid_to
   AND s.rule_json IS NOT DISTINCT FROM NEW.rule_json AND s.content_hash IS NOT DISTINCT FROM NEW.scope_hash
   AND old_role.role_code=new_role.role_code AND new_role.version>old_role.version
   AND old_role.capabilities_json::jsonb @> new_role.capabilities_json::jsonb) THEN
  RAISE EXCEPTION 'invalid fixed migration source plan';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER role_migration_plan_insert_guard BEFORE INSERT ON auth_governance.role_migration_item
 FOR EACH ROW EXECUTE FUNCTION auth_governance.guard_role_migration_plan_insert();

-- 授新需同来源形状、真实原撤权证明和固定组；不接受仅填一个操作UUID。
CREATE OR REPLACE FUNCTION auth_governance.guard_role_migration_source() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.source_id LIKE 'role-migration:%' AND NOT EXISTS(
  SELECT 1 FROM auth_governance.role_migration_item i
  JOIN auth_governance.role_migration_task t ON t.id=i.task_id
  JOIN auth_governance.access_grant old_grant ON old_grant.id=i.old_grant_id
  JOIN auth_governance.projection_grant_receipt r ON r.grant_id=old_grant.id AND r.grant_version=old_grant.version
  JOIN auth_governance.projection_operation o ON o.id=r.operation_id AND o.state='APPLIED'
  JOIN auth_governance.projection_receipt proof ON proof.operation_id=o.id
  JOIN auth_governance.policy_partition p ON p.id=o.fence_id
  JOIN auth_governance.directory_fence d ON d.tenant_id=i.tenant_id
  WHERE t.state='RUNNING' AND i.stage='READY_TO_GRANT' AND i.revoke_receipt_id=r.operation_id
   AND old_grant.state='REVOKED' AND old_grant.version=i.old_version+1
   AND i.planned_grant_id=NEW.id AND i.new_source_id=NEW.source_id AND i.tenant_id=NEW.tenant_id
   AND i.application_id=NEW.application_id AND i.environment=NEW.environment
   AND i.member_id IS NOT DISTINCT FROM NEW.membership_id AND i.generation=NEW.generation
   AND i.group_id IS NOT DISTINCT FROM NEW.group_id AND i.source_type=NEW.source_type
   AND i.new_role_id=NEW.role_id AND i.scope=NEW.scope AND NEW.valid_to=i.valid_to AND NEW.valid_from>=i.valid_from
   AND NEW.valid_from>=clock_timestamp()-interval '5 seconds' AND NEW.state='PENDING' AND NEW.version=1
   AND p.state='READY' AND p.desired_epoch=p.applied_epoch AND p.applied_epoch>=o.target_epoch
   AND d.state='READY' AND d.desired_epoch=d.applied_epoch) THEN
  RAISE EXCEPTION 'reserved migration lineage';
 END IF;
 RETURN NEW;
END;
$$;

-- 新Grant范围也必须与原计划逐字节一致，不能在合法来源之后另外扩大范围。
CREATE FUNCTION auth_governance.guard_role_migration_scope() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM auth_governance.access_grant g WHERE g.id=NEW.grant_id AND g.source_id LIKE 'role-migration:%')
  AND NOT EXISTS(SELECT 1 FROM auth_governance.role_migration_item i
   WHERE i.planned_grant_id=NEW.grant_id AND i.tenant_id=NEW.tenant_id AND i.application_id=NEW.application_id
    AND i.environment=NEW.environment AND i.rule_json=NEW.rule_json AND i.scope_hash=NEW.content_hash) THEN
  RAISE EXCEPTION 'fixed migration scope mismatch';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER role_migration_scope_guard BEFORE INSERT ON auth_governance.grant_scope
 FOR EACH ROW EXECUTE FUNCTION auth_governance.guard_role_migration_scope();
