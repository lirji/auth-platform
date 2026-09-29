CREATE TABLE auth_governance.approval_inbox (
    producer varchar(40) NOT NULL,
    event_id varchar(36) NOT NULL,
    tenant_id varchar(36) NOT NULL,
    application_id varchar(100) NOT NULL,
    environment varchar(40) NOT NULL,
    request_id varchar(36) NOT NULL,
    payload_hash varchar(64) NOT NULL,
    payload_json text NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'RECEIVED' CHECK(status IN ('RECEIVED','APPLIED','REJECTED','IGNORED')),
    result varchar(80),
    received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    processed_at timestamptz,
    PRIMARY KEY(producer,event_id)
);
COMMENT ON TABLE auth_governance.approval_inbox IS '可信审批事件Inbox；消费与Grant副作用同事务';
COMMENT ON COLUMN auth_governance.approval_inbox.producer IS '可信生产者';
COMMENT ON COLUMN auth_governance.approval_inbox.event_id IS '稳定业务事件ID';
COMMENT ON COLUMN auth_governance.approval_inbox.tenant_id IS '目标企业';
COMMENT ON COLUMN auth_governance.approval_inbox.application_id IS '目标应用';
COMMENT ON COLUMN auth_governance.approval_inbox.environment IS '目标环境';
COMMENT ON COLUMN auth_governance.approval_inbox.request_id IS '固定申请引用';
COMMENT ON COLUMN auth_governance.approval_inbox.payload_hash IS '原始传输体SHA256';
COMMENT ON COLUMN auth_governance.approval_inbox.payload_json IS '已验签原始消息';
COMMENT ON COLUMN auth_governance.approval_inbox.status IS '消费状态；接收不等于应用';
COMMENT ON COLUMN auth_governance.approval_inbox.result IS '稳定处理结果';
COMMENT ON COLUMN auth_governance.approval_inbox.received_at IS '接收UTC时间';
COMMENT ON COLUMN auth_governance.approval_inbox.processed_at IS '处理UTC时间';
CREATE INDEX approval_inbox_pending_idx ON auth_governance.approval_inbox(tenant_id,application_id,environment,event_id) WHERE status='RECEIVED';
CREATE TABLE auth_governance.approval_conflict (
    producer varchar(40) NOT NULL,
    event_id varchar(36) NOT NULL,
    payload_hash varchar(64) NOT NULL,
    original_hash varchar(64) NOT NULL,
    received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(producer,event_id,payload_hash),
    FOREIGN KEY(producer,event_id) REFERENCES auth_governance.approval_inbox(producer,event_id)
);
COMMENT ON TABLE auth_governance.approval_conflict IS '同事件ID改体冲突隔离证据，不被错误响应回滚';
COMMENT ON COLUMN auth_governance.approval_conflict.producer IS '可信生产者';
COMMENT ON COLUMN auth_governance.approval_conflict.event_id IS '冲突业务事件ID';
COMMENT ON COLUMN auth_governance.approval_conflict.payload_hash IS '被隔离消息摘要';
COMMENT ON COLUMN auth_governance.approval_conflict.original_hash IS '原已受理消息摘要';
COMMENT ON COLUMN auth_governance.approval_conflict.received_at IS '冲突接收UTC';
CREATE TABLE auth_governance.approval_nonce (
    producer varchar(40) NOT NULL,
    nonce varchar(36) NOT NULL,
    received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(producer,nonce)
);
COMMENT ON TABLE auth_governance.approval_nonce IS '有效签名的传输重放屏障';
COMMENT ON COLUMN auth_governance.approval_nonce.producer IS '可信生产者';
COMMENT ON COLUMN auth_governance.approval_nonce.nonce IS '唯一发送尝试';
COMMENT ON COLUMN auth_governance.approval_nonce.received_at IS '接收UTC时刻';
