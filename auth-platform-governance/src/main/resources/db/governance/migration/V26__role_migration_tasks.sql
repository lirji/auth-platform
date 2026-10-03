-- 追加式迁移；旧授权和历史命令不回填、不删除，保留来源前缀仅约束新写入。
CREATE TABLE auth_governance.role_migration_task (
 id varchar(36) PRIMARY KEY,
 tenant_id varchar(36) NOT NULL,
 application_id varchar(100) NOT NULL,
 environment varchar(40) NOT NULL,
 old_role_id varchar(36) NOT NULL,
 new_role_id varchar(36) NOT NULL,
 state varchar(32) NOT NULL CHECK(state IN ('RUNNING','COMPLETED','FINISHED_WITH_FAILURES','CANCELLED')),
 version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 created_by varchar(36) NOT NULL REFERENCES auth_governance.membership(id),
 created_generation bigint NOT NULL CHECK(created_generation>0),
 command_id varchar(36) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(id,tenant_id,application_id,environment,new_role_id),
 FOREIGN KEY(old_role_id,tenant_id,application_id,environment) REFERENCES auth_governance.role_version(id,tenant_id,application_id,environment),
 FOREIGN KEY(new_role_id,tenant_id,application_id,environment) REFERENCES auth_governance.role_version(id,tenant_id,application_id,environment),
 CHECK(old_role_id<>new_role_id)
);
COMMENT ON TABLE auth_governance.role_migration_task IS '显式直接授权迁移任务；创建不撤权，取消不回滚授权效果';
COMMENT ON COLUMN auth_governance.role_migration_task.id IS '迁移任务标识';
COMMENT ON COLUMN auth_governance.role_migration_task.tenant_id IS '获授权企业';
COMMENT ON COLUMN auth_governance.role_migration_task.application_id IS '应用隔离分区';
COMMENT ON COLUMN auth_governance.role_migration_task.environment IS '环境隔离分区';
COMMENT ON COLUMN auth_governance.role_migration_task.old_role_id IS '原不可变角色版本';
COMMENT ON COLUMN auth_governance.role_migration_task.new_role_id IS '目标不可变角色版本';
COMMENT ON COLUMN auth_governance.role_migration_task.state IS '固定集合的当前聚合状态';
COMMENT ON COLUMN auth_governance.role_migration_task.version IS '任务乐观版本';
COMMENT ON COLUMN auth_governance.role_migration_task.created_by IS '来自当前登录的创建管理员';
COMMENT ON COLUMN auth_governance.role_migration_task.created_generation IS '创建管理员成员代际';
COMMENT ON COLUMN auth_governance.role_migration_task.command_id IS '原创建幂等命令';
COMMENT ON COLUMN auth_governance.role_migration_task.created_at IS '创建UTC时刻';
COMMENT ON COLUMN auth_governance.role_migration_task.updated_at IS '最近检查点UTC时刻';

