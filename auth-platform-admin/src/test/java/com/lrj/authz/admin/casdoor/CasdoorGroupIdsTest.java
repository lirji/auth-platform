package com.lrj.authz.admin.casdoor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link CasdoorGroupIds} 租户化编码与碰撞安全单测。 */
class CasdoorGroupIdsTest {

    @Test
    void encodesTenantScoped() {
        assertEquals("acme_research", CasdoorGroupIds.encode("acme", "research"));
    }

    @Test
    void encodesBuiltInOrgWithHyphen() {
        // Casdoor 内置组织 built-in（含连字符）合法；组名如 engineers。
        assertEquals("built-in_engineers", CasdoorGroupIds.encode("built-in", "engineers"));
    }

    @Test
    void crossTenantSameGroupDoNotCollide() {
        assertNotEquals(
                CasdoorGroupIds.encode("acme", "research"),
                CasdoorGroupIds.encode("beta", "research"));
    }

    @Test
    void rejectsUnderscoreToAvoidPrefixAmbiguity() {
        // 若允许下划线，encode("a_b","c") 与 encode("a","b_c") 都会产出 "a_b_c" → 碰撞。v1 一律 fail-closed。
        assertThrows(IllegalArgumentException.class, () -> CasdoorGroupIds.encode("a_b", "c"));
        assertThrows(IllegalArgumentException.class, () -> CasdoorGroupIds.encode("a", "b_c"));
    }

    @Test
    void rejectsSlash() {
        // 传入的应是短组名，不应含 <org>/ 路径分隔符。
        assertThrows(IllegalArgumentException.class, () -> CasdoorGroupIds.encode("built-in/x", "g"));
        assertThrows(IllegalArgumentException.class, () -> CasdoorGroupIds.encode("org", "a/b"));
    }

    @Test
    void rejectsBlankOrNull() {
        assertThrows(IllegalArgumentException.class, () -> CasdoorGroupIds.encode("", "g"));
        assertThrows(IllegalArgumentException.class, () -> CasdoorGroupIds.encode("org", null));
        assertThrows(IllegalArgumentException.class, () -> CasdoorGroupIds.encode("  ", "g"));
    }

    @Test
    void rejectsLeadingHyphen() {
        assertThrows(IllegalArgumentException.class, () -> CasdoorGroupIds.encode("-org", "g"));
    }
}
