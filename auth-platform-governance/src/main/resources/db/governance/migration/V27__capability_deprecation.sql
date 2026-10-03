-- 独立元数据只限制新增引用，不回填、不撤销已有Grant或改写清单。
CREATE TABLE auth_governance.capability_lifecycle (
 application_id varchar(100) NOT NULL REFERENCES auth_governance.application_catalog(application_id),
 capability varchar(100) NOT NULL,
 state varchar(20) NOT NULL CHECK(state IN ('ACTIVE','DEPRECATED')),
 version bigint NOT NULL CHECK(version>0),
 reason varchar(500) NOT NULL CHECK(length(btrim(reason))>0),
 changed_by varchar(36) NOT NULL REFERENCES auth_governance.principal(id),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CONSTRAINT capability_lifecycle_pkey PRIMARY KEY(application_id,capability)
);
COMMENT ON TABLE auth_governance.capability_lifecycle IS '应用能力弃用元数据；缺行为ACTIVE版本0，与紧急disabled分离';
COMMENT ON COLUMN auth_governance.capability_lifecycle.application_id IS '当前权限实例内应用作用域';
COMMENT ON COLUMN auth_governance.capability_lifecycle.capability IS '不可删除的稳定能力编码';
COMMENT ON COLUMN auth_governance.capability_lifecycle.state IS 'ACTIVE允许新增使用，DEPRECATED保留历史并拒绝新增';
COMMENT ON COLUMN auth_governance.capability_lifecycle.version IS '显式Owner变更的连续乐观版本';
COMMENT ON COLUMN auth_governance.capability_lifecycle.reason IS '本次变更真实原因';
COMMENT ON COLUMN auth_governance.capability_lifecycle.changed_by IS '来自当前真实登录的应用Owner主体';
COMMENT ON COLUMN auth_governance.capability_lifecycle.updated_at IS '最后变更UTC';
COMMENT ON INDEX auth_governance.capability_lifecycle_pkey IS '按应用与能力取得权威状态';

