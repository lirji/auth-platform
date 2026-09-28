CREATE TABLE auth_governance.directory_source (
    id varchar(36) PRIMARY KEY,
    source varchar(100) NOT NULL,
    environment varchar(40) NOT NULL,
    source_tenant_ref varchar(160) NOT NULL,
    tenant_id varchar(36) NOT NULL UNIQUE REFERENCES auth_governance.tenant(id),
    issuer varchar(500) NOT NULL,
    last_sequence bigint NOT NULL DEFAULT 0 CHECK (last_sequence >= 0),
    last_fingerprint char(64),
    quarantined boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (source, environment, source_tenant_ref),
    UNIQUE (id, tenant_id),
    CHECK ((last_sequence = 0 AND last_fingerprint IS NULL) OR (last_sequence > 0 AND last_fingerprint IS NOT NULL AND last_fingerprint ~ '^[a-f0-9]{64}$'))
);
COMMENT ON TABLE auth_governance.directory_source IS '唯一权威目录来源与已连续提交检查点';
COMMENT ON COLUMN auth_governance.directory_source.id IS '受控来源登记 UUID';
COMMENT ON COLUMN auth_governance.directory_source.source IS '来源系统稳定代码';
COMMENT ON COLUMN auth_governance.directory_source.environment IS '来源环境，不跨环境复用游标';
COMMENT ON COLUMN auth_governance.directory_source.source_tenant_ref IS 'OA 企业精确主键';
COMMENT ON COLUMN auth_governance.directory_source.tenant_id IS '固定治理企业，唯一目录写入权威';
COMMENT ON COLUMN auth_governance.directory_source.issuer IS '精确登录发行方，不由事件选择';
COMMENT ON COLUMN auth_governance.directory_source.last_sequence IS '已完成连续分区序号';
COMMENT ON COLUMN auth_governance.directory_source.last_fingerprint IS '已连续提交最后事件摘要，零检查点时为空';
COMMENT ON COLUMN auth_governance.directory_source.quarantined IS '契约或身份冲突后停止推进';
COMMENT ON COLUMN auth_governance.directory_source.created_at IS '登记时刻';

CREATE TABLE auth_governance.directory_inbox (
    source_id varchar(36) NOT NULL REFERENCES auth_governance.directory_source(id),
    event_id varchar(36) NOT NULL,
    partition_sequence bigint NOT NULL CHECK (partition_sequence >= 1),
    aggregate_type varchar(24) NOT NULL CHECK (aggregate_type IN ('EMPLOYEE','ORG','SNAPSHOT_BEGIN','SNAPSHOT_END')),
    aggregate_id varchar(160) NOT NULL,
    aggregate_version bigint NOT NULL CHECK (aggregate_version >= 1),
    payload_hash char(64) NOT NULL CHECK (payload_hash ~ '^[a-f0-9]{64}$'),
    fingerprint char(64) NOT NULL CHECK (fingerprint ~ '^[a-f0-9]{64}$'),
    snapshot_id varchar(36),
    processed boolean NOT NULL,
    received_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (source_id, event_id),
    UNIQUE (source_id, partition_sequence),
    CHECK (processed OR aggregate_type = 'SNAPSHOT_END')
);
COMMENT ON TABLE auth_governance.directory_inbox IS '与目录副作用同事务提交的事件回执';
COMMENT ON COLUMN auth_governance.directory_inbox.source_id IS '固定来源登记';
COMMENT ON COLUMN auth_governance.directory_inbox.event_id IS '源端稳定事件 UUID';
COMMENT ON COLUMN auth_governance.directory_inbox.partition_sequence IS '可靠分区提交序号';
COMMENT ON COLUMN auth_governance.directory_inbox.aggregate_type IS '聚合或快照控制类型';
COMMENT ON COLUMN auth_governance.directory_inbox.aggregate_id IS '源聚合主键';
COMMENT ON COLUMN auth_governance.directory_inbox.aggregate_version IS '源聚合单调版本';
COMMENT ON COLUMN auth_governance.directory_inbox.payload_hash IS '业务事实摘要';
COMMENT ON COLUMN auth_governance.directory_inbox.fingerprint IS '绑定完整事件元数据的摘要';
COMMENT ON COLUMN auth_governance.directory_inbox.snapshot_id IS '初始化快照批次，普通增量为空';
COMMENT ON COLUMN auth_governance.directory_inbox.processed IS '副作用已完成，未核验 END 保持 false';
COMMENT ON COLUMN auth_governance.directory_inbox.received_at IS '首次提交接收时刻';

