package com.lrj.authz.governance.application;

import com.lrj.authz.protocol.ScopeDtos.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 先锁定授权交叉放大和不完整事实的失败场景。 */
class ScopeRulesTest {
    private Rule rule(Kind kind, String... values) {
        return new Rule(1, "store", List.of(new Clause(kind, List.of(values), false)));
    }
    private Facts facts(String tenant, String id) {
        return new Facts(tenant, "store", id, 1, null, null, List.of(), id, null);
    }
    @Test void allReadCannotExpandRefundFromAnotherGrant() {
        var candidates = List.of(
                new ScopeRules.Candidate("read", List.of("store.read"), rule(Kind.TENANT_ALL)),
                new ScopeRules.Candidate("refund", List.of("store.refund"), rule(Kind.SPECIFIED_STORES, "S001")));
        var refunds = ScopeRules.select("store.refund", "store", candidates, Set.of("read", "refund"));
        assertThat(refunds).extracting(Alternative::grantId).containsExactly("refund");
        assertThat(ScopeRules.matches("T1", "U1", refunds, facts("T1", "S001"))).isTrue();
        assertThat(ScopeRules.matches("T1", "U1", refunds, facts("T1", "S002"))).isFalse();
        assertThat(ScopeRules.matches("T1", "U1", refunds, facts("T2", "S001"))).isFalse();
        assertThat(ScopeRules.select("store.refund", "store", candidates, Set.of("read"))).isEmpty();
    }
    @Test void pathClausesIntersectAndCompletePathsUnion() {
        var rule = new Rule(1, "store", List.of(new Clause(Kind.SPECIFIED_STORES, List.of("S001", "S002"), false),
                new Clause(Kind.SPECIFIED_RESOURCES, List.of("S002"), false)));
        var alternatives = ScopeRules.select("read", "store", List.of(new ScopeRules.Candidate("g", List.of("read"), rule)), Set.of("g"));
        assertThat(ScopeRules.matches("T", "U", alternatives, facts("T", "S001"))).isFalse();
        assertThat(ScopeRules.matches("T", "U", alternatives, facts("T", "S002"))).isTrue();
        assertThat(ScopeRules.matches("T", "U", List.of(), facts("T", "S002"))).isFalse();
    }
    @Test void unboundFieldsAndUnknownVersionsNeverBecomeTenantAll() {
        assertThatThrownBy(() -> ScopeRules.validated(rule(Kind.SELF))).hasMessage("SCOPE_UNSUPPORTED");
        assertThatThrownBy(() -> ScopeRules.validated(rule(Kind.SPECIFIED_SUPPLIERS, "S"))).hasMessage("SCOPE_UNSUPPORTED");
        assertThatThrownBy(() -> ScopeRules.validated(new Rule(2, "store", rule(Kind.TENANT_ALL).clauses()))).hasMessage("INVALID_ARGUMENT");
        assertThatThrownBy(() -> ScopeRules.validated(rule(Kind.SPECIFIED_STORES))).hasMessage("INVALID_ARGUMENT");
        assertThatThrownBy(() -> ScopeRules.validated(rule(Kind.SPECIFIED_STORES, "S", "S"))).hasMessage("INVALID_ARGUMENT");
        assertThatThrownBy(() -> ScopeRules.validated(rule(Kind.SPECIFIED_STORES, "a'; DROP TABLE x;--"))).hasMessage("INVALID_ARGUMENT");
        assertThatThrownBy(() -> ScopeRules.validated(rule(Kind.SPECIFIED_STORES, java.util.stream.IntStream.range(0,101).mapToObj(i->"S"+i).toArray(String[]::new)))).hasMessage("INVALID_ARGUMENT");
    }
    @Test void callerCannotMutateFrozenValuesAndMissingFactsDeny() {
        var values = new java.util.ArrayList<>(List.of("S001"));
        var rule = ScopeRules.validated(new Rule(1, "store", List.of(new Clause(Kind.SPECIFIED_STORES, values, false))));
        values.add("S002");
        assertThat(rule.clauses().getFirst().values()).containsExactly("S001");
        var a = List.of(new Alternative("g", 1, rule.clauses()));
        assertThat(ScopeRules.matches("T", "U", a, new Facts("T", "store", "S001", 1, null, null, List.of(), null, null))).isFalse();
    }
}
