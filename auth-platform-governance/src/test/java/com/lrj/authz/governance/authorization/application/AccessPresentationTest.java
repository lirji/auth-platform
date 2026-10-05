package com.lrj.authz.governance.authorization.application;

import static org.junit.jupiter.api.Assertions.*;

import com.lrj.authz.governance.catalog.domain.CatalogModels.*;

import org.junit.jupiter.api.Test;

import java.util.*;

/** 菜单祖先仅作容器，不能从可见子项推导父页面权限。 */
class AccessPresentationTest {
    @Test
    void parentVisibilityNeverGrantsItsLink() {
        var manifest =
                new Manifest(
                        "1",
                        "commerce",
                        1,
                        List.of(),
                        List.of(
                                new Menu("root", null, "/admin", List.of("commerce.admin")),
                                new Menu(
                                        "stores",
                                        "root",
                                        "/stores",
                                        List.of("commerce.store.read"))));
        var view =
                AccessPresentation.visible(
                        manifest, Set.of("commerce.store.read"), "https://commerce.example");
        assertEquals(2, view.menus().size());
        assertNull(view.menus().getFirst().href());
        assertEquals("https://commerce.example/stores", view.menus().getLast().href());
        assertTrue(
                AccessPresentation.visible(manifest, Set.of(), "https://commerce.example")
                        .menus()
                        .isEmpty());
    }

    @Test
    void permittedContainerHasNoInventedDestination() {
        var manifest =
                new Manifest(
                        "1",
                        "commerce",
                        1,
                        List.of(),
                        List.of(new Menu("root", null, null, List.of("commerce.read"))));
        assertNull(
                AccessPresentation.visible(
                                manifest, Set.of("commerce.read"), "https://commerce.example")
                        .menus()
                        .getFirst()
                        .href());
    }
}
