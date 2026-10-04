-- V29已在隔离目标执行；追加补强原事实与回执分区，禁止借其他分区的真实回执授新。
CREATE OR REPLACE FUNCTION auth_governance.guard_role_migration_source() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.source_id LIKE 'role-migration:%' AND NOT EXISTS(
  SELECT 1 FROM auth_governance.role_migration_item i
  JOIN auth_governance.role_migration_task t ON t.id=i.task_id
  JOIN auth_governance.access_grant old_grant ON old_grant.id=i.old_grant_id
  LEFT JOIN auth_governance.grant_scope original_scope ON original_scope.grant_id=old_grant.id
  JOIN auth_governance.projection_grant_receipt r ON r.grant_id=old_grant.id AND r.grant_version=old_grant.version
  JOIN auth_governance.projection_operation o ON o.id=r.operation_id AND o.state='APPLIED'
  JOIN auth_governance.projection_receipt proof ON proof.operation_id=o.id
  JOIN auth_governance.policy_partition p ON p.id=o.fence_id
  JOIN auth_governance.directory_fence d ON d.tenant_id=i.tenant_id
  WHERE t.state='RUNNING' AND i.stage='READY_TO_GRANT' AND i.revoke_receipt_id=r.operation_id
   AND old_grant.state='REVOKED' AND old_grant.version=i.old_version+1
   AND old_grant.tenant_id=i.tenant_id AND old_grant.application_id=i.application_id AND old_grant.environment=i.environment
   AND old_grant.role_id=t.old_role_id AND old_grant.source_type=i.source_type AND old_grant.source_id=i.old_source_id
   AND old_grant.membership_id IS NOT DISTINCT FROM i.member_id AND old_grant.generation=i.generation
   AND old_grant.group_id IS NOT DISTINCT FROM i.group_id AND old_grant.scope=i.scope
   AND old_grant.valid_from=i.valid_from AND old_grant.valid_to=i.valid_to
   AND original_scope.rule_json IS NOT DISTINCT FROM i.rule_json AND original_scope.content_hash IS NOT DISTINCT FROM i.scope_hash
   AND i.planned_grant_id=NEW.id AND i.new_source_id=NEW.source_id AND i.tenant_id=NEW.tenant_id
   AND i.application_id=NEW.application_id AND i.environment=NEW.environment
   AND i.member_id IS NOT DISTINCT FROM NEW.membership_id AND i.generation=NEW.generation
   AND i.group_id IS NOT DISTINCT FROM NEW.group_id AND i.source_type=NEW.source_type
   AND i.new_role_id=NEW.role_id AND i.scope=NEW.scope AND NEW.valid_to=i.valid_to AND NEW.valid_from>=i.valid_from
   AND NEW.valid_from>=clock_timestamp()-interval '5 seconds' AND NEW.state='PENDING' AND NEW.version=1
   AND p.tenant_id=i.tenant_id AND p.application_id=i.application_id AND p.environment=i.environment
   AND p.state='READY' AND p.desired_epoch=p.applied_epoch AND p.applied_epoch>=o.target_epoch
   AND d.state='READY' AND d.desired_epoch=d.applied_epoch) THEN
  RAISE EXCEPTION 'reserved migration lineage';
 END IF;
 RETURN NEW;
END;
$$;

