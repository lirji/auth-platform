-- 组只是OA事实的投影；未知业务时区保持拒绝，不能猜测日期边界。
ALTER TABLE auth_governance.directory_source ADD COLUMN business_zone varchar(100);
COMMENT ON COLUMN auth_governance.directory_source.business_zone IS '与OA生产者一致的IANA业务时区，未配置不授予组织组资格';
CREATE TABLE auth_governance.directory_group (
 id varchar(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
 source_id varchar(36) NOT NULL,
 tenant_id varchar(36) NOT NULL,
 org_ref varchar(160) NOT NULL,
 active boolean NOT NULL DEFAULT false,
 UNIQUE(source_id,org_ref), UNIQUE(id,tenant_id),
 FOREIGN KEY(source_id,tenant_id) REFERENCES auth_governance.directory_source(id,tenant_id)
);
CREATE TABLE auth_governance.directory_group_member (
 id varchar(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
 group_id varchar(36) NOT NULL,
 tenant_id varchar(36) NOT NULL,
 membership_id varchar(36) NOT NULL,
 generation bigint NOT NULL CHECK(generation>0),
 current boolean NOT NULL,
 periods jsonb NOT NULL CHECK(jsonb_typeof(periods)='array' AND jsonb_array_length(periods)<=100),
 UNIQUE(group_id,membership_id,generation),
 FOREIGN KEY(group_id,tenant_id) REFERENCES auth_governance.directory_group(id,tenant_id),
 FOREIGN KEY(membership_id,tenant_id) REFERENCES auth_governance.membership(id,tenant_id)
);
CREATE INDEX directory_group_member_current_idx ON auth_governance.directory_group_member(tenant_id,membership_id,generation,group_id) WHERE current;
CREATE INDEX directory_group_member_scan_idx ON auth_governance.directory_group_member(tenant_id,id);
-- 保留旧代际和退出的边供可靠图删除；每个事件最多100任职，不递归推导组织树。
CREATE FUNCTION auth_governance.derive_directory_groups(e auth_governance.directory_entry) RETURNS void LANGUAGE plpgsql AS $$
DECLARE item record; gid varchar; gen bigint;
BEGIN
 IF e.aggregate_type='ORG' THEN
  INSERT INTO auth_governance.directory_group(source_id,tenant_id,org_ref,active)
  VALUES(e.source_id,e.tenant_id,e.aggregate_id,e.payload_json->'organization'->>'status'='ACTIVE')
  ON CONFLICT(source_id,org_ref) DO UPDATE SET active=EXCLUDED.active;
 ELSE
  SELECT generation INTO gen FROM auth_governance.membership WHERE id=e.membership_id;
  UPDATE auth_governance.directory_group_member SET current=false
   WHERE tenant_id=e.tenant_id AND membership_id=e.membership_id AND current;
  FOR item IN SELECT a->>'org_id' org, jsonb_agg(a ORDER BY a->>'id') periods
   FROM jsonb_array_elements(e.payload_json->'employee'->'assignments') a
   WHERE a->>'type' IN ('PRIMARY','CONCURRENT') GROUP BY a->>'org_id' LOOP
    INSERT INTO auth_governance.directory_group(source_id,tenant_id,org_ref)
    VALUES(e.source_id,e.tenant_id,item.org) ON CONFLICT DO NOTHING;
    SELECT id INTO gid FROM auth_governance.directory_group WHERE source_id=e.source_id AND org_ref=item.org;
    INSERT INTO auth_governance.directory_group_member(group_id,tenant_id,membership_id,generation,current,periods)
    VALUES(gid,e.tenant_id,e.membership_id,gen,e.payload_json->'employee'->>'status'<>'LEFT',item.periods)
    ON CONFLICT(group_id,membership_id,generation) DO UPDATE SET current=EXCLUDED.current,periods=EXCLUDED.periods;
  END LOOP;
 END IF;
END;
$$;
CREATE FUNCTION auth_governance.directory_groups_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN PERFORM auth_governance.derive_directory_groups(NEW); RETURN NEW; END;
$$;
CREATE TRIGGER directory_groups_changed AFTER INSERT OR UPDATE ON auth_governance.directory_entry
 FOR EACH ROW EXECUTE FUNCTION auth_governance.directory_groups_changed();
-- 迁移只在受控owner运行；旧目录不自动获得权限，新组必须另行授予Grant。
DO $$ DECLARE e auth_governance.directory_entry; BEGIN
 FOR e IN SELECT * FROM auth_governance.directory_entry ORDER BY source_id,aggregate_type,aggregate_id LOOP
  PERFORM auth_governance.derive_directory_groups(e);
 END LOOP;
END $$;
ALTER TABLE auth_governance.access_grant ALTER COLUMN membership_id DROP NOT NULL;
ALTER TABLE auth_governance.access_grant DROP CONSTRAINT access_grant_generation_check;
ALTER TABLE auth_governance.access_grant DROP CONSTRAINT access_grant_source_type_check;
ALTER TABLE auth_governance.access_grant ADD COLUMN group_id varchar(36);
COMMENT ON COLUMN auth_governance.access_grant.group_id IS '组织组受益方，DIRECT为空，GROUP不伪造成员';
ALTER TABLE auth_governance.access_grant ADD CONSTRAINT grant_subject_check CHECK(
 (source_type='DIRECT' AND membership_id IS NOT NULL AND generation>0 AND group_id IS NULL) OR
 (source_type='GROUP' AND membership_id IS NULL AND generation=0 AND group_id IS NOT NULL));
ALTER TABLE auth_governance.access_grant ADD CONSTRAINT grant_group_tenant_fk
 FOREIGN KEY(group_id,tenant_id) REFERENCES auth_governance.directory_group(id,tenant_id);
CREATE UNIQUE INDEX grant_group_source_unique ON auth_governance.access_grant(tenant_id,application_id,environment,group_id,source_id) WHERE source_type='GROUP';
CREATE TABLE auth_governance.projection_grant_receipt (
 grant_id varchar(36) NOT NULL REFERENCES auth_governance.access_grant(id),
 grant_version bigint NOT NULL CHECK(grant_version>0),
 operation_id varchar(36) NOT NULL REFERENCES auth_governance.projection_receipt(operation_id),
 PRIMARY KEY(grant_id,grant_version)
);
CREATE TABLE auth_governance.capability_state (
 application_id varchar(100) NOT NULL REFERENCES auth_governance.application_catalog(application_id),
 capability varchar(100) NOT NULL,
 disabled boolean NOT NULL,
 version bigint NOT NULL CHECK(version>0),
 PRIMARY KEY(application_id,capability)
);
CREATE TABLE auth_governance.capability_command (
 application_id varchar(100) NOT NULL REFERENCES auth_governance.application_catalog(application_id),
 command_id varchar(36) NOT NULL,
 capability varchar(100) NOT NULL,
 payload_hash varchar(64) NOT NULL,
 actor varchar(36) NOT NULL REFERENCES auth_governance.principal(id),
 reason varchar(500) NOT NULL,
 previous_version bigint NOT NULL,
 result_version bigint NOT NULL,
 disabled boolean NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(application_id,command_id)
);
CREATE TRIGGER capability_fence AFTER INSERT OR UPDATE ON auth_governance.capability_state FOR EACH ROW EXECUTE FUNCTION auth_governance.catalog_changed();
CREATE TRIGGER immutable_capability_command BEFORE UPDATE OR DELETE ON auth_governance.capability_command FOR EACH ROW EXECUTE FUNCTION auth_governance.reject_role_mutation();
CREATE TRIGGER immutable_grant_receipt BEFORE UPDATE OR DELETE ON auth_governance.projection_grant_receipt FOR EACH ROW EXECUTE FUNCTION auth_governance.reject_role_mutation();
COMMENT ON TABLE auth_governance.directory_group IS 'OA直接组织组投影，不自动授予权限';
COMMENT ON COLUMN auth_governance.directory_group.id IS '稳定组UUID';
COMMENT ON COLUMN auth_governance.directory_group.source_id IS '唯一OA来源';
COMMENT ON COLUMN auth_governance.directory_group.tenant_id IS '所属企业';
COMMENT ON COLUMN auth_governance.directory_group.org_ref IS '精确OA组织主键';
COMMENT ON COLUMN auth_governance.directory_group.active IS '已收到有效ACTIVE组织事实';
COMMENT ON TABLE auth_governance.directory_group_member IS '当前任职及历史图边，退出不删除墓碑';
COMMENT ON COLUMN auth_governance.directory_group_member.id IS '稳定扫描边UUID';
COMMENT ON COLUMN auth_governance.directory_group_member.group_id IS '来源组织组';
COMMENT ON COLUMN auth_governance.directory_group_member.tenant_id IS '企业隔离边界';
COMMENT ON COLUMN auth_governance.directory_group_member.membership_id IS '受益成员';
COMMENT ON COLUMN auth_governance.directory_group_member.generation IS '源事件处理时成员代际';
COMMENT ON COLUMN auth_governance.directory_group_member.current IS '仍在最新任职事实中';
COMMENT ON COLUMN auth_governance.directory_group_member.periods IS 'PRIMARY和CONCURRENT日期区间，左闭右开';
COMMENT ON TABLE auth_governance.projection_grant_receipt IS 'Grant版本到真实图确认的持久回执';
COMMENT ON COLUMN auth_governance.projection_grant_receipt.grant_id IS '已确认授权路径';
COMMENT ON COLUMN auth_governance.projection_grant_receipt.grant_version IS '实际确认版本';
COMMENT ON COLUMN auth_governance.projection_grant_receipt.operation_id IS '真实远端提交回执外键';
COMMENT ON TABLE auth_governance.capability_state IS '应用拥有者紧急能力开关，独立于不可变角色';
COMMENT ON COLUMN auth_governance.capability_state.application_id IS '能力所属应用';
COMMENT ON COLUMN auth_governance.capability_state.capability IS '清单内稳定能力';
COMMENT ON COLUMN auth_governance.capability_state.disabled IS '紧急停用状态';
COMMENT ON COLUMN auth_governance.capability_state.version IS '乐观版本';
COMMENT ON TABLE auth_governance.capability_command IS '不可变应用能力变更审计与幂等结果';
COMMENT ON COLUMN auth_governance.capability_command.application_id IS '所属应用';
COMMENT ON COLUMN auth_governance.capability_command.command_id IS '命令UUID';
COMMENT ON COLUMN auth_governance.capability_command.capability IS '目标能力';
COMMENT ON COLUMN auth_governance.capability_command.payload_hash IS '固定命令摘要';
COMMENT ON COLUMN auth_governance.capability_command.actor IS '验证过的应用拥有者';
COMMENT ON COLUMN auth_governance.capability_command.reason IS '停用或恢复原因';
COMMENT ON COLUMN auth_governance.capability_command.previous_version IS '前置版本';
COMMENT ON COLUMN auth_governance.capability_command.result_version IS '结果版本';
COMMENT ON COLUMN auth_governance.capability_command.disabled IS '本次目标状态';
COMMENT ON COLUMN auth_governance.capability_command.created_at IS '提交UTC时刻';
