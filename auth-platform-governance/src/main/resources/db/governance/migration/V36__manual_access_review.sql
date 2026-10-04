-- 人工复核仅固定有限来源，取消和KEEP不会恢复Grant。
CREATE TABLE auth_governance.access_review_task (
 id varchar(36) PRIMARY KEY,
 tenant_id varchar(36) NOT NULL,
 application_id varchar(100) NOT NULL,
 environment varchar(40) NOT NULL,
 membership_id varchar(36) NOT NULL,
 target_generation bigint NOT NULL CHECK(target_generation>0),
 original_basis_hash char(64) NOT NULL CHECK(original_basis_hash ~ '^[a-f0-9]{64}$'),
 responsible_membership_id varchar(36) NOT NULL,
 responsible_generation bigint NOT NULL CHECK(responsible_generation>0),
 state varchar(32) NOT NULL CHECK(state IN ('RUNNING','NEEDS_INVESTIGATION','COMPLETED','CANCELLED')),
 version bigint NOT NULL CHECK(version>0),
 reason varchar(500) NOT NULL CHECK(length(trim(reason))>0),
 created_by varchar(36) NOT NULL,
 creator_generation bigint NOT NULL CHECK(creator_generation>0),
 command_id varchar(36) NOT NULL,
 item_count int NOT NULL CHECK(item_count BETWEEN 1 AND 100),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(id,tenant_id,application_id,environment),
 FOREIGN KEY(tenant_id,application_id,environment) REFERENCES auth_governance.tenant_application(tenant_id,application_id,environment),
 FOREIGN KEY(membership_id,tenant_id) REFERENCES auth_governance.membership(id,tenant_id),
 FOREIGN KEY(responsible_membership_id,tenant_id) REFERENCES auth_governance.membership(id,tenant_id),
 FOREIGN KEY(created_by,tenant_id) REFERENCES auth_governance.membership(id,tenant_id)
);
CREATE INDEX access_review_task_page ON auth_governance.access_review_task(tenant_id,application_id,environment,id);
CREATE INDEX access_review_member_page ON auth_governance.access_review_task(tenant_id,application_id,environment,membership_id,id);
CREATE TABLE auth_governance.access_review_item (
 id varchar(36) PRIMARY KEY,
 task_id varchar(36) NOT NULL,
 tenant_id varchar(36) NOT NULL,
 application_id varchar(100) NOT NULL,
 environment varchar(40) NOT NULL,
 grant_id varchar(36) NOT NULL,
 original_version bigint NOT NULL CHECK(original_version>0),
 original_json jsonb NOT NULL CHECK(jsonb_typeof(original_json)='object' AND octet_length(original_json::text)<=65536),
 original_hash char(64) NOT NULL CHECK(original_hash ~ '^[a-f0-9]{64}$'),
 state varchar(32) NOT NULL CHECK(state IN ('PENDING','INVESTIGATE','KEPT','WAIT_REVOKE','REVOKED','CANCELLED')),
 version bigint NOT NULL CHECK(version>0),
 reason varchar(500),
 revoke_command varchar(36) NOT NULL UNIQUE,
 confirmed_operation_id varchar(36),
 UNIQUE(task_id,grant_id),
 FOREIGN KEY(task_id,tenant_id,application_id,environment) REFERENCES auth_governance.access_review_task(id,tenant_id,application_id,environment),
 FOREIGN KEY(grant_id,tenant_id,application_id,environment) REFERENCES auth_governance.access_grant(id,tenant_id,application_id,environment),
 CHECK((state='REVOKED')=(confirmed_operation_id IS NOT NULL)),
 CHECK(state='PENDING' OR (reason IS NOT NULL AND length(trim(reason))>0))
);
CREATE INDEX access_review_item_page ON auth_governance.access_review_item(tenant_id,application_id,environment,task_id,id);
CREATE TABLE auth_governance.access_review_audit (
 id varchar(36) PRIMARY KEY,
 tenant_id varchar(36) NOT NULL,
 application_id varchar(100) NOT NULL,
 environment varchar(40) NOT NULL,
 task_id varchar(36) NOT NULL,
 item_id varchar(36) REFERENCES auth_governance.access_review_item(id),
 operator_ref varchar(80) NOT NULL,
 command_id varchar(36) NOT NULL,
 operation varchar(40) NOT NULL,
 previous_state varchar(160),
 resulting_state varchar(160) NOT NULL,
 reason varchar(500) NOT NULL CHECK(length(trim(reason))>0),
 occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(tenant_id,application_id,environment,operator_ref,command_id),
 FOREIGN KEY(task_id,tenant_id,application_id,environment) REFERENCES auth_governance.access_review_task(id,tenant_id,application_id,environment)
);
CREATE INDEX access_review_audit_task ON auth_governance.access_review_audit(tenant_id,application_id,environment,task_id,id);
COMMENT ON TABLE auth_governance.access_review_task IS '人工权限复核固定范围与责任任务';
COMMENT ON COLUMN auth_governance.access_review_task.id IS '复核任务UUID';
COMMENT ON COLUMN auth_governance.access_review_task.tenant_id IS '固定企业';
COMMENT ON COLUMN auth_governance.access_review_task.application_id IS '固定应用';
COMMENT ON COLUMN auth_governance.access_review_task.environment IS '固定环境';
COMMENT ON COLUMN auth_governance.access_review_task.membership_id IS '目标成员，所有代际来源仍单独展示';
COMMENT ON COLUMN auth_governance.access_review_task.target_generation IS '创建时目标代际，不自动继承新代际';
COMMENT ON COLUMN auth_governance.access_review_task.original_basis_hash IS '创建时不可变人员依据摘要';
COMMENT ON COLUMN auth_governance.access_review_task.responsible_membership_id IS '当前显式负责人，不自动分配';
COMMENT ON COLUMN auth_governance.access_review_task.responsible_generation IS '负责人固定成员代际';
COMMENT ON COLUMN auth_governance.access_review_task.state IS '复核任务稳定状态';
COMMENT ON COLUMN auth_governance.access_review_task.version IS '任务乐观版本';
COMMENT ON COLUMN auth_governance.access_review_task.reason IS '创建复核原因';
COMMENT ON COLUMN auth_governance.access_review_task.created_by IS '已验证创建者成员UUID';
COMMENT ON COLUMN auth_governance.access_review_task.creator_generation IS '创建者当时成员代际';
COMMENT ON COLUMN auth_governance.access_review_task.command_id IS '原创建命令UUID';
COMMENT ON COLUMN auth_governance.access_review_task.item_count IS '固定显式来源数量，最多100';
COMMENT ON COLUMN auth_governance.access_review_task.created_at IS '数据库创建时刻';
COMMENT ON COLUMN auth_governance.access_review_task.updated_at IS '数据库最后检查点时刻';
COMMENT ON TABLE auth_governance.access_review_item IS '人工权限复核不可变来源及可恢复决定检查点';
COMMENT ON COLUMN auth_governance.access_review_item.id IS '复核条目UUID';
COMMENT ON COLUMN auth_governance.access_review_item.task_id IS '所属固定复核任务';
COMMENT ON COLUMN auth_governance.access_review_item.tenant_id IS '固定企业';
COMMENT ON COLUMN auth_governance.access_review_item.application_id IS '固定应用';
COMMENT ON COLUMN auth_governance.access_review_item.environment IS '固定环境';
COMMENT ON COLUMN auth_governance.access_review_item.grant_id IS '唯一已选原来源';
COMMENT ON COLUMN auth_governance.access_review_item.original_version IS '创建时原Grant版本';
COMMENT ON COLUMN auth_governance.access_review_item.original_json IS '最小完整来源不可变快照，不含登录绑定';
COMMENT ON COLUMN auth_governance.access_review_item.original_hash IS '来源业务事实摘要，排除投影瞬时状态';
COMMENT ON COLUMN auth_governance.access_review_item.state IS '条目稳定状态，不将受理当完成';
COMMENT ON COLUMN auth_governance.access_review_item.version IS '条目CAS版本';
COMMENT ON COLUMN auth_governance.access_review_item.reason IS '最近一次决定原因，原决定保留审计';
COMMENT ON COLUMN auth_governance.access_review_item.revoke_command IS '预分配原严格撤权命令UUID';
COMMENT ON COLUMN auth_governance.access_review_item.confirmed_operation_id IS '实际确认完成的严格投影操作UUID';
COMMENT ON TABLE auth_governance.access_review_audit IS '人工权限复核不可修改删除的决定审计';
COMMENT ON COLUMN auth_governance.access_review_audit.id IS '复核审计UUID';
COMMENT ON COLUMN auth_governance.access_review_audit.tenant_id IS '固定企业';
COMMENT ON COLUMN auth_governance.access_review_audit.application_id IS '固定应用';
COMMENT ON COLUMN auth_governance.access_review_audit.environment IS '固定环境';
COMMENT ON COLUMN auth_governance.access_review_audit.task_id IS '所属复核任务';
COMMENT ON COLUMN auth_governance.access_review_audit.item_id IS '目标条目，任务操作为空';
COMMENT ON COLUMN auth_governance.access_review_audit.operator_ref IS '已验证成员UUID及代际';
COMMENT ON COLUMN auth_governance.access_review_audit.command_id IS '原幂等命令UUID';
COMMENT ON COLUMN auth_governance.access_review_audit.operation IS '稳定复核操作代码';
COMMENT ON COLUMN auth_governance.access_review_audit.previous_state IS '前状态或原负责人';
COMMENT ON COLUMN auth_governance.access_review_audit.resulting_state IS '后状态或新负责人';
COMMENT ON COLUMN auth_governance.access_review_audit.reason IS '受控决定原因，不记录Token或OA全文';
COMMENT ON COLUMN auth_governance.access_review_audit.occurred_at IS '数据库审计提交时刻';
CREATE FUNCTION auth_governance.review_immutable_inputs() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION '复核事实不可删除'; END IF;
 IF NEW.version<>OLD.version+1 THEN RAISE EXCEPTION '复核版本必须逐次推进'; END IF;
 IF TG_TABLE_NAME='access_review_task' THEN
  IF OLD.state='COMPLETED' OR (OLD.state='CANCELLED' AND NEW.state<>'CANCELLED') THEN RAISE EXCEPTION '复核终态不可重开'; END IF;
  IF (to_jsonb(NEW)-ARRAY['state','version','updated_at','responsible_membership_id','responsible_generation']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['state','version','updated_at','responsible_membership_id','responsible_generation']) THEN RAISE EXCEPTION '复核原输入不可修改'; END IF;
 ELSE
  IF NOT ((OLD.state IN ('PENDING','INVESTIGATE') AND NEW.state IN ('INVESTIGATE','KEPT','WAIT_REVOKE','CANCELLED')) OR (OLD.state='WAIT_REVOKE' AND NEW.state='REVOKED')) THEN RAISE EXCEPTION '非法复核条目状态迁移'; END IF;
  IF (to_jsonb(NEW)-ARRAY['state','version','reason','confirmed_operation_id']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['state','version','reason','confirmed_operation_id']) THEN RAISE EXCEPTION '复核原来源不可修改'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER access_review_task_immutable BEFORE UPDATE OR DELETE ON auth_governance.access_review_task FOR EACH ROW EXECUTE FUNCTION auth_governance.review_immutable_inputs();
CREATE TRIGGER access_review_item_immutable BEFORE UPDATE OR DELETE ON auth_governance.access_review_item FOR EACH ROW EXECUTE FUNCTION auth_governance.review_immutable_inputs();
CREATE TRIGGER access_review_audit_immutable BEFORE UPDATE OR DELETE ON auth_governance.access_review_audit FOR EACH ROW EXECUTE FUNCTION auth_governance.reject_role_mutation();
