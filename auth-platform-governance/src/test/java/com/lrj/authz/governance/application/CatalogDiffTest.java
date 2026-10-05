package com.lrj.authz.governance.application;

import static org.assertj.core.api.Assertions.*;

import com.lrj.authz.governance.domain.CatalogModels.*;

import org.junit.jupiter.api.Test;

import java.util.*;

/** 纯差异验证稳定身份、两版子树影响及非法发布保护，不把差异当作实际授权。 */
class CatalogDiffTest {
    private final List<Capability> caps =
            List.of(
                    new Capability("app.read", "store", Risk.NORMAL),
                    new Capability("app.write", "store", Risk.HIGH));

    private Menu menu(
            String code, String parent, String route, String label, int position, String... any) {
        return new Menu(code, parent, route, List.of(any), label, position);
    }

    private Manifest manifest(long version, List<Menu> menus) {
        return new Manifest("1", "app", version, caps, menus);
    }

    private Manifest first() {
        return manifest(
                1,
                List.of(
                        menu("group.a", null, null, "商品", 0),
                        menu("page", "group.a", "/items", "商品列表", 1, "app.read"),
                        menu("group.b", null, null, "经营", 2)));
    }

    @Test
    void stableIdentityReportsAllChangedFieldsAndOldAndNewFacts() {
        var old = first();
        var changed = menu("page", "group.b", "/new-items", "商品档案", 3, "app.write");
        var next = manifest(2, List.of(old.menus().get(0), changed, old.menus().get(2)));
        var result = CatalogDiff.compare("app", 1, old, next);
        assertThat(result.publishable()).isTrue();
        assertThat(result.menuChanges()).hasSize(1);
        var diff = result.menuChanges().getFirst();
        assertThat(diff.kind()).isEqualTo(ChangeKind.CHANGED);
        assertThat(diff.code()).isEqualTo("page");
        assertThat(diff.before()).isEqualTo(old.menus().get(1));
        assertThat(diff.after()).isEqualTo(changed);
        assertThat(diff.fields()).containsExactly("label", "position", "parent", "route", "any_of");
        assertThat(result.affectedCapabilities()).containsExactly("app.read", "app.write");
    }

    @Test
    void movingAncestorIncludesUnchangedDescendantCapabilities() {
        var old = first();
        var next =
                manifest(
                        2,
                        List.of(
                                menu("group.a", "group.b", null, "商品", 0),
                                old.menus().get(1),
                                old.menus().get(2)));
        var result = CatalogDiff.compare("app", 1, old, next);
        assertThat(result.menuChanges()).extracting(MenuChange::code).containsExactly("group.a");
        assertThat(result.affectedCapabilities()).containsExactly("app.read");
    }

    @Test
    void removalAndAdditionRemainDistinctAndSortedWithoutChangingCapabilities() {
        var old = first();
        var next =
                manifest(
                        2,
                        List.of(
                                old.menus().get(0),
                                old.menus().get(2),
                                menu("new.page", "group.b", "/new", "新页", 3, "app.write")));
        var result = CatalogDiff.compare("app", 1, old, next);
        assertThat(result.menuChanges())
                .extracting(MenuChange::code)
                .containsExactly("new.page", "page");
        assertThat(result.menuChanges())
                .extracting(MenuChange::kind)
                .containsExactly(ChangeKind.ADDED, ChangeKind.REMOVED);
        assertThat(result.menuChanges().get(0).before()).isNull();
        assertThat(result.menuChanges().get(1).after()).isNull();
        assertThat(result.retained()).containsExactly("app.read", "app.write");
    }

    @Test
    void inputOrderingAndPermissionOrderingDoNotCreateDifferences() {
        var old =
                manifest(
                        1, List.of(menu("page", null, "/items", "商品", 0, "app.read", "app.write")));
        var next =
                new Manifest(
                        "1",
                        "app",
                        1,
                        List.of(caps.get(1), caps.get(0)),
                        List.of(menu("page", null, "/items", "商品", 0, "app.write", "app.read")));
        var result = CatalogDiff.compare("app", 1, old, next);
        assertThat(result.publishable()).isTrue();
        assertThat(result.menuChanges()).isEmpty();
        assertThat(result.affectedCapabilities()).isEmpty();
    }

    @Test
    void sameVersionDisplayChangeIsVisibleButUnpublishableDespiteSameLegacyHash() {
        var old = first();
        var next =
                manifest(
                        1,
                        List.of(
                                menu("group.a", null, null, "商品管理", 0),
                                old.menus().get(1),
                                old.menus().get(2)));
        var result = CatalogDiff.compare("app", 1, old, next);
        assertThat(result.contentHash()).isEqualTo(CatalogManifest.hash(old));
        assertThat(result.presentationHash()).isNotEqualTo(CatalogManifest.presentationHash(old));
        assertThat(result.publishable()).isFalse();
        assertThat(result.violations())
                .extracting(CatalogViolation::code)
                .containsExactly(ViolationKind.SAME_VERSION_CHANGED);
    }

    @Test
    void capabilityRemovalAndSemanticChangeReturnSpecificFactsAndCannotPublish() {
        var old = first();
        var next =
                new Manifest(
                        "1",
                        "app",
                        2,
                        List.of(new Capability("app.write", "order", Risk.NORMAL)),
                        List.of());
        var result = CatalogDiff.compare("app", 1, old, next);
        assertThat(result.publishable()).isFalse();
        assertThat(result.violations())
                .extracting(CatalogViolation::code)
                .containsExactly(
                        ViolationKind.CAPABILITY_REMOVED, ViolationKind.CAPABILITY_CHANGED);
        assertThat(result.violations().get(1).before()).isEqualTo(caps.get(1));
        assertThat(result.violations().get(1).after().resourceType()).isEqualTo("order");
        assertThat(result.retained()).isEmpty();
    }

    @Test
    void oldVersionIsReportedAndFirstPublicationShowsAllNewMenus() {
        assertThat(
                        CatalogDiff.compare("app", 2, manifest(2, first().menus()), first())
                                .violations())
                .extracting(CatalogViolation::code)
                .containsExactly(ViolationKind.VERSION_REGRESSION);
        var initial = CatalogDiff.compare("app", 0, null, first());
        assertThat(initial.menuChanges()).hasSize(3);
        assertThat(initial.added()).containsExactly("app.read", "app.write");
        assertThat(initial.publishable()).isTrue();
    }

    @Test
    void brokenStructureStillFailsInsteadOfReceivingPublicationEligibility() {
        var broken = manifest(2, List.of(menu("page", "missing", "/items", "商品", 0, "app.read")));
        assertThatThrownBy(() -> CatalogDiff.compare("app", 1, first(), broken))
                .hasMessage("INVALID_ARGUMENT");
    }
}
