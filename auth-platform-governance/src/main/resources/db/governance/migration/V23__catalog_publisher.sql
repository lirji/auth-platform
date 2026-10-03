-- 追加受限SERVICE发布关系；不改原Manifest、角色或Grant，不自动初始化任何实例目标。
ALTER TABLE auth_governance.principal ADD CONSTRAINT publisher_principal_kind_key UNIQUE(id,kind);
ALTER TABLE auth_governance.login_identity ADD CONSTRAINT publisher_identity_principal_key UNIQUE(issuer,subject,principal_id);
ALTER TABLE auth_governance.catalog_release_preview ADD CONSTRAINT publisher_preview_owner_key UNIQUE(id,application_id,owner_principal);
ALTER TABLE auth_governance.catalog_release ADD CONSTRAINT publisher_release_actor_key UNIQUE(application_id,version,published_by);
COMMENT ON COLUMN auth_governance.application_manifest.published_by IS '实际已认证发布主体，HUMAN Owner或独立受限SERVICE';

CREATE TABLE auth_governance.catalog_publisher_target (
    singleton smallint NOT NULL DEFAULT 1 CHECK(singleton=1),
    target_instance_id varchar(36) NOT NULL,
    environment varchar(100) NOT NULL,
    created_by varchar(100) NOT NULL,
    reason varchar(500) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(singleton),
    UNIQUE(target_instance_id),
    UNIQUE(target_instance_id,environment)
);
COMMENT ON TABLE auth_governance.catalog_publisher_target IS '单环境实例固定发布目标，受控初始化且不可重绑定';
COMMENT ON COLUMN auth_governance.catalog_publisher_target.singleton IS '实例仅有一条目标记录';
COMMENT ON COLUMN auth_governance.catalog_publisher_target.target_instance_id IS '受控实例UUID，不从发布请求选择数据库';
COMMENT ON COLUMN auth_governance.catalog_publisher_target.environment IS '当前实例唯一发布环境';
COMMENT ON COLUMN auth_governance.catalog_publisher_target.created_by IS '受控初始化操作员';
COMMENT ON COLUMN auth_governance.catalog_publisher_target.reason IS '目标绑定原因';
COMMENT ON COLUMN auth_governance.catalog_publisher_target.created_at IS 'PG初始化时点';