CREATE TABLE auth_governance.directory_version_receipt (
    source_id varchar(36) NOT NULL REFERENCES auth_governance.directory_source(id),
    aggregate_type varchar(24) NOT NULL CHECK (aggregate_type IN ('EMPLOYEE','ORG','SNAPSHOT_BEGIN','SNAPSHOT_END')),
    aggregate_id varchar(160) NOT NULL,
    aggregate_version bigint NOT NULL CHECK (aggregate_version >= 1),
    payload_hash char(64) NOT NULL CHECK (payload_hash ~ '^[a-f0-9]{64}$'),
    PRIMARY KEY (source_id, aggregate_type, aggregate_id, aggregate_version)
);
COMMENT ON TABLE auth_governance.directory_version_receipt IS '保留各聚合版本摘要以发现旧版本异内容重放';
COMMENT ON COLUMN auth_governance.directory_version_receipt.source_id IS '来源登记';
COMMENT ON COLUMN auth_governance.directory_version_receipt.aggregate_type IS '稳定聚合类型';
COMMENT ON COLUMN auth_governance.directory_version_receipt.aggregate_id IS '源聚合主键';
COMMENT ON COLUMN auth_governance.directory_version_receipt.aggregate_version IS '源聚合版本';
COMMENT ON COLUMN auth_governance.directory_version_receipt.payload_hash IS '该版本唯一业务摘要';

CREATE TABLE auth_governance.directory_entry (
    source_id varchar(36) NOT NULL,
    tenant_id varchar(36) NOT NULL,
    aggregate_type varchar(24) NOT NULL CHECK (aggregate_type IN ('EMPLOYEE','ORG')),
    aggregate_id varchar(160) NOT NULL,
    aggregate_version bigint NOT NULL CHECK (aggregate_version >= 1),
    payload_hash char(64) NOT NULL CHECK (payload_hash ~ '^[a-f0-9]{64}$'),
    payload_json jsonb NOT NULL CHECK (jsonb_typeof(payload_json) = 'object' AND octet_length(payload_json::text) <= 65536),
    principal_id varchar(36),
    membership_id varchar(36),
    login_subject varchar(500),
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (source_id, aggregate_type, aggregate_id),
    UNIQUE (source_id, membership_id),
    FOREIGN KEY (source_id, tenant_id) REFERENCES auth_governance.directory_source(id, tenant_id),
    FOREIGN KEY (membership_id, principal_id, tenant_id) REFERENCES auth_governance.membership(id, principal_id, tenant_id),
    CHECK ((aggregate_type = 'EMPLOYEE' AND principal_id IS NOT NULL AND membership_id IS NOT NULL) OR (aggregate_type = 'ORG' AND principal_id IS NULL AND membership_id IS NULL AND login_subject IS NULL))
);
COMMENT ON TABLE auth_governance.directory_entry IS '员工和直接组织事实投影，不生成角色或图授权';
COMMENT ON COLUMN auth_governance.directory_entry.source_id IS '来源登记';
COMMENT ON COLUMN auth_governance.directory_entry.tenant_id IS '固定治理企业';
COMMENT ON COLUMN auth_governance.directory_entry.aggregate_type IS '目录聚合类型';
COMMENT ON COLUMN auth_governance.directory_entry.aggregate_id IS 'OA 员工或组织主键';
COMMENT ON COLUMN auth_governance.directory_entry.aggregate_version IS '当前源事实版本';
COMMENT ON COLUMN auth_governance.directory_entry.payload_hash IS '当前事实摘要';
COMMENT ON COLUMN auth_governance.directory_entry.payload_json IS '有界类型化最小投影，直接关系及日期不推导业务权限';
COMMENT ON COLUMN auth_governance.directory_entry.principal_id IS '员工映射主体，组织为空';
COMMENT ON COLUMN auth_governance.directory_entry.membership_id IS '员工映射成员，组织为空';
COMMENT ON COLUMN auth_governance.directory_entry.login_subject IS '首次精确登录标识，缺失时不猜测';
COMMENT ON COLUMN auth_governance.directory_entry.updated_at IS '本次投影提交时刻';

