-- OA升级固定新审批与原来源，已执行V26-V30不编辑。
ALTER TABLE auth_governance.access_request
 ADD COLUMN migration_old_grant_id varchar(36),
 ADD COLUMN migration_old_version bigint,
 ADD COLUMN withdrawn boolean NOT NULL DEFAULT false,
 ADD CONSTRAINT request_migration_original_fk FOREIGN KEY(migration_old_grant_id,tenant_id,application_id,environment)
  REFERENCES auth_governance.access_grant(id,tenant_id,application_id,environment),
 ADD CONSTRAINT request_migration_link_shape CHECK(
  (migration_old_grant_id IS NULL AND migration_old_version IS NULL)
  OR (migration_old_grant_id IS NOT NULL AND migration_old_version IS NOT NULL AND migration_old_version>0));
COMMENT ON COLUMN auth_governance.access_request.migration_old_grant_id IS '本人重新审批的固定原OA授权，普通申请为空';
COMMENT ON COLUMN auth_governance.access_request.migration_old_version IS '发起时原OA授权版本，撤旧后只能前进一次';
COMMENT ON COLUMN auth_governance.access_request.withdrawn IS '单向撤回事实，保留APPROVED历史但阻止未授新迁移';
CREATE UNIQUE INDEX request_migration_open_unique ON auth_governance.access_request(migration_old_grant_id,role_id)
 WHERE migration_old_grant_id IS NOT NULL AND NOT withdrawn AND state IN ('SUBMITTED','IN_REVIEW','APPROVED');
COMMENT ON INDEX auth_governance.request_migration_open_unique IS '同原来源和新版角色最多一个当前替代申请';
CREATE INDEX request_migration_original_idx ON auth_governance.access_request(migration_old_grant_id,id)
 WHERE migration_old_grant_id IS NOT NULL AND grant_id IS NULL AND NOT withdrawn;
COMMENT ON INDEX auth_governance.request_migration_original_idx IS '原申请撤回时有界找到尚未授新的关联申请';

ALTER TABLE auth_governance.role_migration_item
 ADD COLUMN replacement_request_id varchar(36),
 ADD CONSTRAINT migration_replacement_request_fk FOREIGN KEY(replacement_request_id,tenant_id,application_id,environment)
  REFERENCES auth_governance.access_request(id,tenant_id,application_id,environment),
 DROP CONSTRAINT role_migration_item_source_type_check,
 DROP CONSTRAINT role_migration_item_recipient_shape,
 DROP CONSTRAINT role_migration_item_check,
 ADD CONSTRAINT migration_source_type CHECK(source_type IN ('DIRECT','GROUP','OA_REQUEST')),
 ADD CONSTRAINT migration_recipient_shape CHECK(
  (source_type IN ('DIRECT','OA_REQUEST') AND member_id IS NOT NULL AND generation>0 AND group_id IS NULL)
  OR (source_type='GROUP' AND member_id IS NULL AND generation=0 AND group_id IS NOT NULL)),
 ADD CONSTRAINT migration_lineage_shape CHECK(
  (source_type IN ('DIRECT','GROUP') AND replacement_request_id IS NULL
   AND new_source_id='role-migration:'||old_grant_id||':'||new_role_id)
  OR (source_type='OA_REQUEST' AND replacement_request_id IS NOT NULL
   AND new_source_id=replacement_request_id||':single:1'));
COMMENT ON COLUMN auth_governance.role_migration_item.replacement_request_id IS '固定新的可信批准申请，OA来源必须有，DIRECT/GROUP为空';

-- 撤回只能前进；快照守卫自动覆盖新关联字段，不能重新指向另一原授权。
CREATE OR REPLACE FUNCTION auth_governance.keep_request_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'request history is immutable'; END IF;
 IF TG_TABLE_NAME='request_policy' THEN
  IF (to_jsonb(NEW)-'enabled') IS DISTINCT FROM (to_jsonb(OLD)-'enabled') THEN RAISE EXCEPTION 'request policy is immutable'; END IF;
 ELSE
  IF (to_jsonb(NEW)-ARRAY['state','state_version','approval_instance_id','grant_id','updated_at','withdrawn']) IS DISTINCT FROM
     (to_jsonb(OLD)-ARRAY['state','state_version','approval_instance_id','grant_id','updated_at','withdrawn'])
   OR (OLD.withdrawn AND NOT NEW.withdrawn) OR (OLD.grant_id IS NOT NULL AND NEW.grant_id IS DISTINCT FROM OLD.grant_id) THEN
   RAISE EXCEPTION 'request snapshot is immutable';
  END IF;
 END IF;
 RETURN NEW;
