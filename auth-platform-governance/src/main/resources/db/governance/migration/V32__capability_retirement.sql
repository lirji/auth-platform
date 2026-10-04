-- 正常退役保留所有旧清单、角色和来源；未完成引用不能用资格暂不可用掩盖。
ALTER TABLE auth_governance.capability_lifecycle DROP CONSTRAINT capability_lifecycle_state_check;
ALTER TABLE auth_governance.capability_lifecycle ADD CONSTRAINT capability_lifecycle_state_check CHECK(state IN ('ACTIVE','DEPRECATED','RETIRED'));
ALTER TABLE auth_governance.capability_lifecycle_command DROP CONSTRAINT capability_lifecycle_command_after_state_check;
ALTER TABLE auth_governance.capability_lifecycle_command ADD CONSTRAINT capability_lifecycle_command_after_state_check CHECK(after_state IN ('ACTIVE','DEPRECATED','RETIRED'));
COMMENT ON COLUMN auth_governance.capability_lifecycle.state IS 'ACTIVE允许新增，DEPRECATED拒绝新增，RETIRED为不可恢复兼容墓碑';

CREATE FUNCTION auth_governance.retirement_references(app text,cap text)
RETURNS TABLE(kind text,id text,tenant_id text,environment text,blocking boolean,state text,role_id text,source_type text,valid_to timestamptz,fingerprint text)
LANGUAGE sql STABLE AS $$
 WITH roles AS MATERIALIZED (SELECT r.* FROM auth_governance.role_version r WHERE r.application_id=$1 AND r.capabilities_json::jsonb ? $2),
 grants AS MATERIALIZED (SELECT g.* FROM auth_governance.access_grant g JOIN roles r ON r.id=g.role_id),
 partitions AS (SELECT DISTINCT r.tenant_id,r.environment FROM roles r)
 SELECT 'MENU',m->>'code',NULL,NULL,true,'CURRENT',NULL,NULL,NULL::timestamptz,md5(m::text)
 FROM auth_governance.application_catalog a JOIN auth_governance.application_manifest s ON s.application_id=a.application_id AND s.version=a.manifest_version,
 jsonb_array_elements(s.manifest_json::jsonb->'menus') m WHERE a.application_id=$1 AND m->'any_of' ? $2
 UNION ALL SELECT 'ROLE',r.id,r.tenant_id,r.environment,false,'HISTORICAL',r.id,NULL,NULL,md5(to_jsonb(r)::text) FROM roles r
 UNION ALL SELECT 'GRANT',g.id,g.tenant_id,g.environment,g.state IN ('ACTIVE','PENDING') AND g.valid_to>statement_timestamp(),g.state,g.role_id,g.source_type,g.valid_to,md5(to_jsonb(g)::text) FROM grants g
 UNION ALL SELECT 'DELEGATION',d.membership_id,d.tenant_id,d.environment,d.enabled,CASE WHEN d.enabled THEN 'ENABLED' ELSE 'DISABLED' END,NULL,NULL,NULL,md5(to_jsonb(d)::text)
 FROM auth_governance.access_delegation d WHERE d.application_id=$1 AND d.capabilities_json::jsonb ? $2
 UNION ALL SELECT 'POLICY',p.id,p.tenant_id,p.environment,p.enabled,CASE WHEN p.enabled THEN 'ENABLED' ELSE 'DISABLED' END,p.role_id,NULL,NULL,md5(to_jsonb(p)::text)
 FROM auth_governance.request_policy p JOIN roles r ON r.id=p.role_id
 UNION ALL SELECT 'REQUEST',q.id,q.tenant_id,q.environment,NOT q.withdrawn AND q.valid_to>statement_timestamp() AND (q.state IN ('SUBMITTED','IN_REVIEW') OR (q.state='APPROVED' AND q.grant_id IS NULL)),q.state,q.role_id,'OA_REQUEST',q.valid_to,md5(to_jsonb(q)::text)
 FROM auth_governance.access_request q WHERE q.application_id=$1 AND q.capabilities_json::jsonb ? $2
 UNION ALL SELECT 'MIGRATION',i.id,i.tenant_id,i.environment,i.stage NOT IN ('COMPLETED','FAILED','CANCELLED'),i.stage,i.new_role_id,i.source_type,i.valid_to,md5(to_jsonb(i)::text)
 FROM auth_governance.role_migration_item i JOIN auth_governance.role_migration_task t ON t.id=i.task_id
 WHERE i.application_id=$1 AND (EXISTS(SELECT 1 FROM roles r WHERE r.id=i.new_role_id) OR EXISTS(SELECT 1 FROM roles r WHERE r.id=t.old_role_id))
 UNION ALL SELECT 'LEGACY_PROJECTION',o.grant_id||':'||o.grant_version,g.tenant_id,g.environment,NOT o.completed,CASE WHEN o.completed THEN 'COMPLETED' ELSE 'PENDING' END,g.role_id,g.source_type,g.valid_to,md5(to_jsonb(o)::text)
 FROM auth_governance.grant_projection o JOIN grants g ON g.id=o.grant_id
 UNION ALL SELECT 'PROJECTION',o.id,p.tenant_id,p.environment,o.state NOT IN ('APPLIED','SUPERSEDED'),o.state,NULL,NULL,NULL,md5(to_jsonb(o)::text)
 FROM auth_governance.projection_operation o JOIN auth_governance.policy_partition p ON p.id=o.fence_id JOIN partitions x ON x.tenant_id=p.tenant_id AND x.environment=p.environment WHERE p.application_id=$1
 UNION ALL SELECT 'RECEIPT',g.id,g.tenant_id,g.environment,NOT EXISTS(SELECT 1 FROM auth_governance.projection_grant_receipt r JOIN auth_governance.projection_operation o ON o.id=r.operation_id
 WHERE r.grant_id=g.id AND r.grant_version=g.version AND o.state='APPLIED'),g.state,g.role_id,g.source_type,g.valid_to,md5(to_jsonb(g)::text)
 FROM grants g JOIN auth_governance.policy_partition p ON p.tenant_id=g.tenant_id AND p.application_id=g.application_id AND p.environment=g.environment
 UNION ALL SELECT 'FENCE',p.id,p.tenant_id,p.environment,p.state<>'READY' OR p.desired_epoch<>p.applied_epoch,p.state,NULL,NULL,NULL,md5(to_jsonb(p)::text)
 FROM auth_governance.policy_partition p JOIN partitions x ON x.tenant_id=p.tenant_id AND x.environment=p.environment WHERE p.application_id=$1
 UNION ALL SELECT 'DIRECTORY_FENCE',d.id,x.tenant_id,x.environment,d.state<>'READY' OR d.desired_epoch<>d.applied_epoch,d.state,NULL,NULL,NULL,md5(to_jsonb(d)::text)
 FROM auth_governance.directory_fence d JOIN partitions x ON x.tenant_id=d.tenant_id
 UNION ALL SELECT 'DIRECTORY_PROJECTION',o.id,x.tenant_id,x.environment,o.state NOT IN ('APPLIED','SUPERSEDED'),o.state,NULL,NULL,NULL,md5(to_jsonb(o)::text)
 FROM auth_governance.projection_operation o JOIN auth_governance.directory_fence d ON d.id=o.fence_id JOIN partitions x ON x.tenant_id=d.tenant_id
 UNION ALL SELECT 'EXECUTION',e.id,m.tenant_id,e.environment,e.expires_at>statement_timestamp(),'ISSUED',NULL,NULL,e.expires_at,md5(to_jsonb(e)::text)
 FROM auth_governance.execution_reference e JOIN auth_governance.membership m ON m.id=e.member WHERE e.application=$1 AND e.capability=$2
