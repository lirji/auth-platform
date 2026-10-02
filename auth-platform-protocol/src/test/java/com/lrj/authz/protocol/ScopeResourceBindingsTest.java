package com.lrj.authz.protocol;

import com.lrj.authz.protocol.ScopeDtos.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 没有真实门店归属的资源，不能在协议升级时意外接受伪门店或部门范围。 */
class ScopeResourceBindingsTest {
    @Test void tenantOnlyTypesRejectEveryNarrowScope() {
        for (String type : List.of("commerce_member", "commerce_member_policy", "commerce_runtime", "commerce_tenant",
                "campaign", "marketing_rule", "segment", "audience", "coupon_definition", "coupon_delivery",
                "entitlement_definition", "entitlement", "point_offer", "journey", "journey_instance",
                "journey_scan", "ops_page", "marketing_report")) {
            for (Kind kind : Kind.values()) {
                assertThat(ScopeResourceBindings.allows(type, kind)).as("%s/%s", type, kind).isEqualTo(kind == Kind.TENANT_ALL);
            }
            assertThat(ScopeResourceBindings.validFacts(facts(type, null))).isTrue();
            assertThat(ScopeResourceBindings.validFacts(facts(type, "S1"))).isFalse();
        }
    }
    @Test void merchantResourceDoesNotAuthorizeFutureStores() {
        assertThat(ScopeResourceBindings.allows("merchant", Kind.SPECIFIED_RESOURCES)).isTrue();
        assertThat(ScopeResourceBindings.allows("merchant", Kind.SPECIFIED_STORES)).isFalse();
        assertThat(ScopeResourceBindings.validFacts(facts("merchant", null))).isTrue();
        assertThat(ScopeResourceBindings.validFacts(facts("merchant", "S1"))).isFalse();
    }
    /** 初始调度锁版本不能冒充人群规则版本，门店字段也不能制造人群归属。 */
    @Test void segmentFactsRequirePositiveDefinitionVersion() {
        for (long version : List.of(0L, -1L))
            assertThat(ScopeResourceBindings.validFacts(new Facts("T1", ScopeDtos.MARKETING_SEGMENT_RESOURCE_TYPE,
                    "SEGMENT-1", version, null, null, List.of(), null, null))).isFalse();
        assertThat(ScopeResourceBindings.validFacts(new Facts("T1", ScopeDtos.MARKETING_SEGMENT_RESOURCE_TYPE,
                "SEGMENT-1", 7, null, null, List.of(), null, null))).isTrue();
    }
    @Test void originalStoreAndProductBindingsRemainExact() {
        for (String type : List.of("store", "product")) {
            assertThat(ScopeResourceBindings.allows(type, Kind.SPECIFIED_STORES)).isTrue();
            assertThat(ScopeResourceBindings.allows(type, Kind.SPECIFIED_RESOURCES)).isTrue();
            assertThat(ScopeResourceBindings.validFacts(facts(type, null))).isFalse();
        }
        assertThat(ScopeResourceBindings.validFacts(facts("store", "R1"))).isTrue();
        assertThat(ScopeResourceBindings.validFacts(facts("store", "S1"))).isFalse();
        assertThat(ScopeResourceBindings.validFacts(facts("product", "S1"))).isTrue();
    }
    @Test void unknownAndUnboundOwnerFieldsFailClosed() {
        assertThat(ScopeResourceBindings.allows("new_unregistered_type", Kind.TENANT_ALL)).isFalse();
        assertThat(ScopeResourceBindings.supports(null)).isFalse();
        assertThat(ScopeResourceBindings.validFacts(null)).isFalse();
        assertThat(ScopeResourceBindings.validFacts(new Facts("T1", "commerce_member", "M1", 1,
                "employee", null, List.of(), null, null))).isFalse();
        assertThat(ScopeResourceBindings.validFacts(new Facts("T1", "commerce_member", "M1", 1,
                null, "department", List.of("parent"), null, null))).isFalse();
    }
    private Facts facts(String type, String store) {
        return new Facts("T1", type, "R1", 1, null, null, List.of(), store, null);
    }
}
