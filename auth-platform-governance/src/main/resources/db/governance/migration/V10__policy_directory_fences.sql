CREATE TABLE auth_governance.directory_fence (
    tenant_id varchar(36) PRIMARY KEY REFERENCES auth_governance.tenant(id),
    id varchar(36) NOT NULL UNIQUE DEFAULT gen_random_uuid()::text,
    desired_epoch bigint NOT NULL DEFAULT 1 CHECK(desired_epoch>=0),
    applied_epoch bigint NOT NULL DEFAULT 0 CHECK(applied_epoch>=0 AND applied_epoch<=desired_epoch),
    state varchar(16) NOT NULL DEFAULT 'UPDATING' CHECK(state IN ('READY','UPDATING','BLOCKED')),
    zed_token text CHECK(zed_token IS NULL OR length(zed_token) BETWEEN 1 AND 2048),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK(state<>'READY' OR (desired_epoch=applied_epoch AND zed_token IS NOT NULL))
);
COMMENT ON TABLE auth_governance.directory_fence IS '租户目录授权栅栏，主体成员与目录变化同事务推进';
COMMENT ON COLUMN auth_governance.directory_fence.tenant_id IS '所属企业唯一目录隔离边界';
COMMENT ON COLUMN auth_governance.directory_fence.id IS '图目录marker分区标识';
COMMENT ON COLUMN auth_governance.directory_fence.desired_epoch IS '权威目录目标版本';
COMMENT ON COLUMN auth_governance.directory_fence.applied_epoch IS '图已确认目录版本';
COMMENT ON COLUMN auth_governance.directory_fence.state IS '栅栏状态，未确认不得放行';
COMMENT ON COLUMN auth_governance.directory_fence.zed_token IS '图持久化不透明水位';
COMMENT ON COLUMN auth_governance.directory_fence.updated_at IS '最近事实或确认UTC时刻';
CREATE TABLE auth_governance.policy_partition (
    tenant_id varchar(36) NOT NULL REFERENCES auth_governance.directory_fence(tenant_id),
    application_id varchar(100) NOT NULL,
    environment varchar(40) NOT NULL,
    id varchar(36) NOT NULL UNIQUE DEFAULT gen_random_uuid()::text,
    desired_epoch bigint NOT NULL DEFAULT 1 CHECK(desired_epoch>=0),
    applied_epoch bigint NOT NULL DEFAULT 0 CHECK(applied_epoch>=0 AND applied_epoch<=desired_epoch),
    state varchar(16) NOT NULL DEFAULT 'UPDATING' CHECK(state IN ('READY','UPDATING','BLOCKED')),
    zed_token text CHECK(zed_token IS NULL OR length(zed_token) BETWEEN 1 AND 2048),
    created_by varchar(100) NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(tenant_id,application_id,environment),
    FOREIGN KEY(tenant_id,application_id,environment) REFERENCES auth_governance.tenant_application(tenant_id,application_id,environment),
    CHECK(state<>'READY' OR (desired_epoch=applied_epoch AND zed_token IS NOT NULL))
);
COMMENT ON TABLE auth_governance.policy_partition IS '租户应用环境策略栅栏，记录显式启用后的严格授权版本';
COMMENT ON COLUMN auth_governance.policy_partition.tenant_id IS '所属企业';
COMMENT ON COLUMN auth_governance.policy_partition.application_id IS '所属应用';
COMMENT ON COLUMN auth_governance.policy_partition.environment IS '隔离环境';
COMMENT ON COLUMN auth_governance.policy_partition.id IS '图策略marker分区标识';
COMMENT ON COLUMN auth_governance.policy_partition.desired_epoch IS '权威策略目标版本';
COMMENT ON COLUMN auth_governance.policy_partition.applied_epoch IS '图已确认策略版本';
COMMENT ON COLUMN auth_governance.policy_partition.state IS 'READY必须已确认全部目标';
COMMENT ON COLUMN auth_governance.policy_partition.zed_token IS '图策略不透明水位';
COMMENT ON COLUMN auth_governance.policy_partition.created_by IS '受控启用操作人';
COMMENT ON COLUMN auth_governance.policy_partition.updated_at IS '最近安全事实或确认UTC时刻';

