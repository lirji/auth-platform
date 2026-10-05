package com.lrj.authz.governance.authorization.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lrj.authz.governance.authorization.domain.ScopeModels.Evaluation;
import com.lrj.authz.governance.catalog.application.CatalogManifest;
import com.lrj.authz.governance.catalog.domain.CatalogModels.*;
import com.lrj.authz.governance.catalog.persistence.CatalogMapper;
import com.lrj.authz.governance.projection.application.ReadFence;
import com.lrj.authz.governance.projection.domain.FenceModels.*;
import com.lrj.authz.protocol.GovernanceDtos.AccessContext;
import com.lrj.authz.protocol.ScopeDtos.*;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** 祖先、显示顺序和相对入口沿用真实菜单规则，无能力不能默认显示全目录。 */
class BusinessNavigationTest {
    @Test
    void visibleAncestorDoesNotOpenItsPageAndPositionsArePreserved() {
        var manifest =
                new Manifest(
                        "1",
                        "commerce",
                        1,
                        List.of(),
                        List.of(
                                new Menu(
                                        "root", null, "/admin", List.of("commerce.admin"), "后台", 2),
                                new Menu(
                                        "z.child",
                                        "root",
                                        "/products",
                                        List.of("commerce.product.read"),
                                        "商品",
                                        0),
                                new Menu(
                                        "a.child",
                                        "root",
                                        "/stores",
                                        List.of("commerce.store.read"),
                                        "门店",
                                        1)));
        var view = BusinessNavigation.visible(manifest, Set.of("commerce.product.read"));
        assertThat(view)
                .extracting(com.lrj.authz.protocol.NavigationDtos.Menu::code)
                .containsExactly("z.child", "root");
        assertThat(view.getFirst().route()).isEqualTo("/products");
        assertThat(view.getLast().route()).isNull();
        assertThat(view.getFirst().position()).isZero();
        assertThat(BusinessNavigation.visible(manifest, Set.of())).isEmpty();
    }

    @Test
    void oldDisplayHasStableCodeOrderWithoutInventingLabels() {
        var manifest =
                new Manifest(
                        "1",
                        "commerce",
                        1,
                        List.of(),
                        List.of(
                                new Menu("z", null, "/z", List.of("commerce.read")),
                                new Menu("a", null, "/a", List.of("commerce.read"))));
        var view = BusinessNavigation.visible(manifest, Set.of("commerce.read"));
        assertThat(view)
                .extracting(com.lrj.authz.protocol.NavigationDtos.Menu::code)
                .containsExactly("a", "z");
        assertThat(view.getFirst().label()).isNull();
        assertThat(view.getFirst().position()).isNull();
    }

    @Test
    void expiryAndOuterEpochCannotBeHiddenByIndividuallyCompletedChecks() {
        var catalog = mock(CatalogMapper.class);
        var fences = mock(ReadFence.class);
        var authorization = mock(ReliableAuthorization.class);
        var manifest =
                CatalogManifest.normalize(
                        new Manifest(
                                "1",
                                "commerce",
                                1,
                                List.of(new Capability("commerce.read", "store", Risk.NORMAL)),
                                List.of(
                                        new Menu(
                                                "read", null, "/read", List.of("commerce.read")))));
        when(catalog.application("commerce"))
                .thenReturn(
                        new Application(
                                "commerce",
                                UUID.randomUUID().toString(),
                                "https://commerce.example",
                                1));
        when(catalog.snapshot("commerce", 1))
                .thenReturn(
                        new com.lrj.authz.governance.catalog.domain.CatalogModels.Snapshot(
                                "commerce",
                                1,
                                CatalogManifest.hash(manifest),
                                CatalogManifest.json(manifest)));
        String tenant = UUID.randomUUID().toString(),
                principal = UUID.randomUUID().toString(),
                member = UUID.randomUUID().toString(),
                policy = UUID.randomUUID().toString(),
                directory = UUID.randomUUID().toString();
        var context =
                new AccessContext(
                        principal,
                        member,
                        1,
                        1,
                        1,
                        tenant,
                        "commerce",
                        "test",
                        "commerce-nav",
                        "HUMAN",
                        UUID.randomUUID().toString());
        var old =
                new Stamp(
                        principal,
                        member,
                        1,
                        1,
                        1,
                        1,
                        1,
                        tenant,
                        "commerce",
                        "test",
                        policy,
                        1,
                        "policy-token",
                        directory,
                        1,
                        "directory-token");
        var newer =
                new Stamp(
                        principal,
                        member,
                        1,
                        1,
                        1,
                        1,
                        1,
                        tenant,
                        "commerce",
                        "test",
                        policy,
                        2,
                        "new-token",
                        directory,
                        1,
                        "directory-token");
        var current = new java.util.concurrent.atomic.AtomicReference<>(old);
        doAnswer(
                        invocation ->
                                new com.lrj.authz.governance.projection.domain.FenceModels
                                        .Snapshot<>(
                                        current.get(),
                                        ((Supplier<?>) invocation.getArgument(1)).get()))
                .when(fences)
                .snapshot(eq(context), any());
        doCallRealMethod().when(fences).requireSame(any(), any());
        var alternatives =
                List.of(
                        new Alternative(
                                UUID.randomUUID().toString(),
                                1,
                                List.of(new Clause(Kind.TENANT_ALL, List.of(), false))));
        when(authorization.evaluate(context, "commerce.read", "store"))
                .thenReturn(
                        new Evaluation(
                                UUID.randomUUID().toString(),
                                old,
                                alternatives,
                                Instant.now().minusSeconds(1)));
        var navigation = new BusinessNavigation(catalog, fences, authorization);
        assertThatThrownBy(() -> navigation.current(context, UUID.randomUUID().toString()))
                .hasMessage("AUTHZ_STATE_NOT_READY");
        when(authorization.evaluate(context, "commerce.read", "store"))
                .thenAnswer(
                        invocation -> {
                            current.set(newer);
                            return new Evaluation(
                                    UUID.randomUUID().toString(),
                                    old,
                                    alternatives,
                                    Instant.now().plusSeconds(30));
                        });
        assertThatThrownBy(() -> navigation.current(context, UUID.randomUUID().toString()))
                .hasMessage("AUTHZ_STATE_NOT_READY");
    }
}
