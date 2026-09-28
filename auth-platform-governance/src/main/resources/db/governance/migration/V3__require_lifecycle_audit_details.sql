ALTER TABLE auth_governance.audit_event
    ADD CONSTRAINT audit_suspension_details_required_ck CHECK (
        operation NOT IN ('SUSPEND_MEMBERSHIP', 'SUSPEND_PRINCIPAL')
        OR (reason IS NOT NULL AND previous_status IS NOT NULL
            AND resulting_status IS NOT NULL AND previous_version IS NOT NULL)
    );
COMMENT ON CONSTRAINT audit_suspension_details_required_ck ON auth_governance.audit_event
    IS '新停用命令不能利用历史审计可空兼容规则省略原因及前后事实';