CREATE TABLE auth_governance.role_migration_item (
 id varchar(36) PRIMARY KEY,
 task_id varchar(36) NOT NULL,
 tenant_id varchar(36) NOT NULL,
 application_id varchar(100) NOT NULL,
 environment varchar(40) NOT NULL,
 old_grant_id varchar(36) NOT NULL,
 old_version bigint NOT NULL CHECK(old_version>0),
 new_role_id varchar(36) NOT NULL,
 member_id varchar(36) NOT NULL REFERENCES auth_governance.membership(id),
 generation bigint NOT NULL CHECK(generation>0),
 old_source_id varchar(100) NOT NULL,
 scope varchar(32) NOT NULL CHECK(scope IN ('TENANT_ALL','SCOPED')),
 rule_json text CHECK(octet_length(rule_json)<=65536 AND jsonb_typeof(rule_json::jsonb)='object'),
 scope_hash varchar(64) CHECK(length(scope_hash)=64),
 valid_from timestamptz NOT NULL,
 valid_to timestamptz NOT NULL,
 original_hash varchar(64) NOT NULL CHECK(length(original_hash)=64),
 new_source_id varchar(100) NOT NULL,
 planned_grant_id varchar(36) NOT NULL UNIQUE,
 revoke_command varchar(36) NOT NULL UNIQUE,
 grant_command varchar(36) NOT NULL UNIQUE,
 stage varchar(32) NOT NULL CHECK(stage IN ('READY_TO_REVOKE','WAIT_REVOKE_CONFIRM','READY_TO_GRANT','WAIT_NEW_CONFIRM','COMPLETED','FAILED','CANCELLED')),
 version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 reason varchar(64),
 revoke_receipt_id varchar(36) REFERENCES auth_governance.projection_operation(id),
 new_grant_id varchar(36),
 new_receipt_id varchar(36) REFERENCES auth_governance.projection_operation(id),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(old_grant_id,new_role_id),
 FOREIGN KEY(task_id,tenant_id,application_id,environment,new_role_id) REFERENCES auth_governance.role_migration_task(id,tenant_id,application_id,environment,new_role_id),
 FOREIGN KEY(old_grant_id,tenant_id,application_id,environment) REFERENCES auth_governance.access_grant(id,tenant_id,application_id,environment),
 FOREIGN KEY(new_grant_id,tenant_id,application_id,environment) REFERENCES auth_governance.access_grant(id,tenant_id,application_id,environment),
 CHECK(new_source_id='role-migration:'||old_grant_id||':'||new_role_id),
 CHECK((scope='TENANT_ALL' AND rule_json IS NULL AND scope_hash IS NULL) OR (scope='SCOPED' AND rule_json IS NOT NULL AND scope_hash IS NOT NULL)),
 CHECK(valid_to>valid_from),
 CHECK(new_grant_id IS NULL OR new_grant_id=planned_grant_id),
 CHECK(stage NOT IN ('READY_TO_GRANT','WAIT_NEW_CONFIRM','COMPLETED') OR revoke_receipt_id IS NOT NULL),
 CHECK(stage NOT IN ('WAIT_NEW_CONFIRM','COMPLETED') OR new_grant_id IS NOT NULL),
 CHECK(stage<>'COMPLETED' OR new_receipt_id IS NOT NULL)
);
COMMENT ON TABLE auth_governance.role_migration_item IS '固定来源谱系与可恢复检查点；先确认撤旧再授新，终态不能复活';
COMMENT ON COLUMN auth_governance.role_migration_item.id IS '迁移子项标识';
COMMENT ON COLUMN auth_governance.role_migration_item.task_id IS '所属固定任务';
COMMENT ON COLUMN auth_governance.role_migration_item.tenant_id IS '原授权企业';
COMMENT ON COLUMN auth_governance.role_migration_item.application_id IS '原授权应用';
COMMENT ON COLUMN auth_governance.role_migration_item.environment IS '原授权环境';
COMMENT ON COLUMN auth_governance.role_migration_item.old_grant_id IS '原独立授权来源';
COMMENT ON COLUMN auth_governance.role_migration_item.old_version IS '撤销前固定原版本';
COMMENT ON COLUMN auth_governance.role_migration_item.new_role_id IS '固定目标角色版本';
COMMENT ON COLUMN auth_governance.role_migration_item.member_id IS '固定目标成员';
COMMENT ON COLUMN auth_governance.role_migration_item.generation IS '固定目标成员代际';
COMMENT ON COLUMN auth_governance.role_migration_item.old_source_id IS '原直接来源保留标识';
COMMENT ON COLUMN auth_governance.role_migration_item.scope IS '固定原范围种类';
COMMENT ON COLUMN auth_governance.role_migration_item.rule_json IS '原不可变范围JSON字节';
COMMENT ON COLUMN auth_governance.role_migration_item.scope_hash IS '原范围SHA256';
COMMENT ON COLUMN auth_governance.role_migration_item.valid_from IS '原UTC生效开始';
COMMENT ON COLUMN auth_governance.role_migration_item.valid_to IS '原排他截止，禁止续期';
COMMENT ON COLUMN auth_governance.role_migration_item.original_hash IS '原业务字段与范围摘要，检测外部变更';
COMMENT ON COLUMN auth_governance.role_migration_item.new_source_id IS '原Grant到目标角色的唯一迁移谱系';
COMMENT ON COLUMN auth_governance.role_migration_item.planned_grant_id IS '预分配新Grant，重试不会换标识';
COMMENT ON COLUMN auth_governance.role_migration_item.revoke_command IS '原撤旧执行幂等命令';
COMMENT ON COLUMN auth_governance.role_migration_item.grant_command IS '原授新执行幂等命令';
COMMENT ON COLUMN auth_governance.role_migration_item.stage IS '受约束的迁移阶段';
COMMENT ON COLUMN auth_governance.role_migration_item.version IS '子项检查点乐观版本';
COMMENT ON COLUMN auth_governance.role_migration_item.reason IS '脱敏等待或失败原因';
COMMENT ON COLUMN auth_governance.role_migration_item.revoke_receipt_id IS '真实原撤权操作回执';
COMMENT ON COLUMN auth_governance.role_migration_item.new_grant_id IS '同事务提交的新Grant，取消也保留';
COMMENT ON COLUMN auth_governance.role_migration_item.new_receipt_id IS '真实新授权操作回执';
COMMENT ON COLUMN auth_governance.role_migration_item.updated_at IS '最近子项检查点UTC时刻';
CREATE INDEX role_migration_task_partition_idx ON auth_governance.role_migration_task(tenant_id,application_id,environment,old_role_id,id);
COMMENT ON INDEX auth_governance.role_migration_task_partition_idx IS '角色行恢复任务的稳定游标索引';
CREATE INDEX role_migration_item_task_idx ON auth_governance.role_migration_item(task_id,id);
COMMENT ON INDEX auth_governance.role_migration_item_task_idx IS '有界任务子项读取与聚合索引';

