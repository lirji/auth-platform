package com.lrj.authz.protocol;

import java.util.List;

/** 门户管理展示契约不包含秘密；读取范围仍由服务端当前委派决定。 */
public final class PortalDtos {
    private PortalDtos() {}
    /** 元数据只表示当前可选项，不替代写入重验或业务访问判权。 */
    public record PublishedCatalog(String tenantId, String applicationId, String environment,
            String membershipId, long generation, long maxDurationSeconds,
            long manifestVersion, String contentHash, String viewHash,
            List<PublishedMenu> menus, List<PublishedCapability> capabilities,
            List<PublishedResourceType> resourceTypes, boolean lifecycleOwner) {
        /** 旧Java调用者没有新资格依据，保守返回不可变更。 */
        public PublishedCatalog(String tenantId,String applicationId,String environment,String membershipId,long generation,long maxDurationSeconds,long manifestVersion,String contentHash,String viewHash,List<PublishedMenu> menus,List<PublishedCapability> capabilities,List<PublishedResourceType> resourceTypes) {
            this(tenantId,applicationId,environment,membershipId,generation,maxDurationSeconds,manifestVersion,contentHash,viewHash,menus,capabilities,resourceTypes,false);
        }
        public PublishedCatalog { menus=List.copyOf(menus); capabilities=List.copyOf(capabilities); resourceTypes=List.copyOf(resourceTypes); }
    }
    /** 父菜单仅作为浏览上下文，anyOf不是批量授权或角色模板。 */
    public record PublishedMenu(String code, String parent, String route, List<String> anyOf, String label, Integer position) {
        public PublishedMenu { anyOf=List.copyOf(anyOf); }
        /** 保留旧Java消费者的四字段构造入口。 */
        public PublishedMenu(String code, String parent, String route, List<String> anyOf) { this(code,parent,route,anyOf,null,null); }
    }
    /** 全清单可读，超委派或停用项明确不可新选；原管理接口形状保持。 */
    public record PublishedCapability(String code, String resourceType, String riskLevel, boolean disabled, boolean grantable,
            CapabilityLifecycleDtos.State lifecycleState, Long lifecycleVersion, String lifecycleReason) {
        /** 旧Java构造没有新生命周期证据，不能伪造成ACTIVE。 */
        public PublishedCapability(String code,String resourceType,String riskLevel,boolean disabled,boolean grantable){this(code,resourceType,riskLevel,disabled,grantable,null,null,null);}
    }
    /** 资源名来自实际清单，允许范围只来自协议绑定，不伪造业务实例目录。 */
    public record PublishedResourceType(String code, boolean scopeSupported, List<ScopeDtos.Kind> allowedScopeKinds) {
        public PublishedResourceType { allowedScopeKinds=List.copyOf(allowedScopeKinds); }
    }
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
    /** 管理者核对固定审批配置，不出现在普通策略目录中。 */
    public record PolicyConfiguration(RequestDtos.PolicyView policy, String approverMembershipId, long approverGeneration, boolean enabled) {}
}