CREATE TABLE auth_governance.catalog_publisher_delegation (
    id varchar(36) NOT NULL,
    publisher_id varchar(36) NOT NULL,
    application_id varchar(100) NOT NULL REFERENCES auth_governance.application_catalog(application_id),
    target_instance_id varchar(36) NOT NULL,
    environment varchar(100) NOT NULL,
    service_principal varchar(36) NOT NULL,
    service_kind varchar(20) NOT NULL DEFAULT 'SERVICE' CHECK(service_kind='SERVICE'),
    owner_principal varchar(36) NOT NULL,
    owner_kind varchar(20) NOT NULL DEFAULT 'HUMAN' CHECK(owner_kind='HUMAN'),
    issuer varchar(500) NOT NULL,
    subject varchar(500) NOT NULL,
    client_id varchar(160) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED')),
    version bigint NOT NULL DEFAULT 1,
    valid_until timestamptz NOT NULL,
    command_id varchar(36) NOT NULL,
    reason varchar(500) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    disabled_at timestamptz,
    PRIMARY KEY(id),
    UNIQUE(publisher_id),
    UNIQUE(application_id,command_id),
    UNIQUE(id,application_id,target_instance_id,environment,service_principal,owner_principal),
    FOREIGN KEY(target_instance_id,environment) REFERENCES auth_governance.catalog_publisher_target(target_instance_id,environment),
    FOREIGN KEY(service_principal,service_kind) REFERENCES auth_governance.principal(id,kind),
    FOREIGN KEY(owner_principal,owner_kind) REFERENCES auth_governance.principal(id,kind),
    FOREIGN KEY(issuer,subject,service_principal) REFERENCES auth_governance.login_identity(issuer,subject,principal_id),
    CHECK(valid_until>created_at AND valid_until<=created_at+interval '30 days'),
    CHECK((status='ACTIVE' AND version=1 AND disabled_at IS NULL) OR (status='DISABLED' AND version=2 AND disabled_at IS NOT NULL AND disabled_at>=created_at))
);
COMMENT ON TABLE auth_governance.catalog_publisher_delegation IS 'HUMAN Owner对固定SERVICE机器客户端的有限期发布委派';
COMMENT ON COLUMN auth_governance.catalog_publisher_delegation.id IS '不可复用委派UUID';
COMMENT ON COLUMN auth_governance.catalog_publisher_delegation.publisher_id IS '后端配置中唯一发布slot';
COMMENT ON COLUMN auth_governance.catalog_publisher_delegation.application_id IS '固定可发布应用';
COMMENT ON COLUMN auth_governance.catalog_publisher_delegation.target_instance_id IS '固定实例目标UUID';
COMMENT ON COLUMN auth_governance.catalog_publisher_delegation.environment IS '固定实例环境';
COMMENT ON COLUMN auth_governance.catalog_publisher_delegation.service_principal IS '真实SERVICE发布主体';
COMMENT ON COLUMN auth_governance.catalog_publisher_delegation.service_kind IS '防止HUMAN被登记成机器主体';
COMMENT ON COLUMN auth_governance.catalog_publisher_delegation.owner_principal IS '建立委派的真实HUMAN Owner';
COMMENT ON COLUMN auth_governance.catalog_publisher_delegation.owner_kind IS '防止机器被登记成Owner';
COMMENT ON COLUMN auth_governance.catalog_publisher_delegation.issuer IS '固定客户端发行方';
COMMENT ON COLUMN auth_governance.catalog_publisher_delegation.subject IS '固定Casdoor应用subject';
COMMENT ON COLUMN auth_governance.catalog_publisher_delegation.client_id IS '专用客户端及受众';
COMMENT ON COLUMN auth_governance.catalog_publisher_delegation.status IS '单向委派状态，到期另按valid_until核验';
COMMENT ON COLUMN auth_governance.catalog_publisher_delegation.version IS 'ACTIVE版本1，禁用版本2';
COMMENT ON COLUMN auth_governance.catalog_publisher_delegation.valid_until IS '有期限委派，最长30天';
COMMENT ON COLUMN auth_governance.catalog_publisher_delegation.command_id IS '原创建命令UUID';
COMMENT ON COLUMN auth_governance.catalog_publisher_delegation.reason IS '原创建委派原因';
COMMENT ON COLUMN auth_governance.catalog_publisher_delegation.created_at IS 'PG建立时点';
COMMENT ON COLUMN auth_governance.catalog_publisher_delegation.disabled_at IS 'PG禁用时点';