-- 固定业务计划不可变；检查点仅按合法状态和连续版本推进。
CREATE FUNCTION auth_governance.guard_role_migration_item() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (to_jsonb(NEW)-ARRAY['stage','version','reason','revoke_receipt_id','new_grant_id','new_receipt_id','updated_at']) IS DISTINCT FROM
    (to_jsonb(OLD)-ARRAY['stage','version','reason','revoke_receipt_id','new_grant_id','new_receipt_id','updated_at'])
    OR NEW.version<>OLD.version+1 OR OLD.stage IN ('COMPLETED','FAILED','CANCELLED') THEN
  RAISE EXCEPTION 'immutable migration plan or terminal checkpoint';
 END IF;
 IF NOT (NEW.stage IN ('FAILED','CANCELLED') OR NEW.stage=OLD.stage
   OR (OLD.stage='READY_TO_REVOKE' AND NEW.stage='WAIT_REVOKE_CONFIRM')
   OR (OLD.stage='WAIT_REVOKE_CONFIRM' AND NEW.stage='READY_TO_GRANT')
   OR (OLD.stage='READY_TO_GRANT' AND NEW.stage='WAIT_NEW_CONFIRM')
   OR (OLD.stage='WAIT_NEW_CONFIRM' AND NEW.stage='COMPLETED')) THEN
  RAISE EXCEPTION 'illegal migration transition';
 END IF;
 IF (OLD.new_grant_id IS NOT NULL AND NEW.new_grant_id IS DISTINCT FROM OLD.new_grant_id)
   OR (OLD.revoke_receipt_id IS NOT NULL AND NEW.revoke_receipt_id IS DISTINCT FROM OLD.revoke_receipt_id)
   OR (OLD.new_receipt_id IS NOT NULL AND NEW.new_receipt_id IS DISTINCT FROM OLD.new_receipt_id) THEN
  RAISE EXCEPTION 'immutable migration effect reference';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER role_migration_item_guard BEFORE UPDATE ON auth_governance.role_migration_item
 FOR EACH ROW EXECUTE FUNCTION auth_governance.guard_role_migration_item();
CREATE FUNCTION auth_governance.guard_role_migration_task() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (to_jsonb(NEW)-ARRAY['state','version','updated_at']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['state','version','updated_at'])
   OR NEW.version<>OLD.version+1 OR OLD.state<>'RUNNING' THEN RAISE EXCEPTION 'immutable migration task'; END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER role_migration_task_guard BEFORE UPDATE ON auth_governance.role_migration_task
 FOR EACH ROW EXECUTE FUNCTION auth_governance.guard_role_migration_task();

-- 旧写节点也不能伪造保留来源；只有当前固定子项预分配UUID可提交新Grant。
CREATE FUNCTION auth_governance.guard_role_migration_source() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.source_id LIKE 'role-migration:%' AND NOT EXISTS(
  SELECT 1 FROM auth_governance.role_migration_item i JOIN auth_governance.role_migration_task t ON t.id=i.task_id
  WHERE t.state='RUNNING' AND i.stage='READY_TO_GRANT' AND i.revoke_receipt_id IS NOT NULL
   AND i.planned_grant_id=NEW.id AND i.new_source_id=NEW.source_id AND i.tenant_id=NEW.tenant_id
   AND i.application_id=NEW.application_id AND i.environment=NEW.environment AND i.member_id=NEW.membership_id
   AND i.generation=NEW.generation AND i.new_role_id=NEW.role_id AND i.scope=NEW.scope
   AND NEW.source_type='DIRECT' AND NEW.valid_to=i.valid_to AND NEW.valid_from>=i.valid_from
   AND NEW.valid_from>=clock_timestamp()-interval '5 seconds' AND NEW.state='PENDING' AND NEW.version=1) THEN
  RAISE EXCEPTION 'reserved migration lineage';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER role_migration_source_guard BEFORE INSERT ON auth_governance.access_grant
 FOR EACH ROW EXECUTE FUNCTION auth_governance.guard_role_migration_source();
