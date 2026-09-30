-- 受控运维只读快照：限定语句预算，聚合数值不带租户、主体或凭据。
BEGIN READ ONLY;
SET LOCAL statement_timeout='2000ms';
SELECT json_build_object(
 'policy_updating',(SELECT count(*) FROM auth_governance.policy_partition WHERE state='UPDATING'),
 'policy_blocked',(SELECT count(*) FROM auth_governance.policy_partition WHERE state='BLOCKED'),
 'directory_updating',(SELECT count(*) FROM auth_governance.directory_fence WHERE state='UPDATING'),
 'directory_blocked',(SELECT count(*) FROM auth_governance.directory_fence WHERE state='BLOCKED'),
 'pending_operations',(SELECT count(*) FROM auth_governance.projection_operation WHERE state='PENDING'),
 'oldest_pending_seconds',(SELECT coalesce(max(extract(epoch FROM clock_timestamp()-created_at)),0) FROM auth_governance.projection_operation WHERE state='PENDING'),
 'projection_attempts',(SELECT coalesce(sum(attempts),0) FROM auth_governance.projection_operation),
 'blocked_streams',(SELECT count(*) FROM auth_governance.projection_stream WHERE failures>=5),
 'approval_conflicts',(SELECT count(*) FROM auth_governance.approval_conflict),
 'quarantined_directory_sources',(SELECT count(*) FROM auth_governance.directory_source WHERE quarantined)
);
COMMIT;
