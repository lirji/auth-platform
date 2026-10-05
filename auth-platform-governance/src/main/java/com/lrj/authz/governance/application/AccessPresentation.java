package com.lrj.authz.governance.application;

import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.AccessModels.Partition;
import com.lrj.authz.governance.domain.CatalogModels.Manifest;
import com.lrj.authz.governance.persistence.CatalogMapper;
import com.lrj.authz.protocol.GovernanceDtos.AccessContext;

import java.util.*;

/** 本人菜单只提供体验提示；任何真实业务请求仍独立执行中央授权。 */
public final class AccessPresentation {
    private final IdentityGovernance identity;
    private final CatalogMapper catalog;
    private final Checker authorization;

    /** 只组合现有权威身份、目录与判权，不引入前端角色推断。 */
    public AccessPresentation(
            IdentityGovernance identity, CatalogMapper catalog, AccessAuthorization authorization) {
        this(identity, catalog, authorization::allowed);
    }

    /** 展示可切换严格范围，但提示永远不代替业务资源检查。 */
    public AccessPresentation(
            IdentityGovernance identity, CatalogMapper catalog, Checker authorization) {
        this.identity = identity;
        this.catalog = catalog;
        this.authorization = authorization;
    }

    /** 只回答是否存在当前有效范围，用于菜单提示，不返回全范围授权。 */
    @FunctionalInterface
    public interface Checker {
        /** 当前主体至少有一条完整允许路径才显示能力。 */
        boolean allowed(AccessContext context, String capability, String resourceType);
    }

    /** 租户由本人有效成员关系验证，app/env仅选择展示目标，不能代查其他主体。 */
    public View current(VerifiedLogin login, Partition partition) {
        AccessValues.partition(partition);
        var member =
                identity.contextForLogin(
                        login.issuer(), login.subject(), partition.tenantId(), null);
        var context =
                new AccessContext(
                        member.principalId(),
                        member.membershipId(),
                        member.membershipGeneration(),
                        member.membershipVersion(),
                        member.principalVersion(),
                        member.tenantId(),
                        partition.applicationId(),
                        partition.environment(),
                        "console-display",
                        "HUMAN",
                        UUID.randomUUID().toString());
        var app = catalog.application(partition.applicationId());
        if (app == null || app.manifestVersion() == 0) return new View(List.of(), List.of());
        var manifest =
                CatalogManifest.read(
                        catalog.snapshot(app.applicationId(), app.manifestVersion())
                                .manifestJson());
        var presentation = catalog.presentation(app.applicationId(), app.manifestVersion());
        if (presentation != null)
            manifest =
                    CatalogManifest.withPresentation(
                            manifest,
                            presentation.presentationJson(),
                            presentation.presentationHash());
        Set<String> allowed = new HashSet<>();
        long deadline = System.nanoTime() + 10_000_000_000L;
        for (var capability : manifest.capabilities()) {
            if (System.nanoTime() >= deadline)
                throw new GovernanceException(GovernanceException.Code.DEPENDENCY_UNAVAILABLE);
            if (authorization.allowed(context, capability.code(), capability.resourceType()))
                allowed.add(capability.code());
        }
        if (System.nanoTime() >= deadline)
            throw new GovernanceException(GovernanceException.Code.DEPENDENCY_UNAVAILABLE);
        return visible(manifest, allowed, app.entryOrigin());
    }

    /** 祖先只因子节点可见而出现时不返回链接；不能顺带开放父页面。 */
    static View visible(Manifest manifest, Set<String> allowed, String origin) {
        var byCode = new HashMap<String, com.lrj.authz.governance.domain.CatalogModels.Menu>();
        manifest.menus().forEach(menu -> byCode.put(menu.code(), menu));
        Set<String> direct = new HashSet<>(), visible = new HashSet<>();
        for (var menu : manifest.menus()) {
            if (menu.anyOf().stream().anyMatch(allowed::contains)) {
                direct.add(menu.code());
                visible.add(menu.code());
                String parent = menu.parent();
                while (parent != null) {
                    visible.add(parent);
                    parent = byCode.get(parent).parent();
                }
            }
        }
        var menus =
                manifest.menus().stream()
                        .filter(menu -> visible.contains(menu.code()))
                        .map(
                                menu ->
                                        new Menu(
                                                menu.code(),
                                                menu.parent(),
                                                direct.contains(menu.code()) && menu.route() != null
                                                        ? origin + menu.route()
                                                        : null,
                                                menu.label()))
                        .toList();
        return new View(menus, allowed.stream().sorted().toList());
    }

    /** href为空意味着仅作为目录展示，能力提示不构成授权票据。 */
    public record Menu(String code, String parent, String href, String label) {
        /** 存量调用仍兼容无展示名称的旧目录。 */
        public Menu(String code, String parent, String href) {
            this(code, parent, href, null);
        }
    }

    /** 当前响应不缓存；失败时整次查询失败，不能保留之前的允许菜单。 */
    public record View(List<Menu> menus, List<String> capabilityHints) {}
}
