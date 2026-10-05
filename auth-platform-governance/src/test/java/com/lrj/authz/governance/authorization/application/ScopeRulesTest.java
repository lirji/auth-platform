package com.lrj.authz.governance.authorization.application;

import static org.assertj.core.api.Assertions.*;

import com.lrj.authz.protocol.ScopeDtos.*;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

/** 先锁定授权交叉放大和不完整事实的失败场景。 */
class ScopeRulesTest {
    private Rule rule(Kind kind, String... values) {
        return new Rule(1, "store", List.of(new Clause(kind, List.of(values), false)));
    }

    private Facts facts(String tenant, String id) {
        return new Facts(tenant, "store", id, 1, null, null, List.of(), id, null);
    }

    @Test
    void allReadCannotExpandRefundFromAnotherGrant() {
        var candidates =
                List.of(
                        new ScopeRules.Candidate(
                                "read", List.of("store.read"), rule(Kind.TENANT_ALL)),
                        new ScopeRules.Candidate(
                                "refund",
                                List.of("store.refund"),
                                rule(Kind.SPECIFIED_STORES, "S001")));
        var refunds =
                ScopeRules.select("store.refund", "store", candidates, Set.of("read", "refund"));
        assertThat(refunds).extracting(Alternative::grantId).containsExactly("refund");
        assertThat(ScopeRules.matches("T1", "U1", refunds, facts("T1", "S001"))).isTrue();
        assertThat(ScopeRules.matches("T1", "U1", refunds, facts("T1", "S002"))).isFalse();
        assertThat(ScopeRules.matches("T1", "U1", refunds, facts("T2", "S001"))).isFalse();
        assertThat(ScopeRules.select("store.refund", "store", candidates, Set.of("read")))
                .isEmpty();
    }

    @Test
    void pathClausesIntersectAndCompletePathsUnion() {
        var rule =
                new Rule(
                        1,
                        "store",
                        List.of(
                                new Clause(Kind.SPECIFIED_STORES, List.of("S001", "S002"), false),
                                new Clause(Kind.SPECIFIED_RESOURCES, List.of("S002"), false)));
        var alternatives =
                ScopeRules.select(
                        "read",
                        "store",
                        List.of(new ScopeRules.Candidate("g", List.of("read"), rule)),
                        Set.of("g"));
        assertThat(ScopeRules.matches("T", "U", alternatives, facts("T", "S001"))).isFalse();
        assertThat(ScopeRules.matches("T", "U", alternatives, facts("T", "S002"))).isTrue();
        assertThat(ScopeRules.matches("T", "U", List.of(), facts("T", "S002"))).isFalse();
    }

    @Test
    void unboundFieldsAndUnknownVersionsNeverBecomeTenantAll() {
        assertThatThrownBy(() -> ScopeRules.validated(rule(Kind.SELF)))
                .hasMessage("SCOPE_UNSUPPORTED");
        assertThatThrownBy(() -> ScopeRules.validated(rule(Kind.SPECIFIED_SUPPLIERS, "S")))
                .hasMessage("SCOPE_UNSUPPORTED");
        assertThatThrownBy(
                        () ->
                                ScopeRules.validated(
                                        new Rule(2, "store", rule(Kind.TENANT_ALL).clauses())))
                .hasMessage("INVALID_ARGUMENT");
        assertThatThrownBy(() -> ScopeRules.validated(rule(Kind.SPECIFIED_STORES)))
                .hasMessage("INVALID_ARGUMENT");
        assertThatThrownBy(() -> ScopeRules.validated(rule(Kind.SPECIFIED_STORES, "S", "S")))
                .hasMessage("INVALID_ARGUMENT");
        assertThatThrownBy(
                        () ->
                                ScopeRules.validated(
                                        rule(Kind.SPECIFIED_STORES, "a'; DROP TABLE x;--")))
                .hasMessage("INVALID_ARGUMENT");
        assertThatThrownBy(
                        () ->
                                ScopeRules.validated(
                                        rule(
                                                Kind.SPECIFIED_STORES,
                                                java.util.stream.IntStream.range(0, 101)
                                                        .mapToObj(i -> "S" + i)
                                                        .toArray(String[]::new))))
                .hasMessage("INVALID_ARGUMENT");
    }

