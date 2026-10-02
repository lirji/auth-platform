package com.lrj.authz.protocol;

import java.util.List;

/** 有限范围协议只描述业务约束，不允许调用方传SQL、脚本或字段名称。 */
public final class ScopeDtos {
    private ScopeDtos() {}
    /** 已绑定的门店资源类型，与应用清单及业务Owner字段一致。 */
    public static final String STORE_RESOURCE_TYPE = "store";
    /** 商品的归属字段由commerce catalog模块提供。 */
    public static final String PRODUCT_RESOURCE_TYPE = "product";
    /** 商家仅绑定自身资源，不隐含拥有其当前或将来门店的权限。 */
    public static final String MERCHANT_RESOURCE_TYPE = "merchant";
    /** 商城客户会员是租户级业务资源，与OA员工主体分离。 */
    public static final String COMMERCE_MEMBER_RESOURCE_TYPE = "commerce_member";
    public static final String COMMERCE_MEMBER_POLICY_RESOURCE_TYPE = "commerce_member_policy";
    /** 积分兑换商品采用真实租户范围，门店仅为业务过滤条件。 */
    public static final String POINT_OFFER_RESOURCE_TYPE = "point_offer";
    /** 优惠券定义由商城维护，版本目录采用完整租户范围。 */
    public static final String COUPON_DEFINITION_RESOURCE_TYPE = "coupon_definition";
    /** 权益定义与实际授予分开判权，均只使用真实租户范围。 */
    public static final String ENTITLEMENT_DEFINITION_RESOURCE_TYPE = "entitlement_definition";
    /** 活动绑定不可变内容版本；审批与发布的并发锁版本不作为授权事实。 */
    public static final String MARKETING_CAMPAIGN_RESOURCE_TYPE = "campaign";
    public static final String MARKETING_RULE_RESOURCE_TYPE = "marketing_rule";
    /** 人群快照只提供完整租户集合许可，不把成员列表作为授权事实。 */
    public static final String MARKETING_AUDIENCE_RESOURCE_TYPE = "audience";
    /** 动态人群绑定真实定义版本，运行快照及调度锁版本不作为授权事实。 */
    public static final String MARKETING_SEGMENT_RESOURCE_TYPE = "segment";
    public static final String ENTITLEMENT_RESOURCE_TYPE = "entitlement";
    /** 范围响应大小有界，超出不能截断后放行。 */
    public static final int MAX_PLAN_BYTES = 262144;

    /** 显式编码不使用ordinal；未注册业务字段的类型不能执行。 */
    public enum Kind {
        TENANT_ALL, SELF, DEPARTMENT, DEPARTMENT_TREE,
        SPECIFIED_STORES, SPECIFIED_SUPPLIERS, SPECIFIED_RESOURCES
    }

    /** 一条路径内各条件取交集；部门子树显式声明是否包含根。 */
    public record Clause(Kind kind, List<String> values, boolean includeRoot) {}

    /** 创建后固定，不随角色升级或目录变动改写历史内容。 */
    public record Rule(long version, String resourceType, List<Clause> clauses) {}

    /** 同一条完整Grant的范围；不能将其能力拆出后与其他范围组合。 */
    public record Alternative(String grantId, long scopeVersion, List<Clause> clauses) {}

    /** 可信资源Owner读取的事实，浏览器同名字段不得直接转发。 */
    public record Facts(String tenantId, String resourceType, String resourceId, long resourceVersion,
                        String ownerPrincipalId, String departmentId, List<String> departmentAncestors,
                        String storeId, String supplierId) {}

    /** 新管理入口复用现有委派，主体与操作者仍来自认证边界。 */
    public record CreateScopedGrant(String tenantId, String applicationId, String environment,
                                    String commandId, String memberId, long memberGeneration,
                                    String roleId, Rule scopeRule, String sourceId, String validFrom, String validTo) {}
}
