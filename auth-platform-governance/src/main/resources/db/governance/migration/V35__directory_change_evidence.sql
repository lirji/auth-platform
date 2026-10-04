-- 新消费者追加最小前后证据；旧消费者共存时不回填或虚构历史。
CREATE TABLE auth_governance.directory_change_evidence (
 source_id varchar(36) NOT NULL,
 event_id varchar(36) NOT NULL,
 tenant_id varchar(36) NOT NULL,
 partition_sequence bigint NOT NULL CHECK(partition_sequence>0),
 aggregate_type varchar(24) NOT NULL CHECK(aggregate_type IN ('EMPLOYEE','ORG')),
 aggregate_id varchar(160) NOT NULL,
 aggregate_version bigint NOT NULL CHECK(aggregate_version>0),
 event_fingerprint char(64) NOT NULL CHECK(event_fingerprint ~ '^[a-f0-9]{64}$'),
 membership_id varchar(36),
 outcome varchar(24) NOT NULL CHECK(outcome IN ('APPLIED','OBSOLETE')),
 before_json jsonb CHECK(before_json IS NULL OR (jsonb_typeof(before_json)='object' AND octet_length(before_json::text)<=32768)),
 after_json jsonb NOT NULL CHECK(jsonb_typeof(after_json)='object' AND octet_length(after_json::text)<=32768),
 occurred_at timestamptz NOT NULL,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(source_id,event_id),
 UNIQUE(source_id,partition_sequence),
 FOREIGN KEY(source_id,event_id) REFERENCES auth_governance.directory_inbox(source_id,event_id),
 FOREIGN KEY(source_id,tenant_id) REFERENCES auth_governance.directory_source(id,tenant_id),
 FOREIGN KEY(membership_id,tenant_id) REFERENCES auth_governance.membership(id,tenant_id),
 CHECK((aggregate_type='EMPLOYEE' AND membership_id IS NOT NULL) OR (aggregate_type='ORG' AND membership_id IS NULL))
);
COMMENT ON TABLE auth_governance.directory_change_evidence IS '同目录接受事务的不可变必要前后证据，不含登录绑定和原始事件正文';
COMMENT ON COLUMN auth_governance.directory_change_evidence.source_id IS '受控权威来源UUID';
COMMENT ON COLUMN auth_governance.directory_change_evidence.event_id IS '原接受事件UUID，重放不新增';
COMMENT ON COLUMN auth_governance.directory_change_evidence.tenant_id IS '固定企业作用域';
COMMENT ON COLUMN auth_governance.directory_change_evidence.partition_sequence IS '来源分区连续提交序号';
COMMENT ON COLUMN auth_governance.directory_change_evidence.aggregate_type IS '人员或组织聚合稳定类型';
COMMENT ON COLUMN auth_governance.directory_change_evidence.aggregate_id IS '源端人员或组织主键';
COMMENT ON COLUMN auth_governance.directory_change_evidence.aggregate_version IS '事件原聚合版本，可能是过期版本';
COMMENT ON COLUMN auth_governance.directory_change_evidence.event_fingerprint IS '原事件不可变摘要';
COMMENT ON COLUMN auth_governance.directory_change_evidence.membership_id IS '人员当前映射成员，组织为空';
COMMENT ON COLUMN auth_governance.directory_change_evidence.outcome IS 'APPLIED实际更新或OBSOLETE不复活事实';
COMMENT ON COLUMN auth_governance.directory_change_evidence.before_json IS '必要目录和成员前值，首次目录事实为显式空';
COMMENT ON COLUMN auth_governance.directory_change_evidence.after_json IS '接受后必要目录和成员事实，不含登录标识';
COMMENT ON COLUMN auth_governance.directory_change_evidence.occurred_at IS '源事件业务发生时刻';
COMMENT ON COLUMN auth_governance.directory_change_evidence.recorded_at IS '平台接受证据提交时刻';
CREATE INDEX directory_change_member_idx ON auth_governance.directory_change_evidence(tenant_id,membership_id,source_id,partition_sequence);
CREATE INDEX directory_change_aggregate_idx ON auth_governance.directory_change_evidence(source_id,aggregate_type,aggregate_id,partition_sequence);
CREATE TRIGGER immutable_directory_change BEFORE UPDATE OR DELETE ON auth_governance.directory_change_evidence FOR EACH ROW EXECUTE FUNCTION auth_governance.reject_role_mutation();
