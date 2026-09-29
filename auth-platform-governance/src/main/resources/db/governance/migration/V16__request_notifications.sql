-- 申请事件通知只提示查看权威进度，不将APPROVED渲染为已生效。
CREATE TABLE auth_governance.request_notice_outbox (
 id text PRIMARY KEY DEFAULT gen_random_uuid()::text,
 request_id text NOT NULL REFERENCES auth_governance.access_request(id),
 state_version bigint NOT NULL,
 state text NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','RUNNING','DONE','DEAD')),
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 5),
 lease_token text,
 lease_until timestamptz,
 next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(request_id,state_version)
);
COMMENT ON TABLE auth_governance.request_notice_outbox IS '申请状态通知独立投递意图；发送失败不回退已提交授权';
COMMENT ON COLUMN auth_governance.request_notice_outbox.id IS '稳定通知标识，用于站内去重';
COMMENT ON COLUMN auth_governance.request_notice_outbox.request_id IS '唯一申请引用，不存内部权限清单';
COMMENT ON COLUMN auth_governance.request_notice_outbox.state_version IS '触发通知的申请状态版本';
COMMENT ON COLUMN auth_governance.request_notice_outbox.state IS '有界投递状态';
COMMENT ON COLUMN auth_governance.request_notice_outbox.attempts IS '领取次数上限五次';
COMMENT ON COLUMN auth_governance.request_notice_outbox.lease_token IS '本次领取令牌，防旧执行器写入';
COMMENT ON COLUMN auth_governance.request_notice_outbox.lease_until IS '数据库时钟租约截止';
COMMENT ON COLUMN auth_governance.request_notice_outbox.next_attempt_at IS '指数退避的下次时间';
COMMENT ON COLUMN auth_governance.request_notice_outbox.created_at IS '意图创建时间';
CREATE INDEX request_notice_due ON auth_governance.request_notice_outbox(state,next_attempt_at,id);
CREATE TABLE auth_governance.request_site_notice (
 id text PRIMARY KEY REFERENCES auth_governance.request_notice_outbox(id),
 request_id text NOT NULL REFERENCES auth_governance.access_request(id),
 state_version bigint NOT NULL,
 message_key text NOT NULL CHECK(message_key='REQUEST_PROGRESS_UPDATED'),
 delivered_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
COMMENT ON TABLE auth_governance.request_site_notice IS '站内通知已投递记录；只能通过当前成员代际本人读取';
COMMENT ON COLUMN auth_governance.request_site_notice.id IS '使用意图ID幂等投递';
COMMENT ON COLUMN auth_governance.request_site_notice.request_id IS '跳转到本人申请状态的标识';
COMMENT ON COLUMN auth_governance.request_site_notice.state_version IS '历史变更版本，不代表当前授权生效';
COMMENT ON COLUMN auth_governance.request_site_notice.message_key IS '安全模板：申请进度已更新，请查看详情';
COMMENT ON COLUMN auth_governance.request_site_notice.delivered_at IS '实际站内投递时间';
CREATE INDEX request_site_notice_request ON auth_governance.request_site_notice(request_id,id);
CREATE FUNCTION auth_governance.enqueue_request_notice() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' OR NEW.state_version<>OLD.state_version THEN
  INSERT INTO auth_governance.request_notice_outbox(request_id,state_version) VALUES(NEW.id,NEW.state_version) ON CONFLICT DO NOTHING;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER request_notice_intent AFTER INSERT OR UPDATE ON auth_governance.access_request
 FOR EACH ROW EXECUTE FUNCTION auth_governance.enqueue_request_notice();
