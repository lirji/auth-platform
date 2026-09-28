CREATE TABLE auth_governance.projection_stream (
    fence_id varchar(36) PRIMARY KEY,
    policy_id varchar(36) UNIQUE REFERENCES auth_governance.policy_partition(id),
    directory_id varchar(36) UNIQUE REFERENCES auth_governance.directory_fence(id),
    worker_id varchar(36),
    lease_generation bigint NOT NULL DEFAULT 0 CHECK(lease_generation>=0),
    lease_until timestamptz NOT NULL DEFAULT '-infinity',
    target_epoch bigint NOT NULL DEFAULT 0,
    scan_cursor varchar(100) NOT NULL DEFAULT '',
    batch_counter bigint NOT NULL DEFAULT 0 CHECK(batch_counter>=0),
    confirmed_batch bigint NOT NULL DEFAULT 0 CHECK(confirmed_batch>=0 AND confirmed_batch<=batch_counter),
    marker varchar(36),
    zed_token text,
    failures integer NOT NULL DEFAULT 0 CHECK(failures BETWEEN 0 AND 5),
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK(num_nonnulls(policy_id,directory_id)=1 AND fence_id=coalesce(policy_id,directory_id)),
    CHECK((marker IS NULL AND confirmed_batch=0 AND zed_token IS NULL) OR (marker IS NOT NULL AND confirmed_batch>0 AND zed_token IS NOT NULL))
);
COMMENT ON TABLE auth_governance.projection_stream IS '可靠投影分区游标与数据库租约，不替代图CAS';
COMMENT ON COLUMN auth_governance.projection_stream.fence_id IS '所属图marker分区';
COMMENT ON COLUMN auth_governance.projection_stream.policy_id IS '策略栅栏FK，与目录FK二选一';
COMMENT ON COLUMN auth_governance.projection_stream.directory_id IS '目录栅栏FK，与策略FK二选一';
COMMENT ON COLUMN auth_governance.projection_stream.worker_id IS '最近领取进程的不可复用运行标识';
COMMENT ON COLUMN auth_governance.projection_stream.lease_generation IS '递增租约代际，旧执行者不能确认';
COMMENT ON COLUMN auth_governance.projection_stream.lease_until IS '数据库时间租约截止';
COMMENT ON COLUMN auth_governance.projection_stream.target_epoch IS '当前扫描目标版本';
COMMENT ON COLUMN auth_governance.projection_stream.scan_cursor IS '最后确认批次的稳定业务游标';
COMMENT ON COLUMN auth_governance.projection_stream.batch_counter IS '全部目标间单调递增的规划批号';
COMMENT ON COLUMN auth_governance.projection_stream.confirmed_batch IS '最后确认批号，防图回退';
COMMENT ON COLUMN auth_governance.projection_stream.marker IS '最后确认图operation标识';
COMMENT ON COLUMN auth_governance.projection_stream.zed_token IS '最后确认批次不透明水位';
COMMENT ON COLUMN auth_governance.projection_stream.failures IS '连续失败次数，5次后隔离';
COMMENT ON COLUMN auth_governance.projection_stream.next_attempt_at IS '指数退避与抖动后的允许时刻';
COMMENT ON COLUMN auth_governance.projection_stream.updated_at IS '租约或确认最近UTC时刻';
CREATE TABLE auth_governance.projection_operation (
    id varchar(36) PRIMARY KEY,
    fence_id varchar(36) NOT NULL REFERENCES auth_governance.projection_stream(fence_id),
    target_epoch bigint NOT NULL CHECK(target_epoch>0),
    batch_no bigint NOT NULL CHECK(batch_no>0),
    expected_marker varchar(36),
    payload_json text NOT NULL CHECK(octet_length(payload_json)<=262144),
    content_hash varchar(64) NOT NULL CHECK(length(content_hash)=64),
    after_cursor varchar(100) NOT NULL,
    last_batch boolean NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','APPLIED','SUPERSEDED','QUARANTINED')),
    attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
    created_by varchar(36) NOT NULL,
    lease_generation bigint NOT NULL CHECK(lease_generation>0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(fence_id,target_epoch,batch_no),
    UNIQUE(fence_id,batch_no),
    UNIQUE(id,content_hash)
);
COMMENT ON TABLE auth_governance.projection_operation IS '固定目标与payload的可靠远端操作，禁止换marker重发旧内容';
COMMENT ON COLUMN auth_governance.projection_operation.id IS '不可复用的操作及新图marker标识';
COMMENT ON COLUMN auth_governance.projection_operation.fence_id IS '所属持久化分区';
COMMENT ON COLUMN auth_governance.projection_operation.target_epoch IS '本批规划时的权威目标';
COMMENT ON COLUMN auth_governance.projection_operation.batch_no IS '分区内不可复用单调批号';
COMMENT ON COLUMN auth_governance.projection_operation.expected_marker IS '规划时唯一预期图marker，空表示首次初始化';
COMMENT ON COLUMN auth_governance.projection_operation.payload_json IS '规范化固定关系变化及Grant版本快照';
COMMENT ON COLUMN auth_governance.projection_operation.content_hash IS '绑定目标、批号、预期marker、游标和完整payload的SHA256';
COMMENT ON COLUMN auth_governance.projection_operation.after_cursor IS '本批确认后的稳定游标';
COMMENT ON COLUMN auth_governance.projection_operation.last_batch IS '该快照下是否完整扫描末批';
COMMENT ON COLUMN auth_governance.projection_operation.state IS '可靠操作状态，终态不能成为新任务';
COMMENT ON COLUMN auth_governance.projection_operation.attempts IS '远端尝试失败次数';
COMMENT ON COLUMN auth_governance.projection_operation.created_by IS '规划租约的worker运行标识';
COMMENT ON COLUMN auth_governance.projection_operation.lease_generation IS '规划时租约代际，恢复仍需当前有效租约';
COMMENT ON COLUMN auth_governance.projection_operation.created_at IS '操作持久化UTC时刻';
CREATE TABLE auth_governance.projection_receipt (
    operation_id varchar(36) PRIMARY KEY REFERENCES auth_governance.projection_operation(id),
    graph_marker varchar(36) NOT NULL CHECK(graph_marker=operation_id),
    content_hash varchar(64) NOT NULL,
    zed_token text NOT NULL CHECK(length(zed_token) BETWEEN 1 AND 2048),
    confirmed_by varchar(36) NOT NULL,
    confirmed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(operation_id,content_hash) REFERENCES auth_governance.projection_operation(id,content_hash)
);
COMMENT ON TABLE auth_governance.projection_receipt IS '图已提交的持久回执，未知结果通过真实marker恢复';
COMMENT ON COLUMN auth_governance.projection_receipt.operation_id IS '已确认固定操作';
COMMENT ON COLUMN auth_governance.projection_receipt.graph_marker IS '实际观察到的图marker';
COMMENT ON COLUMN auth_governance.projection_receipt.content_hash IS '与不可变操作一致的内容摘要';
COMMENT ON COLUMN auth_governance.projection_receipt.zed_token IS '写入或完全一致读取返回的不透明水位';
COMMENT ON COLUMN auth_governance.projection_receipt.confirmed_by IS '实际补回执或确认的当前worker';
COMMENT ON COLUMN auth_governance.projection_receipt.confirmed_at IS '平台确认UTC时刻，不冒充远端数据库提交时间';

CREATE UNIQUE INDEX projection_one_pending ON auth_governance.projection_operation(fence_id) WHERE state='PENDING';
CREATE INDEX projection_stream_due ON auth_governance.projection_stream(next_attempt_at,lease_until) WHERE failures<5;
CREATE FUNCTION auth_governance.protect_projection_payload() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' OR ROW(NEW.id,NEW.fence_id,NEW.target_epoch,NEW.batch_no,NEW.expected_marker,NEW.payload_json,NEW.content_hash,NEW.after_cursor,NEW.last_batch,NEW.created_by,NEW.lease_generation)
        IS DISTINCT FROM ROW(OLD.id,OLD.fence_id,OLD.target_epoch,OLD.batch_no,OLD.expected_marker,OLD.payload_json,OLD.content_hash,OLD.after_cursor,OLD.last_batch,OLD.created_by,OLD.lease_generation) THEN
        RAISE EXCEPTION 'immutable projection payload';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER immutable_projection_payload BEFORE UPDATE OR DELETE ON auth_governance.projection_operation FOR EACH ROW EXECUTE FUNCTION auth_governance.protect_projection_payload();
CREATE TRIGGER immutable_projection_receipt BEFORE UPDATE OR DELETE ON auth_governance.projection_receipt FOR EACH ROW EXECUTE FUNCTION auth_governance.reject_role_mutation();
