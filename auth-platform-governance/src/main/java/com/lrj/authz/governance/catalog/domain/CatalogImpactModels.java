package com.lrj.authz.governance.catalog.domain;

import com.lrj.authz.governance.catalog.domain.CatalogModels.Manifest;
import com.lrj.authz.governance.catalog.domain.CatalogModels.Preview;
import com.lrj.authz.protocol.ScopeDtos.Rule;

import java.util.List;

/** 潜在关系与实际资源判权分开建模；统计始终来自服务端完整分区。 */
public final class CatalogImpactModels {
    private CatalogImpactModels() {}

    /** 游标只绑定查询基准和排序位置，没有授权效力。 */
    public record Cursor(
            String basisHash,
            String afterRole,
            String afterGrant,
            String afterMember,
            String afterPolicy) {}

    /** 调用方不能指定被分析人的身份或替换已认证诊断人。 */
    public record Input(
            String tenantId,
            String applicationId,
            String environment,
            Manifest manifest,
            Cursor cursor) {}

    /** 完整计数独立于三个明细列表的当前页。 */
    public record Stats(
            long roleCount,
            long activeGrantCount,
            long pendingGrantCount,
            long activePeopleCount,
            long pendingPeopleCount,
            long peopleCount,
            long groupGrantCount,
            long requestPolicyCount) {}

    /** 投影／目录尚未就绪时仍不得把候选统计冒充业务ALLOW。 */
    public record Fences(
            String policyState,
            Long policyDesiredEpoch,
            Long policyAppliedEpoch,
            String directoryState,
            Long directoryDesiredEpoch,
            Long directoryAppliedEpoch,
            boolean sourceQuarantined,
            List<String> disabledCapabilities) {
        /** 在不可变契约构造边界沿用既有字段校验、集合快照或默认值，保持各创建入口语义一致。 */
        public Fences {
            disabledCapabilities = List.copyOf(disabledCapabilities);
        }
    }

    /** 固定角色依赖保留完整能力；无当前来源的角色仍可关联菜单变化。 */
    public record Role(
            String id,
            String roleCode,
            long version,
            List<String> capabilities,
            long activeGrantCount,
            long pendingGrantCount) {
        /** 在不可变契约构造边界沿用既有字段校验、集合快照或默认值，保持各创建入口语义一致。 */
        public Role {
            capabilities = List.copyOf(capabilities);
        }
    }

    /** 组展开保留每个当前成员的来源；没有当前人员的有效组来源明确返回null成员。 */
    public record Source(
            String grantId,
            String roleId,
            String memberId,
            Long generation,
            String groupId,
            String sourceType,
            String sourceId,
            String validFrom,
            String validTo,
            String grantState,
            String scope,
            Rule scopeRule) {}

    /** 申请配置是依赖事实，enabled不意味着已经审批或授予。 */
    public record Policy(String id, String roleId, long policyVersion, boolean enabled) {}

    /** 超过运算边界没有可用于发布确认的完整依据。 */
    public enum Completeness {
        COMPLETE,
        BASIS_LIMIT_EXCEEDED
    }

    /** 双摘要、时点、完整性和每类有界明细均随结果返回，不产生任何Grant。 */
    public record Report(
            String tenantId,
            String applicationId,
            String environment,
            long currentVersion,
            String contentHash,
            String presentationHash,
            String observedAt,
            String basisHash,
            Completeness completeness,
            Preview diff,
            Stats stats,
            Fences fences,
            List<Role> roles,
            List<Source> sources,
            List<Policy> policies,
            Cursor nextCursor) {
        /** 在不可变契约构造边界沿用既有字段校验、集合快照或默认值，保持各创建入口语义一致。 */
        public Report {
            roles = List.copyOf(roles);
            sources = List.copyOf(sources);
            policies = List.copyOf(policies);
        }
    }
}