CREATE TABLE auth_governance.catalog_publisher_event (
    application_id varchar(100) NOT NULL,
    command_id varchar(36) NOT NULL,
    delegation_id varchar(36) NOT NULL REFERENCES auth_governance.catalog_publisher_delegation(id),
    operator_principal varchar(36) NOT NULL REFERENCES auth_governance.principal(id),
    operation varchar(20) NOT NULL CHECK(operation IN ('CREATE','DISABLE')),
    payload_hash varchar(64) NOT NULL CHECK(payload_hash ~ '^[a-f0-9]{64}$'),
    previous_version bigint NOT NULL CHECK(previous_version>=0),
    resulting_version bigint NOT NULL CHECK(resulting_version IN (1,2)),
    reason varchar(500) NOT NULL,
    result_json text NOT NULL CHECK(octet_length(result_json)<=8192 AND jsonb_typeof(result_json::jsonb)='object'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(application_id,command_id),
    CHECK((operation='CREATE' AND previous_version=0 AND resulting_version=1) OR (operation='DISABLE' AND previous_version=1 AND resulting_version=2))
);
COMMENT ON TABLE auth_governance.catalog_publisher_event IS '不可变建立和禁用委派事件及原命令回执';
COMMENT ON COLUMN auth_governance.catalog_publisher_event.application_id IS '原命令应用作用域';
COMMENT ON COLUMN auth_governance.catalog_publisher_event.command_id IS '建立或禁用原命令UUID';
COMMENT ON COLUMN auth_governance.catalog_publisher_event.delegation_id IS '对应固定委派';
COMMENT ON COLUMN auth_governance.catalog_publisher_event.operator_principal IS '真实已认证HUMAN操作主体';
COMMENT ON COLUMN auth_governance.catalog_publisher_event.operation IS '显式委派操作类型';
COMMENT ON COLUMN auth_governance.catalog_publisher_event.payload_hash IS '规范化完整操作摘要';
COMMENT ON COLUMN auth_governance.catalog_publisher_event.previous_version IS '操作前版本，建立为0';
COMMENT ON COLUMN auth_governance.catalog_publisher_event.resulting_version IS '操作后版本';
COMMENT ON COLUMN auth_governance.catalog_publisher_event.reason IS '该命令的业务原因';
COMMENT ON COLUMN auth_governance.catalog_publisher_event.result_json IS '固定原命令回执，不把后续状态改回原状态';
COMMENT ON COLUMN auth_governance.catalog_publisher_event.created_at IS 'PG事件时点';

CREATE TABLE auth_governance.catalog_publisher_preview (
    preview_id varchar(36) NOT NULL,
    delegation_id varchar(36) NOT NULL,
    application_id varchar(100) NOT NULL,
    target_instance_id varchar(36) NOT NULL,
    environment varchar(100) NOT NULL,
    service_principal varchar(36) NOT NULL,
    owner_principal varchar(36) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(preview_id),
    FOREIGN KEY(preview_id,application_id,owner_principal) REFERENCES auth_governance.catalog_release_preview(id,application_id,owner_principal),
    FOREIGN KEY(delegation_id,application_id,target_instance_id,environment,service_principal,owner_principal) REFERENCES auth_governance.catalog_publisher_delegation(id,application_id,target_instance_id,environment,service_principal,owner_principal)
);
COMMENT ON TABLE auth_governance.catalog_publisher_preview IS '固定MG05机器票据与委派关系，HUMAN票据不能冒用';
COMMENT ON COLUMN auth_governance.catalog_publisher_preview.preview_id IS '固定MG05票据UUID';
COMMENT ON COLUMN auth_governance.catalog_publisher_preview.delegation_id IS '唯一产生票据的委派';
COMMENT ON COLUMN auth_governance.catalog_publisher_preview.application_id IS '固定应用';
COMMENT ON COLUMN auth_governance.catalog_publisher_preview.target_instance_id IS '固定实例';
COMMENT ON COLUMN auth_governance.catalog_publisher_preview.environment IS '固定环境';
COMMENT ON COLUMN auth_governance.catalog_publisher_preview.service_principal IS '实际机器主体';
COMMENT ON COLUMN auth_governance.catalog_publisher_preview.owner_principal IS '固定票据HUMAN Owner';
COMMENT ON COLUMN auth_governance.catalog_publisher_preview.created_at IS 'PG关系建立时点';

CREATE TABLE auth_governance.catalog_publisher_release (
    application_id varchar(100) NOT NULL,
    version bigint NOT NULL,
    delegation_id varchar(36) NOT NULL,
    target_instance_id varchar(36) NOT NULL,
    environment varchar(100) NOT NULL,
    service_principal varchar(36) NOT NULL,
    owner_principal varchar(36) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(application_id,version),
    FOREIGN KEY(application_id,version,service_principal) REFERENCES auth_governance.catalog_release(application_id,version,published_by),
    FOREIGN KEY(delegation_id,application_id,target_instance_id,environment,service_principal,owner_principal) REFERENCES auth_governance.catalog_publisher_delegation(id,application_id,target_instance_id,environment,service_principal,owner_principal)
);
COMMENT ON TABLE auth_governance.catalog_publisher_release IS '与目录发布同事务固定的实际SERVICE演员和HUMAN委派依据';
COMMENT ON COLUMN auth_governance.catalog_publisher_release.application_id IS '固定应用';
COMMENT ON COLUMN auth_governance.catalog_publisher_release.version IS '已提交不可变目录版本';
COMMENT ON COLUMN auth_governance.catalog_publisher_release.delegation_id IS '原发布有效委派';
COMMENT ON COLUMN auth_governance.catalog_publisher_release.target_instance_id IS '原发布实例';
COMMENT ON COLUMN auth_governance.catalog_publisher_release.environment IS '原发布环境';
COMMENT ON COLUMN auth_governance.catalog_publisher_release.service_principal IS '真实published_by机器主体';
COMMENT ON COLUMN auth_governance.catalog_publisher_release.owner_principal IS '委派关系中真实HUMAN Owner';
COMMENT ON COLUMN auth_governance.catalog_publisher_release.created_at IS 'PG同事务发布时点';

CREATE FUNCTION auth_governance.guard_publisher_delegation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'publisher delegation is immutable'; END IF;
    IF OLD.status<>'ACTIVE' OR NEW.status<>'DISABLED' OR OLD.version<>1 OR NEW.version<>2 OR NEW.disabled_at IS NULL
       OR ROW(NEW.id,NEW.publisher_id,NEW.application_id,NEW.target_instance_id,NEW.environment,NEW.service_principal,NEW.service_kind,
              NEW.owner_principal,NEW.owner_kind,NEW.issuer,NEW.subject,NEW.client_id,NEW.valid_until,NEW.command_id,NEW.reason,NEW.created_at)
          IS DISTINCT FROM
          ROW(OLD.id,OLD.publisher_id,OLD.application_id,OLD.target_instance_id,OLD.environment,OLD.service_principal,OLD.service_kind,
              OLD.owner_principal,OLD.owner_kind,OLD.issuer,OLD.subject,OLD.client_id,OLD.valid_until,OLD.command_id,OLD.reason,OLD.created_at)
    THEN RAISE EXCEPTION 'publisher delegation allows only disabling'; END IF;
    RETURN NEW;
END;
$$;
COMMENT ON FUNCTION auth_governance.guard_publisher_delegation() IS '委派只允许ACTIVE到DISABLED，身份目标期限均不可重写';
CREATE TRIGGER publisher_delegation_guard BEFORE UPDATE OR DELETE ON auth_governance.catalog_publisher_delegation FOR EACH ROW EXECUTE FUNCTION auth_governance.guard_publisher_delegation();
CREATE INDEX publisher_delegation_application_id_idx ON auth_governance.catalog_publisher_delegation(application_id,id);
CREATE INDEX publisher_preview_delegation_id_idx ON auth_governance.catalog_publisher_preview(delegation_id,preview_id);

CREATE TRIGGER publisher_target_immutable BEFORE UPDATE OR DELETE ON auth_governance.catalog_publisher_target FOR EACH ROW EXECUTE FUNCTION auth_governance.reject_catalog_release_mutation();

CREATE TRIGGER publisher_event_immutable BEFORE UPDATE OR DELETE ON auth_governance.catalog_publisher_event FOR EACH ROW EXECUTE FUNCTION auth_governance.reject_catalog_release_mutation();

CREATE TRIGGER publisher_preview_immutable BEFORE UPDATE OR DELETE ON auth_governance.catalog_publisher_preview FOR EACH ROW EXECUTE FUNCTION auth_governance.reject_catalog_release_mutation();

CREATE TRIGGER publisher_release_immutable BEFORE UPDATE OR DELETE ON auth_governance.catalog_publisher_release FOR EACH ROW EXECUTE FUNCTION auth_governance.reject_catalog_release_mutation();