CREATE TABLE auth_governance.capability_lifecycle_command (
 application_id varchar(100) NOT NULL,
 command_id varchar(36) NOT NULL,
 payload_hash varchar(64) NOT NULL CHECK(length(payload_hash)=64),
 capability varchar(100) NOT NULL,
 before_state varchar(20) NOT NULL CHECK(before_state IN ('ACTIVE','DEPRECATED')),
 after_state varchar(20) NOT NULL CHECK(after_state IN ('ACTIVE','DEPRECATED')),
 before_version bigint NOT NULL CHECK(before_version>=0),
 after_version bigint NOT NULL CHECK(after_version=before_version+1),
 reason varchar(500) NOT NULL CHECK(length(btrim(reason))>0),
 actor varchar(36) NOT NULL REFERENCES auth_governance.principal(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CONSTRAINT capability_lifecycle_command_pkey PRIMARY KEY(application_id,command_id),
 CONSTRAINT capability_lifecycle_effect_once UNIQUE(application_id,capability,after_version),
 FOREIGN KEY(application_id,capability) REFERENCES auth_governance.capability_lifecycle(application_id,capability),
 CHECK(before_state<>after_state)
);
COMMENT ON TABLE auth_governance.capability_lifecycle_command IS '不可变弃用或恢复命令及精确历史回执；重复不重新应用';
COMMENT ON COLUMN auth_governance.capability_lifecycle_command.application_id IS '原命令应用作用域';
COMMENT ON COLUMN auth_governance.capability_lifecycle_command.command_id IS '原Owner幂等命令UUID';
COMMENT ON COLUMN auth_governance.capability_lifecycle_command.payload_hash IS '目标状态版本与原因的固定摘要';
COMMENT ON COLUMN auth_governance.capability_lifecycle_command.capability IS '原命令固定能力';
COMMENT ON COLUMN auth_governance.capability_lifecycle_command.before_state IS '原变更之前的状态';
COMMENT ON COLUMN auth_governance.capability_lifecycle_command.after_state IS '原命令提交的目标状态';
COMMENT ON COLUMN auth_governance.capability_lifecycle_command.before_version IS '原变更前版本，初始为0';
COMMENT ON COLUMN auth_governance.capability_lifecycle_command.after_version IS '原提交连续版本，不随之后恢复变化';
COMMENT ON COLUMN auth_governance.capability_lifecycle_command.reason IS '原命令完整变更原因';
COMMENT ON COLUMN auth_governance.capability_lifecycle_command.actor IS '原已认证Owner主体';
COMMENT ON COLUMN auth_governance.capability_lifecycle_command.created_at IS '原提交UTC';
COMMENT ON INDEX auth_governance.capability_lifecycle_command_pkey IS '应用内原命令重放与冲突查询';
COMMENT ON INDEX auth_governance.capability_lifecycle_effect_once IS '每个能力版本只存在一条提交依据';
CREATE TRIGGER immutable_capability_lifecycle_command BEFORE UPDATE OR DELETE ON auth_governance.capability_lifecycle_command
 FOR EACH ROW EXECUTE FUNCTION auth_governance.reject_role_mutation();

CREATE FUNCTION auth_governance.guard_capability_lifecycle() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'lifecycle history is retained'; END IF;
 -- 同一应用排他锁与新增使用共享锁互斥，SQL旧节点同样遵守此顺序。
 PERFORM 1 FROM auth_governance.application_catalog WHERE application_id=NEW.application_id FOR UPDATE;
 IF TG_OP='INSERT' THEN
  IF NEW.version<>1 OR NEW.state<>'DEPRECATED' THEN RAISE EXCEPTION 'invalid initial lifecycle'; END IF;
 ELSE
  IF NEW.application_id<>OLD.application_id OR NEW.capability<>OLD.capability
   OR NEW.version<>OLD.version+1 OR NEW.state=OLD.state THEN RAISE EXCEPTION 'invalid lifecycle transition'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER capability_lifecycle_transition BEFORE INSERT OR UPDATE OR DELETE ON auth_governance.capability_lifecycle
 FOR EACH ROW EXECUTE FUNCTION auth_governance.guard_capability_lifecycle();

CREATE FUNCTION auth_governance.guard_deprecated_usage() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE caps jsonb;
BEGIN
 -- 即使旧应用节点没有新用例，正式数据库也会阻止新增已弃用能力引用。
 PERFORM 1 FROM auth_governance.application_catalog WHERE application_id=NEW.application_id FOR SHARE;
 IF TG_TABLE_NAME='role_version' THEN
  IF EXISTS(SELECT 1 FROM auth_governance.role_version r WHERE r.tenant_id=NEW.tenant_id
   AND r.application_id=NEW.application_id AND r.environment=NEW.environment AND r.role_code=NEW.role_code
   AND r.version=NEW.version AND r.capabilities_json::jsonb=NEW.capabilities_json::jsonb AND r.content_hash=NEW.content_hash) THEN RETURN NEW; END IF;
  caps=NEW.capabilities_json::jsonb;
 ELSIF TG_TABLE_NAME='access_delegation' THEN
  IF TG_OP='UPDATE' THEN
   IF NEW.tenant_id=OLD.tenant_id AND NEW.application_id=OLD.application_id AND NEW.environment=OLD.environment
    AND NEW.membership_id=OLD.membership_id AND NEW.generation=OLD.generation
    AND (NOT NEW.enabled OR (OLD.enabled AND NEW.max_duration_seconds<=OLD.max_duration_seconds
      AND OLD.capabilities_json::jsonb @> NEW.capabilities_json::jsonb)) THEN RETURN NEW; END IF;
  ELSIF EXISTS(SELECT 1 FROM auth_governance.access_delegation d WHERE d.tenant_id=NEW.tenant_id
   AND d.application_id=NEW.application_id AND d.environment=NEW.environment AND d.membership_id=NEW.membership_id
   AND d.generation=NEW.generation AND d.enabled=NEW.enabled AND d.max_duration_seconds=NEW.max_duration_seconds
   AND d.capabilities_json::jsonb=NEW.capabilities_json::jsonb) THEN RETURN NEW;
  END IF;
  caps=NEW.capabilities_json::jsonb;
 ELSIF TG_TABLE_NAME='access_grant' THEN
  -- 已有PENDING投影成ACTIVE、撤权和期限缩减不属于新增使用。
  IF TG_OP='UPDATE' AND (to_jsonb(NEW)-ARRAY['state','version','zed_token','updated_at','valid_from','valid_to'])
   IS NOT DISTINCT FROM (to_jsonb(OLD)-ARRAY['state','version','zed_token','updated_at','valid_from','valid_to'])
   AND NEW.valid_to<=OLD.valid_to AND NEW.valid_from>=OLD.valid_from
   AND (OLD.state<>'REVOKED' OR NEW.state='REVOKED') THEN RETURN NEW; END IF;
  SELECT capabilities_json::jsonb INTO caps FROM auth_governance.role_version WHERE id=NEW.role_id;
 ELSIF TG_TABLE_NAME='request_policy' THEN
  IF TG_OP='UPDATE' AND (NOT NEW.enabled OR (OLD.enabled AND to_jsonb(NEW)=to_jsonb(OLD))) THEN RETURN NEW; END IF;
  SELECT capabilities_json::jsonb INTO caps FROM auth_governance.role_version WHERE id=NEW.role_id;
 ELSIF TG_TABLE_NAME='access_request' THEN
  caps=NEW.capabilities_json::jsonb;
 ELSE RAISE EXCEPTION 'unsupported lifecycle guarded table';
 END IF;
 IF EXISTS(SELECT 1 FROM auth_governance.capability_lifecycle l JOIN jsonb_array_elements_text(COALESCE(caps,'[]'::jsonb)) c ON c.value=l.capability
  WHERE l.application_id=NEW.application_id AND l.state<>'ACTIVE') THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='deprecated capability cannot gain new usage';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER deprecated_role_usage BEFORE INSERT ON auth_governance.role_version FOR EACH ROW EXECUTE FUNCTION auth_governance.guard_deprecated_usage();
CREATE TRIGGER deprecated_delegation_usage BEFORE INSERT OR UPDATE ON auth_governance.access_delegation FOR EACH ROW EXECUTE FUNCTION auth_governance.guard_deprecated_usage();
CREATE TRIGGER deprecated_grant_usage BEFORE INSERT OR UPDATE ON auth_governance.access_grant FOR EACH ROW EXECUTE FUNCTION auth_governance.guard_deprecated_usage();
CREATE TRIGGER deprecated_policy_usage BEFORE INSERT OR UPDATE ON auth_governance.request_policy FOR EACH ROW EXECUTE FUNCTION auth_governance.guard_deprecated_usage();
CREATE TRIGGER deprecated_request_usage BEFORE INSERT ON auth_governance.access_request FOR EACH ROW EXECUTE FUNCTION auth_governance.guard_deprecated_usage();
