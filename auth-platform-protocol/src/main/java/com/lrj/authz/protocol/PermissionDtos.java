package com.lrj.authz.protocol;

import java.util.List;

/** 解释只展示同Grant固定来源，不作为业务授权结果或跨请求ALLOW缓存。 */
public final class PermissionDtos {
    private PermissionDtos() {}

    /** 组授权没有单一受益成员，组成员资格与生效时段由业务请求实时检查。 */
    public record Explanation(
            String grantId,
            String memberId,
            Long generation,
            String groupId,
            String roleId,
            String roleCode,
            long roleVersion,
            List<String> capabilities,
            String scope,
            ScopeDtos.Rule scopeRule,
            String sourceType,
            String sourceId,
            String validFrom,
            String validTo,
            String grantState,
            String effectiveState,
            long grantVersion,
            String operationId,
            String policyState,
            String directoryState) {}

    /** 查询结果永远限定受控分区，审计没有修改或删除命令。 */
    public record Audit(
            String id,
            String operatorRef,
            String operation,
            String targetId,
            long targetVersion,
            String occurredAt,
            String outcome) {}
}