END;
$$;

-- 只有已验签并实际应用的新版决定可被迁移引用；APPROVED一行不等于可信批准。
CREATE FUNCTION auth_governance.oa_migration_approval_valid(request_id text) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM auth_governance.access_request r
 JOIN auth_governance.request_policy policy ON policy.id=r.policy_id AND policy.tenant_id=r.tenant_id
  AND policy.application_id=r.application_id AND policy.environment=r.environment
 JOIN auth_governance.approval_inbox inbox ON inbox.request_id=r.id AND inbox.tenant_id=r.tenant_id
  AND inbox.application_id=r.application_id AND inbox.environment=r.environment
 WHERE r.id=$1 AND r.migration_old_grant_id IS NOT NULL AND r.state='APPROVED' AND NOT r.withdrawn
  AND r.valid_to>clock_timestamp() AND policy.enabled AND policy.content_hash=r.policy_hash
  AND inbox.producer='oa-platform' AND inbox.status='APPLIED' AND inbox.result='MIGRATION_APPROVED_PENDING_SWITCH'
  AND inbox.payload_json::jsonb->>'outcome'='APPROVED'
  AND inbox.payload_json::jsonb->>'request_id'=r.id
  AND inbox.payload_json::jsonb->>'request_version'=r.request_version::text
  AND inbox.payload_json::jsonb->>'snapshot_hash'=r.snapshot_hash
  AND inbox.payload_json::jsonb->>'approval_instance_id'=r.approval_instance_id
  AND inbox.payload_json::jsonb->>'policy_id'=policy.id
  AND inbox.payload_json::jsonb->>'policy_version'=policy.policy_version::text
  AND inbox.payload_json::jsonb->>'approver_membership_id'=policy.approver_membership_id
  AND inbox.payload_json::jsonb->>'approver_generation'=policy.approver_generation::text);
$$;

CREATE FUNCTION auth_governance.guard_oa_migration_request() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.migration_old_grant_id IS NULL THEN RETURN NEW; END IF;
 IF TG_OP='INSERT' AND (NEW.state<>'SUBMITTED' OR NEW.request_version<>1 OR NEW.state_version<>1
  OR NEW.grant_id IS NOT NULL OR NEW.approval_instance_id IS NOT NULL OR NEW.withdrawn OR NOT EXISTS(
  SELECT 1 FROM auth_governance.access_grant g
  JOIN auth_governance.access_request original ON original.grant_id=g.id AND original.tenant_id=g.tenant_id
   AND original.application_id=g.application_id AND original.environment=g.environment
  JOIN auth_governance.grant_scope scope ON scope.grant_id=g.id
  JOIN auth_governance.role_version old_role ON old_role.id=g.role_id
  JOIN auth_governance.role_version next_role ON next_role.id=NEW.role_id
  JOIN auth_governance.request_policy policy ON policy.id=NEW.policy_id
  WHERE g.id=NEW.migration_old_grant_id AND g.version=NEW.migration_old_version AND g.state='ACTIVE'
   AND g.source_type='OA_REQUEST' AND g.source_id=original.id||':single:'||original.request_version
   AND original.state='APPROVED' AND NOT original.withdrawn
   AND g.tenant_id=NEW.tenant_id AND g.application_id=NEW.application_id AND g.environment=NEW.environment
   AND g.membership_id=NEW.membership_id AND g.generation=NEW.generation
   AND g.scope='SCOPED' AND scope.rule_json=NEW.scope_json AND policy.scope_json=NEW.scope_json
   AND policy.role_id=NEW.role_id AND policy.content_hash=NEW.policy_hash AND policy.enabled
   AND next_role.capabilities_json=NEW.capabilities_json
   AND g.valid_from<=clock_timestamp() AND NEW.valid_from>=g.valid_from AND NEW.valid_to=g.valid_to
   AND NEW.valid_from>=clock_timestamp()-interval '5 seconds' AND NEW.valid_to>clock_timestamp()
   AND old_role.role_code=next_role.role_code AND next_role.version>old_role.version
   AND old_role.capabilities_json::jsonb @> next_role.capabilities_json::jsonb)) THEN
  RAISE EXCEPTION 'invalid fixed OA migration request';
 END IF;
 IF TG_OP='UPDATE' AND NEW.grant_id IS NOT NULL AND NEW.grant_id IS DISTINCT FROM OLD.grant_id AND NOT EXISTS(
  SELECT 1 FROM auth_governance.access_grant g JOIN auth_governance.role_migration_item item ON item.planned_grant_id=g.id
  WHERE g.id=NEW.grant_id AND item.replacement_request_id=NEW.id AND g.source_type='OA_REQUEST'
   AND g.source_id=NEW.id||':single:'||NEW.request_version AND g.membership_id=NEW.membership_id AND g.generation=NEW.generation
   AND g.tenant_id=NEW.tenant_id AND g.application_id=NEW.application_id AND g.environment=NEW.environment
   AND g.role_id=NEW.role_id AND g.valid_to=NEW.valid_to AND NEW.state='APPROVED' AND NOT NEW.withdrawn) THEN
  RAISE EXCEPTION 'invalid migration approval grant binding';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER oa_migration_request_guard BEFORE INSERT OR UPDATE ON auth_governance.access_request
 FOR EACH ROW EXECUTE FUNCTION auth_governance.guard_oa_migration_request();

