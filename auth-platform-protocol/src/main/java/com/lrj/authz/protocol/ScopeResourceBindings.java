package com.lrj.authz.protocol;

import java.util.Set;
import com.lrj.authz.protocol.ScopeDtos.Facts;
import com.lrj.authz.protocol.ScopeDtos.Kind;

/** 有限资源字段绑定由协议固定；清单声明资源名不等于获准解释任意归属字段。 */
public final class ScopeResourceBindings {
    private static final Set<String> TENANT_ONLY = Set.of(
            ScopeDtos.COMMERCE_MEMBER_RESOURCE_TYPE, ScopeDtos.COMMERCE_MEMBER_POLICY_RESOURCE_TYPE, "commerce_runtime", "commerce_tenant",
            "campaign", ScopeDtos.MARKETING_RULE_RESOURCE_TYPE, "segment", ScopeDtos.MARKETING_AUDIENCE_RESOURCE_TYPE, ScopeDtos.COUPON_DEFINITION_RESOURCE_TYPE, "coupon_delivery",
            ScopeDtos.ENTITLEMENT_DEFINITION_RESOURCE_TYPE, ScopeDtos.ENTITLEMENT_RESOURCE_TYPE, ScopeDtos.POINT_OFFER_RESOURCE_TYPE, "journey", "journey_instance",
            "journey_scan", "ops_page", "marketing_report");

    private ScopeResourceBindings() {}

    /** 未绑定类型失败关闭；服务端还必须显式登记调用应用为该类型Owner。 */
    public static boolean supports(String type) {
        return type != null && (storeBound(type) || ScopeDtos.MERCHANT_RESOURCE_TYPE.equals(type) || TENANT_ONLY.contains(type));
    }

    /** 同一资源类型在治理校验和SDK响应校验中使用同一范围语义。 */
    public static boolean allows(String type, Kind kind) {
        if (!supports(type) || kind == null) return false;
        if (kind == Kind.TENANT_ALL) return true;
        if (kind == Kind.SPECIFIED_RESOURCES) return storeBound(type) || ScopeDtos.MERCHANT_RESOURCE_TYPE.equals(type);
        return kind == Kind.SPECIFIED_STORES && storeBound(type);
    }

    /** 门店/商品沿用P3归属字段，会员营销等类型不能借传入storeId伪造细粒度隔离。 */
    public static boolean validFacts(Facts facts) {
        if (facts == null || !supports(facts.resourceType()) || !resourceId(facts.resourceId())
                || facts.resourceVersion() < 0 || facts.ownerPrincipalId() != null || facts.departmentId() != null
                || facts.supplierId() != null || facts.departmentAncestors() == null || !facts.departmentAncestors().isEmpty()) return false;
        if (!storeBound(facts.resourceType())) return facts.storeId() == null;
        return resourceId(facts.storeId()) && (!ScopeDtos.STORE_RESOURCE_TYPE.equals(facts.resourceType())
                || facts.resourceId().equals(facts.storeId()));
    }

    private static boolean storeBound(String type) {
        return ScopeDtos.STORE_RESOURCE_TYPE.equals(type) || ScopeDtos.PRODUCT_RESOURCE_TYPE.equals(type);
    }

    private static boolean resourceId(String value) {
        return value != null && value.matches("[A-Za-z0-9_:/.-]{1,100}");
    }
}
