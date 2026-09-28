ALTER TABLE auth_governance.audit_event
    ADD COLUMN reason varchar(1000),
    ADD COLUMN previous_status varchar(16),
    ADD COLUMN resulting_status varchar(16),
    ADD COLUMN previous_version bigint,
    ADD CONSTRAINT audit_lifecycle_details_ck CHECK (
        (reason IS NULL AND previous_status IS NULL AND resulting_status IS NULL AND previous_version IS NULL)
        OR (reason IS NOT NULL AND length(btrim(reason)) > 0
            AND previous_status IS NOT NULL AND previous_status IN ('ACTIVE', 'SUSPENDED', 'LEFT')
            AND resulting_status IS NOT NULL AND resulting_status IN ('ACTIVE', 'SUSPENDED', 'LEFT')
            AND previous_status <> resulting_status AND previous_version IS NOT NULL
            AND previous_version >= 1 AND target_version = previous_version + 1)
    );
COMMENT ON COLUMN auth_governance.audit_event.reason IS '生命周期操作原因，历史非生命周期记录允许为空，不记录凭据';
COMMENT ON COLUMN auth_governance.audit_event.previous_status IS '生命周期变更前状态，历史记录允许为空';
COMMENT ON COLUMN auth_governance.audit_event.resulting_status IS '生命周期变更后状态，历史记录允许为空';
COMMENT ON COLUMN auth_governance.audit_event.previous_version IS '变更前版本，生命周期新记录与目标版本连续';
COMMENT ON COLUMN auth_governance.audit_event.tenant_id IS '目标企业；全局主体命令使用非 UUID 保留范围 GLOBAL';
COMMENT ON COLUMN auth_governance.command_record.tenant_id IS '目标企业；全局主体命令使用非 UUID 保留范围 GLOBAL';
