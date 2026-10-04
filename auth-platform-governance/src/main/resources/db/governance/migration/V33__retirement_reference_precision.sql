-- V32已在隔离测试库执行，追加修正历史角色与无来源分区的精度，不改已应用迁移。
-- 仅审计角色不会把与此能力无Grant关系的人员投影变成正常退役阻断。
CREATE OR REPLACE FUNCTION auth_governance.retirement_references(app text,cap text)
RETURNS TABLE(kind text,id text,tenant_id text,environment text,blocking boolean,state text,role_id text,source_type text,valid_to timestamptz,fingerprint text)
LANGUAGE sql STABLE AS $$
 WITH roles AS MATERIALIZED (SELECT r.* FROM auth_governance.role_version r WHERE r.application_id=$1 AND r.capabilities_json::jsonb ? $2),
 grants AS MATERIALIZED (SELECT g.* FROM auth_governance.access_grant g JOIN roles r ON r.id=g.role_id),
 partitions AS (SELECT DISTINCT g.tenant_id,g.environment FROM grants g)
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
COMMENT ON FUNCTION auth_governance.retirement_references(text,text) IS '完整引用同快照；投影分区来自实际能力来源，纯历史角色不制造人员投影阻断';
ALTER TABLE auth_governance.portal_diagnostic_audit ALTER COLUMN target_id TYPE varchar(100);
COMMENT ON COLUMN auth_governance.portal_diagnostic_audit.target_id IS '原来源UUID或本次诊断能力编码，不包含人员范围或Token';
