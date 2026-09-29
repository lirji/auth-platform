package com.lrj.authz.protocol;

import java.util.List;

/** 门户管理展示契约不包含秘密；读取范围仍由服务端当前委派决定。 */
public final class PortalDtos {
    private PortalDtos() {}
    /** 可授予集合只作为表单选项，写入时必须重新检查。 */
    public record Capability(String code, String resourceType, String riskLevel, boolean disabled) {}
    /** 业务权限与目录管理权分开；目录Owner不能由请求参数指定。 */
    public record Management(String membershipId, long generation, long maxDurationSeconds, List<Capability> capabilities,
                             boolean catalogOwner, long manifestVersion, String policyState, String directoryState,
                             Long desiredEpoch, Long appliedEpoch) {}
    /** 最小成员选项不泄露登录凭据，代际是提交时必须携带的并发边界。 */
    public record Member(String membershipId, long generation, String memberKind, String validTo) {}
    /** 固定版本差异不执行原Grant自动升级，数量只指当前版本的引用。 */
    public record RoleImpact(String roleId, String previousRoleId, List<String> added, List<String> removed, long referencingGrantCount) {}
    /** 数据库投影栅栏状态，不以浏览器轮询推断回执。 */
    public record Progress(String policyState, String directoryState, Long desiredEpoch, Long appliedEpoch) {}
}
