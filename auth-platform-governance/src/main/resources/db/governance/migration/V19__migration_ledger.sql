CREATE TABLE auth_governance.migration_record (
 unit_id varchar(100) NOT NULL,
 source_id varchar(100) NOT NULL,
 sequence bigint NOT NULL CHECK(sequence > 0),
 source_version bigint NOT NULL CHECK(source_version >= 0),
 fingerprint varchar(64) NOT NULL,
 snapshot_hash varchar(64) NOT NULL,
 mapping_hash varchar(64) NOT NULL,
 tenant_id varchar(36) NOT NULL,
 application_id varchar(100) NOT NULL,
 environment varchar(40) NOT NULL,
 disposition varchar(32) NOT NULL CHECK(disposition IN ('IMPORTED','PRESERVE_DENIAL')),
 grant_id varchar(36) REFERENCES auth_governance.access_grant(id),
 tombstone boolean NOT NULL,
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(unit_id,source_id),
 CHECK(NOT tombstone OR disposition='PRESERVE_DENIAL')
);
COMMENT ON TABLE auth_governance.migration_record IS '按迁移单元及旧来源保持持久检查点；拒绝墓碑不能被后续快照复活';
COMMENT ON COLUMN auth_governance.migration_record.unit_id IS '已固定迁移单元稳定ID';
COMMENT ON COLUMN auth_governance.migration_record.source_id IS '旧系统授权来源ID';
COMMENT ON COLUMN auth_governance.migration_record.sequence IS '完整快照或有序增量序号，不能用时间戳猜顺序';
COMMENT ON COLUMN auth_governance.migration_record.source_version IS '旧授权原始版本，不能倒退';
COMMENT ON COLUMN auth_governance.migration_record.fingerprint IS '本记录完整导入语义摘要';
COMMENT ON COLUMN auth_governance.migration_record.snapshot_hash IS '核验过的来源快照SHA256';
COMMENT ON COLUMN auth_governance.migration_record.mapping_hash IS '显式身份及能力映射SHA256';
COMMENT ON COLUMN auth_governance.migration_record.tenant_id IS '唯一目标治理租户';
COMMENT ON COLUMN auth_governance.migration_record.application_id IS '唯一目标应用';
COMMENT ON COLUMN auth_governance.migration_record.environment IS '唯一目标环境';
COMMENT ON COLUMN auth_governance.migration_record.disposition IS '已导入或保留拒绝，不代表图已确认';
COMMENT ON COLUMN auth_governance.migration_record.grant_id IS '通过正式授权用例创建的Grant，可空表示从未放行';
COMMENT ON COLUMN auth_governance.migration_record.tombstone IS '撤销、到期或不可用来源的单向拒绝墓碑';
COMMENT ON COLUMN auth_governance.migration_record.updated_at IS '主库检查点更新时间';
CREATE INDEX migration_partition_idx ON auth_governance.migration_record(tenant_id,application_id,environment,unit_id);