CREATE TABLE auth_governance.directory_snapshot (
    source_id varchar(36) NOT NULL REFERENCES auth_governance.directory_source(id),
    snapshot_id varchar(36) NOT NULL,
    start_sequence bigint NOT NULL CHECK (start_sequence >= 1),
    end_sequence bigint NOT NULL,
    expected_count bigint NOT NULL CHECK (expected_count >= 0 AND expected_count <= 10000),
    content_hash char(64) NOT NULL CHECK (content_hash ~ '^[a-f0-9]{64}$'),
    begin_seen boolean NOT NULL,
    end_seen boolean NOT NULL,
    complete boolean NOT NULL DEFAULT false,
    PRIMARY KEY (source_id, snapshot_id),
    CHECK (end_sequence > start_sequence AND expected_count = end_sequence - start_sequence - 1),
    CHECK (NOT complete OR (begin_seen AND end_seen))
);
COMMENT ON TABLE auth_governance.directory_snapshot IS '来源初始化完整性声明与验证结果，不按缺失删除成员';
COMMENT ON COLUMN auth_governance.directory_snapshot.source_id IS '来源登记';
COMMENT ON COLUMN auth_governance.directory_snapshot.snapshot_id IS '封存快照 UUID';
COMMENT ON COLUMN auth_governance.directory_snapshot.start_sequence IS 'BEGIN 序号';
COMMENT ON COLUMN auth_governance.directory_snapshot.end_sequence IS 'END 序号';
COMMENT ON COLUMN auth_governance.directory_snapshot.expected_count IS '业务事件声明数量，有界初始化';
COMMENT ON COLUMN auth_governance.directory_snapshot.content_hash IS '按序连接业务事件摘要后的 SHA-256';
COMMENT ON COLUMN auth_governance.directory_snapshot.begin_seen IS 'BEGIN 已提交';
COMMENT ON COLUMN auth_governance.directory_snapshot.end_seen IS 'END 已提交';
COMMENT ON COLUMN auth_governance.directory_snapshot.complete IS '边界、数量与实际摘要都已验证';

CREATE TABLE auth_governance.directory_conflict (
    source_id varchar(36) NOT NULL REFERENCES auth_governance.directory_source(id),
    event_id varchar(36) NOT NULL,
    fingerprint char(64) NOT NULL CHECK (fingerprint ~ '^[a-f0-9]{64}$'),
    reason varchar(64) NOT NULL,
    first_seen timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    occurrences bigint NOT NULL DEFAULT 1 CHECK (occurrences >= 1),
    PRIMARY KEY (source_id, event_id, fingerprint, reason)
);
COMMENT ON TABLE auth_governance.directory_conflict IS '隔离冲突证据，业务事务回滚后独立提交，不保存原始凭据';
COMMENT ON COLUMN auth_governance.directory_conflict.source_id IS '来源登记';
COMMENT ON COLUMN auth_governance.directory_conflict.event_id IS '冲突事件 ID';
COMMENT ON COLUMN auth_governance.directory_conflict.fingerprint IS '冲突输入摘要';
COMMENT ON COLUMN auth_governance.directory_conflict.reason IS '稳定冲突原因，不拼接异常原文';
COMMENT ON COLUMN auth_governance.directory_conflict.first_seen IS '首次冲突时刻';
COMMENT ON COLUMN auth_governance.directory_conflict.last_seen IS '最近重试时刻';
COMMENT ON COLUMN auth_governance.directory_conflict.occurrences IS '相同冲突重试次数';

CREATE INDEX directory_inbox_snapshot_idx ON auth_governance.directory_inbox(source_id, snapshot_id, partition_sequence);
CREATE INDEX directory_entry_parent_idx ON auth_governance.directory_entry(source_id, ((payload_json->'organization')->>'parent_id')) WHERE aggregate_type = 'ORG';