    @Test
    void callerCannotMutateFrozenValuesAndMissingFactsDeny() {
        var values = new java.util.ArrayList<>(List.of("S001"));
        var rule =
                ScopeRules.validated(
                        new Rule(
                                1,
                                "store",
                                List.of(new Clause(Kind.SPECIFIED_STORES, values, false))));
        values.add("S002");
        assertThat(rule.clauses().getFirst().values()).containsExactly("S001");
        var a = List.of(new Alternative("g", 1, rule.clauses()));
        assertThat(
                        ScopeRules.matches(
                                "T",
                                "U",
                                a,
                                new Facts(
                                        "T", "store", "S001", 1, null, null, List.of(), null,
                                        null)))
                .isFalse();
    }

    @Test
    void memberScopeRemainsTenantOnlyAndNeverUsesEmployeeDepartmentOrStore() {
        var member =
                new Rule(
                        1,
                        "commerce_member",
                        List.of(new Clause(Kind.TENANT_ALL, List.of(), false)));
        var paths =
                ScopeRules.select(
                        "commerce.member.read",
                        "commerce_member",
                        List.of(
                                new ScopeRules.Candidate(
                                        "g", List.of("commerce.member.read"), member)),
                        Set.of("g"));
        assertThat(
                        ScopeRules.matches(
                                "T",
                                "U",
                                paths,
                                new Facts(
                                        "T",
                                        "commerce_member",
                                        "M1",
                                        1,
                                        null,
                                        null,
                                        List.of(),
                                        null,
                                        null)))
                .isTrue();
        assertThat(
                        ScopeRules.matches(
                                "T",
                                "U",
                                paths,
                                new Facts(
                                        "T2",
                                        "commerce_member",
                                        "M1",
                                        1,
                                        null,
                                        null,
                                        List.of(),
                                        null,
                                        null)))
                .isFalse();
        assertThat(
                        ScopeRules.matches(
                                "T",
                                "U",
                                paths,
                                new Facts(
                                        "T",
                                        "commerce_member",
                                        "M1",
                                        1,
                                        null,
                                        null,
                                        List.of(),
                                        "S1",
                                        null)))
                .isFalse();
        for (Kind kind :
                List.of(Kind.SPECIFIED_STORES, Kind.SPECIFIED_RESOURCES, Kind.DEPARTMENT)) {
            assertThatThrownBy(
                            () ->
                                    ScopeRules.validated(
                                            new Rule(
                                                    1,
                                                    "commerce_member",
                                                    List.of(
                                                            new Clause(
                                                                    kind, List.of("S1"), false)))))
                    .hasMessage("SCOPE_UNSUPPORTED");
        }
        assertThatThrownBy(
                        () -> ScopeRules.validated(new Rule(1, "unknown_type", member.clauses())))
                .hasMessage("SCOPE_UNSUPPORTED");
    }

    /** 宽读与窄写必须保持不同Grant；同名仓在另一企业也不能进入该授权路径。 */
    @Test
    void warehouseReadCannotExpandAdjustmentFromAnotherGrant() {
        var read = warehouseRule("WH-A", "WH-B");
        var apply = warehouseRule("WH-A");
        var candidates =
                List.of(
                        new ScopeRules.Candidate("read", List.of("wms.stock.read"), read),
                        new ScopeRules.Candidate("apply", List.of("wms.adjustment.apply"), apply));
        var paths =
                ScopeRules.select(
                        "wms.adjustment.apply",
                        "wms_warehouse",
                        candidates,
                        Set.of("read", "apply"));
        assertThat(paths).extracting(Alternative::grantId).containsExactly("apply");
        assertThat(ScopeRules.matches("T1", "U1", paths, warehouseFacts("T1", "WH-A"))).isTrue();
        assertThat(ScopeRules.matches("T1", "U1", paths, warehouseFacts("T1", "WH-B"))).isFalse();
        assertThat(ScopeRules.matches("T1", "U1", paths, warehouseFacts("T2", "WH-A"))).isFalse();
        assertThat(
                        ScopeRules.select(
                                "wms.adjustment.apply",
                                "wms_warehouse",
                                candidates,
                                Set.of("read")))
                .isEmpty();
    }