-- 对现有企业增量补充目录栅栏，不改变旧分区的判权所有权。
INSERT INTO auth_governance.directory_fence(tenant_id) SELECT id FROM auth_governance.tenant;
CREATE FUNCTION auth_governance.touch_directory(target varchar) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO auth_governance.directory_fence(tenant_id) VALUES(target)
    ON CONFLICT(tenant_id) DO UPDATE SET desired_epoch=auth_governance.directory_fence.desired_epoch+1,
        state=CASE WHEN auth_governance.directory_fence.state='BLOCKED' THEN 'BLOCKED' ELSE 'UPDATING' END,updated_at=clock_timestamp();
END;
$$;
CREATE FUNCTION auth_governance.directory_row_changed() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target varchar;
BEGIN
    IF TG_TABLE_NAME='tenant' THEN target=NEW.id; ELSE target=NEW.tenant_id; END IF;
    PERFORM auth_governance.touch_directory(target);
    RETURN NEW;
END;
$$;
CREATE TRIGGER tenant_fence AFTER INSERT OR UPDATE ON auth_governance.tenant FOR EACH ROW EXECUTE FUNCTION auth_governance.directory_row_changed();
CREATE TRIGGER membership_fence AFTER INSERT OR UPDATE ON auth_governance.membership FOR EACH ROW EXECUTE FUNCTION auth_governance.directory_row_changed();
CREATE TRIGGER directory_entry_fence AFTER INSERT OR UPDATE ON auth_governance.directory_entry FOR EACH ROW EXECUTE FUNCTION auth_governance.directory_row_changed();
CREATE TRIGGER directory_source_fence AFTER INSERT OR UPDATE ON auth_governance.directory_source FOR EACH ROW EXECUTE FUNCTION auth_governance.directory_row_changed();
CREATE FUNCTION auth_governance.principal_changed() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target varchar;
BEGIN
    FOR target IN SELECT tenant_id FROM auth_governance.membership WHERE principal_id=NEW.id ORDER BY tenant_id LOOP
        PERFORM auth_governance.touch_directory(target);
    END LOOP;
    RETURN NEW;
END;
$$;
CREATE TRIGGER principal_fence AFTER UPDATE ON auth_governance.principal FOR EACH ROW EXECUTE FUNCTION auth_governance.principal_changed();
CREATE FUNCTION auth_governance.policy_row_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE auth_governance.policy_partition SET desired_epoch=desired_epoch+1,
        state=CASE WHEN state='BLOCKED' THEN 'BLOCKED' ELSE 'UPDATING' END,updated_at=clock_timestamp()
    WHERE tenant_id=NEW.tenant_id AND application_id=NEW.application_id AND environment=NEW.environment;
    RETURN NEW;
END;
$$;
CREATE TRIGGER grant_fence AFTER INSERT OR UPDATE OF version ON auth_governance.access_grant FOR EACH ROW EXECUTE FUNCTION auth_governance.policy_row_changed();
CREATE TRIGGER admission_fence AFTER UPDATE ON auth_governance.tenant_application FOR EACH ROW EXECUTE FUNCTION auth_governance.policy_row_changed();
CREATE TRIGGER delegation_fence AFTER INSERT OR UPDATE ON auth_governance.access_delegation FOR EACH ROW EXECUTE FUNCTION auth_governance.policy_row_changed();
CREATE FUNCTION auth_governance.catalog_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE auth_governance.policy_partition SET desired_epoch=desired_epoch+1,
        state=CASE WHEN state='BLOCKED' THEN 'BLOCKED' ELSE 'UPDATING' END,updated_at=clock_timestamp()
    WHERE application_id=NEW.application_id;
    RETURN NEW;
END;
$$;
CREATE TRIGGER catalog_fence AFTER UPDATE OF manifest_version ON auth_governance.application_catalog FOR EACH ROW EXECUTE FUNCTION auth_governance.catalog_changed();