$$;
COMMENT ON FUNCTION auth_governance.retirement_references(text,text) IS '同快照全应用引用；未来来源与在途效果阻断，角色历史只审计';

CREATE TABLE auth_governance.capability_retirement_evidence (
 application_id varchar(100) NOT NULL,
 command_id varchar(36) NOT NULL,
 capability varchar(100) NOT NULL,
 lifecycle_version bigint NOT NULL CHECK(lifecycle_version>0),
 manifest_version bigint NOT NULL CHECK(manifest_version>0),
 content_hash varchar(64) NOT NULL CHECK(length(content_hash)=64),
 presentation_hash varchar(64) NOT NULL CHECK(length(presentation_hash)=64),
 basis_hash varchar(64) NOT NULL CHECK(length(basis_hash)=64),
 proof_hash varchar(64) NOT NULL CHECK(length(proof_hash)=64),
 proof_json text NOT NULL CHECK(octet_length(proof_json)<=131072 AND jsonb_typeof(proof_json::jsonb)='object'),
 valid_until timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(application_id,command_id),
 UNIQUE(application_id,capability,lifecycle_version),
 FOREIGN KEY(application_id,command_id) REFERENCES auth_governance.capability_lifecycle_command(application_id,command_id) DEFERRABLE INITIALLY DEFERRED
);
COMMENT ON TABLE auth_governance.capability_retirement_evidence IS '与最终退役原命令原子提交的受信签名证明及固定引用依据';
COMMENT ON COLUMN auth_governance.capability_retirement_evidence.application_id IS '本实例全应用作用域';
COMMENT ON COLUMN auth_governance.capability_retirement_evidence.command_id IS '原Owner退役命令';
COMMENT ON COLUMN auth_governance.capability_retirement_evidence.capability IS '保留墓碑的稳定能力编码';
COMMENT ON COLUMN auth_governance.capability_retirement_evidence.lifecycle_version IS '变更前连续版本';
COMMENT ON COLUMN auth_governance.capability_retirement_evidence.manifest_version IS '核验时目录版本';
COMMENT ON COLUMN auth_governance.capability_retirement_evidence.content_hash IS '固定原权限摘要';
COMMENT ON COLUMN auth_governance.capability_retirement_evidence.presentation_hash IS '固定原展示摘要';
COMMENT ON COLUMN auth_governance.capability_retirement_evidence.basis_hash IS '固定完整引用依据';
COMMENT ON COLUMN auth_governance.capability_retirement_evidence.proof_hash IS '已验签原证明字节SHA256';
COMMENT ON COLUMN auth_governance.capability_retirement_evidence.proof_json IS '已核验签名信封原字节，不含私钥或Token';
COMMENT ON COLUMN auth_governance.capability_retirement_evidence.valid_until IS '受信运行证明排他截止';
COMMENT ON COLUMN auth_governance.capability_retirement_evidence.created_at IS '原提交UTC时刻';
CREATE TRIGGER immutable_retirement_evidence BEFORE UPDATE OR DELETE ON auth_governance.capability_retirement_evidence FOR EACH ROW EXECUTE FUNCTION auth_governance.reject_role_mutation();

