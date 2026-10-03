-- V27已在隔离目标应用；追加修正，不改历史checksum。
-- 停用委派只能保留或缩减原能力与期限，不能借disabled同时扩大已弃用引用。
CREATE OR REPLACE FUNCTION auth_governance.guard_deprecated_usage() RETURNS trigger LANGUAGE plpgsql AS $$
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
    AND NEW.max_duration_seconds<=OLD.max_duration_seconds
    AND OLD.capabilities_json::jsonb @> NEW.capabilities_json::jsonb
    AND (NOT NEW.enabled OR OLD.enabled) THEN RETURN NEW; END IF;
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