CREATE OR REPLACE FUNCTION auth_governance.guard_role_migration_plan_insert() RETURNS trigger LANGUAGE plpgsql AS $$
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
   AND old_role.capabilities_json::jsonb @> new_role.capabilities_json::jsonb
   AND (NEW.source_type<>'OA_REQUEST' OR EXISTS(SELECT 1 FROM auth_governance.access_request replacement
    JOIN auth_governance.access_request original ON original.grant_id=g.id
    WHERE replacement.id=NEW.replacement_request_id AND replacement.migration_old_grant_id=g.id
     AND replacement.migration_old_version=g.version AND replacement.role_id=NEW.new_role_id
     AND replacement.membership_id=NEW.member_id AND replacement.generation=NEW.generation
     AND replacement.scope_json=NEW.rule_json AND replacement.valid_to=NEW.valid_to
     AND replacement.grant_id IS NULL AND NOT original.withdrawn
     AND auth_governance.oa_migration_approval_valid(replacement.id)))) THEN
  RAISE EXCEPTION 'invalid fixed migration source plan';
 END IF;
 RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION auth_governance.guard_role_migration_source() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.source_id LIKE 'role-migration:%' OR (NEW.source_type='OA_REQUEST' AND EXISTS(
  SELECT 1 FROM auth_governance.access_request request WHERE request.migration_old_grant_id IS NOT NULL
   AND NEW.source_id=request.id||':single:'||request.request_version))) AND NOT EXISTS(
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
   AND d.state='READY' AND d.desired_epoch=d.applied_epoch
   AND (i.source_type<>'OA_REQUEST' OR EXISTS(SELECT 1 FROM auth_governance.access_request request
    JOIN auth_governance.access_request original ON original.grant_id=i.old_grant_id
    WHERE request.id=i.replacement_request_id AND request.migration_old_grant_id=i.old_grant_id
     AND request.migration_old_version=i.old_version AND request.grant_id IS NULL AND NOT original.withdrawn
     AND request.membership_id=NEW.membership_id AND request.generation=NEW.generation
     AND request.role_id=NEW.role_id AND request.scope_json=i.rule_json AND request.valid_to=NEW.valid_to
     AND auth_governance.oa_migration_approval_valid(request.id)))) THEN
  RAISE EXCEPTION 'reserved migration lineage';
 END IF;
 RETURN NEW;
END;
$$;

-- 所有迁移类型的新范围都固定原字节，不能合法创建源后再扩大范围。
CREATE OR REPLACE FUNCTION auth_governance.guard_role_migration_scope() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (EXISTS(SELECT 1 FROM auth_governance.access_grant g WHERE g.id=NEW.grant_id AND g.source_id LIKE 'role-migration:%')
  OR EXISTS(SELECT 1 FROM auth_governance.role_migration_item i WHERE i.planned_grant_id=NEW.grant_id))
  AND NOT EXISTS(SELECT 1 FROM auth_governance.role_migration_item i
   WHERE i.planned_grant_id=NEW.grant_id AND i.tenant_id=NEW.tenant_id AND i.application_id=NEW.application_id
    AND i.environment=NEW.environment AND i.rule_json=NEW.rule_json AND i.scope_hash=NEW.content_hash) THEN
  RAISE EXCEPTION 'fixed migration scope mismatch';
 END IF;
 RETURN NEW;
END;
$$;