    /** 不提供全仓通配或空集合退化；重复仓和过大集合沿用现有契约拒绝。 */
    @Test
    void warehouseRulesRejectImplicitAllWarehousesAndMalformedSets() {
        for (Kind kind : List.of(Kind.TENANT_ALL, Kind.SPECIFIED_STORES, Kind.DEPARTMENT)) {
            var values = kind == Kind.TENANT_ALL ? List.<String>of() : List.of("WH-A");
            assertThatThrownBy(
                            () ->
                                    ScopeRules.validated(
                                            new Rule(
                                                    1,
                                                    "wms_warehouse",
                                                    List.of(new Clause(kind, values, false)))))
                    .hasMessage("SCOPE_UNSUPPORTED");
        }
        assertThatThrownBy(() -> ScopeRules.validated(warehouseRule()))
                .hasMessage("INVALID_ARGUMENT");
        assertThatThrownBy(() -> ScopeRules.validated(warehouseRule("WH-A", "WH-A")))
                .hasMessage("INVALID_ARGUMENT");
        assertThatThrownBy(
                        () ->
                                ScopeRules.validated(
                                        warehouseRule(
                                                java.util.stream.IntStream.range(0, 101)
                                                        .mapToObj(i -> "WH-" + i)
                                                        .toArray(String[]::new))))
                .hasMessage("INVALID_ARGUMENT");
        assertThat(
                        ScopeRules.decode(ScopeRules.encode(warehouseRule("WH-B", "WH-A")))
                                .clauses()
                                .getFirst()
                                .values())
                .containsExactly("WH-A", "WH-B");
    }

    /** 完整Grant路径可以并集，但企业共享资源的授权不能套到仓库上。 */
    @Test
    void warehousePathsUnionOnlyForTheSameCapabilityAndResourceType() {
        var candidates =
                List.of(
                        new ScopeRules.Candidate(
                                "a", List.of("wms.stock.read"), warehouseRule("WH-A")),
                        new ScopeRules.Candidate(
                                "b", List.of("wms.stock.read"), warehouseRule("WH-B")),
                        new ScopeRules.Candidate(
                                "enterprise",
                                List.of("wms.stock.read"),
                                new Rule(
                                        1,
                                        "wms_enterprise",
                                        List.of(new Clause(Kind.TENANT_ALL, List.of(), false)))));
        var paths =
                ScopeRules.select(
                        "wms.stock.read",
                        "wms_warehouse",
                        candidates,
                        Set.of("a", "b", "enterprise"));
        assertThat(paths).extracting(Alternative::grantId).containsExactly("a", "b");
        assertThat(ScopeRules.matches("T1", "U1", paths, warehouseFacts("T1", "WH-A"))).isTrue();
        assertThat(ScopeRules.matches("T1", "U1", paths, warehouseFacts("T1", "WH-B"))).isTrue();
        assertThat(ScopeRules.matches("T1", "U1", paths, warehouseFacts("T1", "WH-C"))).isFalse();
        assertThat(
                        ScopeRules.matches(
                                "T1",
                                "U1",
                                paths,
                                new Facts(
                                        "T1",
                                        "wms_warehouse",
                                        "WH-A",
                                        1,
                                        null,
                                        null,
                                        List.of(),
                                        "WH-A",
                                        null)))
                .isFalse();
    }

    private Rule warehouseRule(String... warehouses) {
        return new Rule(
                1,
                "wms_warehouse",
                List.of(new Clause(Kind.SPECIFIED_RESOURCES, List.of(warehouses), false)));
    }

    private Facts warehouseFacts(String tenant, String warehouse) {
        return new Facts(tenant, "wms_warehouse", warehouse, 1, null, null, List.of(), null, null);
    }
}
