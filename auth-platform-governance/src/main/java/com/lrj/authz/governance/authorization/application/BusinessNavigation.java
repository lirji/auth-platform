package com.lrj.authz.governance.authorization.application;

import static com.lrj.authz.governance.shared.application.GovernanceException.Code.*;

import com.lrj.authz.governance.catalog.application.CatalogManifest;
import com.lrj.authz.governance.catalog.domain.CatalogModels.Manifest;
import com.lrj.authz.governance.catalog.persistence.CatalogMapper;
import com.lrj.authz.governance.identity.application.BootstrapCommand;
import com.lrj.authz.governance.projection.application.ReadFence;
import com.lrj.authz.governance.shared.application.GovernanceException;
import com.lrj.authz.protocol.GovernanceDtos.AccessContext;
import com.lrj.authz.protocol.NavigationDtos.*;

import java.time.Instant;
import java.util.*;

/** 整次菜单查询使用两份主库栅栏，避免逐项判权后拼接出跨版本允许集合。 */
public final class BusinessNavigation {
    private static final long QUERY_BUDGET_NANOS = 7_000_000_000L;
    private final CatalogMapper catalog;
    private final ReadFence fences;
    private final ReliableAuthorization authorization;

    /** 只组合当前严格判权，不创建Grant、执行引用或持久化提示。 */
    public BusinessNavigation(
            CatalogMapper catalog, ReadFence fences, ReliableAuthorization authorization) {
        this.catalog = catalog;
        this.fences = fences;
        this.authorization = authorization;
    }

    /** context只能来自本次双身份解析；整次失效不给部分结果。 */
    public View current(AccessContext context, String requestId) {
        BootstrapCommand.uuid(requestId);
        if (context == null
                || !com.lrj.authz.governance.identity.domain.IdentityModels.PrincipalKind.HUMAN
                        .code()
                        .equals(context.actorType())) throw new GovernanceException(ACCESS_DENIED);
        long deadline = System.nanoTime() + QUERY_BUDGET_NANOS;
        var before = fences.snapshot(context, () -> published(context.applicationId()));
        var manifest = before.data();
        if (manifest.manifestVersion() != before.stamp().manifestVersion())
            throw new GovernanceException(AUTHZ_STATE_NOT_READY);
        Set<String> used = new HashSet<>(), allowed = new HashSet<>();
        Instant validUntil = Instant.MAX;
        manifest.menus().forEach(menu -> used.addAll(menu.anyOf()));
        for (var capability : manifest.capabilities()) {
            if (!used.contains(capability.code())) continue;
            deadline(deadline);
            var result =
                    authorization.evaluate(context, capability.code(), capability.resourceType());
            if (!result.alternatives().isEmpty()) allowed.add(capability.code());
            if (result.validUntil().isBefore(validUntil)) validUntil = result.validUntil();
        }
        deadline(deadline);
        var after = fences.snapshot(context, () -> published(context.applicationId()));
        fences.requireSame(before.stamp(), after.stamp());
        if (!manifest.equals(after.data())) throw new GovernanceException(AUTHZ_STATE_NOT_READY);
        deadline(deadline);
        // epoch不随自然到期改变，必须单独约束先检查能力的最早失效时点。
        if (!validUntil.isAfter(Instant.now()))
            throw new GovernanceException(AUTHZ_STATE_NOT_READY);
        return new View(
                "1",
                requestId,
                context,
                manifest.manifestVersion(),
                CatalogManifest.hash(manifest),
                CatalogManifest.presentationHash(manifest),
                Instant.now().toString(),
                (allowed.isEmpty() ? State.NO_ACCESS : State.AVAILABLE).code(),
                visible(manifest, allowed),
                allowed.stream().sorted().toList());
    }

    private Manifest published(String application) {
        try {
            var app = catalog.application(application);
            if (app == null || app.manifestVersion() == 0)
                throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
            var snapshot = catalog.snapshot(application, app.manifestVersion());
            if (snapshot == null) throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
            var manifest = CatalogManifest.read(snapshot.manifestJson());
            if (!application.equals(manifest.application())
                    || manifest.manifestVersion() != app.manifestVersion()
                    || !CatalogManifest.hash(manifest).equals(snapshot.contentHash()))
                throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
            var presentation = catalog.presentation(application, app.manifestVersion());
            return presentation == null
                    ? manifest
                    : CatalogManifest.withPresentation(
                            manifest,
                            presentation.presentationJson(),
                            presentation.presentationHash());
        } catch (GovernanceException corrupt) {
            throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
        }
    }

    /** 复用既有祖先规则，仅将绝对入口改为业务应用内相对路由并保留展示顺序。 */
    static List<Menu> visible(Manifest manifest, Set<String> allowed) {
        var byCode =
                new HashMap<String, com.lrj.authz.governance.catalog.domain.CatalogModels.Menu>();
        manifest.menus().forEach(menu -> byCode.put(menu.code(), menu));
        return AccessPresentation.visible(manifest, allowed, "").menus().stream()
                .map(
                        menu ->
                                new Menu(
                                        menu.code(),
                                        menu.parent(),
                                        menu.href(),
                                        menu.label(),
                                        byCode.get(menu.code()).position()))
                .sorted(
                        Comparator.comparing(
                                        Menu::position,
                                        Comparator.nullsLast(Comparator.naturalOrder()))
                                .thenComparing(Menu::code))
                .toList();
    }

    private static void deadline(long value) {
        if (System.nanoTime() >= value) throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
    }
}