CREATE OR REPLACE FUNCTION auth_governance.guard_capability_lifecycle() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'lifecycle history is retained'; END IF;
 PERFORM 1 FROM auth_governance.application_catalog WHERE application_id=NEW.application_id FOR UPDATE;
 IF TG_OP='INSERT' THEN
  IF NEW.version<>1 OR NEW.state<>'DEPRECATED' THEN RAISE EXCEPTION 'invalid initial lifecycle'; END IF;
 ELSE
  IF NEW.application_id<>OLD.application_id OR NEW.capability<>OLD.capability OR NEW.version<>OLD.version+1 OR NEW.state=OLD.state OR OLD.state='RETIRED' THEN RAISE EXCEPTION 'invalid lifecycle transition'; END IF;
  IF NEW.state='RETIRED' THEN
   IF OLD.state<>'DEPRECATED' OR EXISTS(SELECT 1 FROM auth_governance.retirement_references(NEW.application_id,NEW.capability) WHERE blocking) THEN RAISE EXCEPTION 'retirement references remain'; END IF;
   IF NOT EXISTS(SELECT 1 FROM auth_governance.capability_retirement_evidence e JOIN auth_governance.application_catalog a ON a.application_id=e.application_id
    JOIN auth_governance.application_manifest s ON s.application_id=a.application_id AND s.version=a.manifest_version
    LEFT JOIN auth_governance.application_manifest_presentation ps ON ps.application_id=s.application_id AND ps.version=s.version
    WHERE e.application_id=NEW.application_id AND e.capability=NEW.capability AND e.lifecycle_version=OLD.version
     AND e.manifest_version=a.manifest_version AND e.content_hash=s.content_hash AND e.presentation_hash=COALESCE(ps.presentation_hash,'4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945') AND e.valid_until>clock_timestamp()) THEN RAISE EXCEPTION 'verified current retirement evidence required'; END IF;
  END IF;
 END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION auth_governance.guard_retired_reference() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE app text; caps jsonb;
BEGIN
 -- 新计划与执行引用和退役争用同应用锁；历史推进与取消不新增引用。
 IF TG_TABLE_NAME='execution_reference' THEN app=NEW.application;caps=jsonb_build_array(NEW.capability);
 ELSIF TG_TABLE_NAME='application_manifest' THEN
  app=NEW.application_id;
  IF EXISTS(SELECT 1 FROM auth_governance.application_manifest s WHERE s.application_id=app AND s.version=NEW.version AND s.content_hash=NEW.content_hash) THEN RETURN NEW; END IF;
  SELECT COALESCE(jsonb_agg(c), '[]'::jsonb) INTO caps FROM jsonb_array_elements(NEW.manifest_json::jsonb->'menus') m,jsonb_array_elements(m->'any_of') c;
 ELSE app=NEW.application_id;
  SELECT COALESCE(jsonb_agg(c), '[]'::jsonb) INTO caps FROM auth_governance.role_version r,jsonb_array_elements(r.capabilities_json::jsonb) c
   WHERE r.id=NEW.new_role_id OR (TG_TABLE_NAME='role_migration_task' AND r.id=(to_jsonb(NEW)->>'old_role_id'))
    OR (TG_TABLE_NAME='role_migration_item' AND r.id=(SELECT t.old_role_id FROM auth_governance.role_migration_task t WHERE t.id=(to_jsonb(NEW)->>'task_id')));
 END IF;
 PERFORM 1 FROM auth_governance.application_catalog WHERE application_id=app FOR SHARE;
 IF EXISTS(SELECT 1 FROM auth_governance.capability_lifecycle l WHERE l.application_id=app AND l.state='RETIRED' AND caps ? l.capability) THEN RAISE EXCEPTION 'retired capability cannot gain a reference'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER retired_migration_task BEFORE INSERT ON auth_governance.role_migration_task FOR EACH ROW EXECUTE FUNCTION auth_governance.guard_retired_reference();
CREATE TRIGGER retired_migration_item BEFORE INSERT ON auth_governance.role_migration_item FOR EACH ROW EXECUTE FUNCTION auth_governance.guard_retired_reference();
CREATE TRIGGER retired_execution_reference BEFORE INSERT ON auth_governance.execution_reference FOR EACH ROW EXECUTE FUNCTION auth_governance.guard_retired_reference();

CREATE TRIGGER retired_manifest_reference BEFORE INSERT ON auth_governance.application_manifest FOR EACH ROW EXECUTE FUNCTION auth_governance.guard_retired_reference();
