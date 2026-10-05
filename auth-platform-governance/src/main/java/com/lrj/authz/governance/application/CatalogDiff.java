package com.lrj.authz.governance.application;

import com.lrj.authz.governance.domain.CatalogModels.*;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 有界纯目录差异；不访问身份、授权表或外部系统，所有输入先按v1契约归一化。 */
public final class CatalogDiff {
    private CatalogDiff() {}

    /** 即使候选语义不兼容也返回可审查原因，实际发布仍必须独立拒绝。 */
    public static Preview compare(
            String application, long currentVersion, Manifest previous, Manifest candidate) {
        CatalogManifest.code(application);
        Manifest next = CatalogManifest.normalize(candidate);
        Manifest old = previous == null ? null : CatalogManifest.normalize(previous);
        if (!application.equals(next.application())
                || (old != null
                        && (!application.equals(old.application())
                                || old.manifestVersion() != currentVersion))) {
            throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
        }
        Map<String, Capability> oldCaps =
                old == null ? Map.of() : index(old.capabilities(), Capability::code);
        Map<String, Capability> nextCaps = index(next.capabilities(), Capability::code);
        List<CatalogViolation> violations = new ArrayList<>();
        if (next.manifestVersion() < currentVersion) {
            violations.add(
                    new CatalogViolation(ViolationKind.VERSION_REGRESSION, null, null, null));
        }
        for (var cap : new TreeMap<>(oldCaps).values()) {
            Capability replacement = nextCaps.get(cap.code());
            if (replacement == null)
                violations.add(
                        new CatalogViolation(
                                ViolationKind.CAPABILITY_REMOVED, cap.code(), cap, null));
            else if (!cap.equals(replacement))
                violations.add(
                        new CatalogViolation(
                                ViolationKind.CAPABILITY_CHANGED, cap.code(), cap, replacement));
        }
        String hash = CatalogManifest.hash(next),
                displayHash = CatalogManifest.presentationHash(next);
        if (next.manifestVersion() == currentVersion
                && (old == null
                        || !hash.equals(CatalogManifest.hash(old))
                        || !displayHash.equals(CatalogManifest.presentationHash(old)))) {
            violations.add(
                    new CatalogViolation(ViolationKind.SAME_VERSION_CHANGED, null, null, null));
        }
        Map<String, Menu> before = old == null ? Map.of() : index(old.menus(), Menu::code);
        Map<String, Menu> after = index(next.menus(), Menu::code);
        Set<String> codes = new TreeSet<>(before.keySet());
        codes.addAll(after.keySet());
        List<MenuChange> changes = new ArrayList<>();
        Set<String> affected = new TreeSet<>();
        for (String code : codes) {
            Menu a = before.get(code), b = after.get(code);
            if (Objects.equals(a, b)) continue;
            ChangeKind kind =
                    a == null
                            ? ChangeKind.ADDED
                            : b == null ? ChangeKind.REMOVED : ChangeKind.CHANGED;
            changes.add(new MenuChange(code, kind, a, b, fields(a, b)));
            // 父节点移动／删除也会改变子入口，必须同时检查两版子树，不能只看当前行any_of。
            descendantCapabilities(before, code, affected);
            descendantCapabilities(after, code, affected);
        }
        violations.stream()
                .map(CatalogViolation::capability)
                .filter(Objects::nonNull)
                .forEach(affected::add);
        return new Preview(
                application,
                currentVersion,
                next.manifestVersion(),
                hash,
                nextCaps.keySet().stream()
                        .filter(code -> !oldCaps.containsKey(code))
                        .sorted()
                        .toList(),
                oldCaps.keySet().stream()
                        .filter(code -> Objects.equals(oldCaps.get(code), nextCaps.get(code)))
                        .sorted()
                        .toList(),
                displayHash,
                changes,
                violations,
                violations.isEmpty(),
                new ArrayList<>(affected));
    }

    private static List<String> fields(Menu a, Menu b) {
        if (a == null || b == null)
            return List.of("label", "position", "parent", "route", "any_of");
        List<String> fields = new ArrayList<>();
        if (!Objects.equals(a.label(), b.label())) fields.add("label");
        if (!Objects.equals(a.position(), b.position())) fields.add("position");
        if (!Objects.equals(a.parent(), b.parent())) fields.add("parent");
        if (!Objects.equals(a.route(), b.route())) fields.add("route");
        if (!a.anyOf().equals(b.anyOf())) fields.add("any_of");
        return fields;
    }

    private static void descendantCapabilities(
            Map<String, Menu> menus, String root, Set<String> affected) {
        if (!menus.containsKey(root)) return;
        Deque<String> pending = new ArrayDeque<>();
        pending.add(root);
        Set<String> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            String code = pending.removeFirst();
            if (!visited.add(code)) continue;
            affected.addAll(menus.get(code).anyOf());
            menus.values().stream()
                    .filter(menu -> code.equals(menu.parent()))
                    .map(Menu::code)
                    .forEach(pending::addLast);
        }
    }

    private static <T> Map<String, T> index(List<T> values, Function<T, String> key) {
        return values.stream().collect(Collectors.toMap(key, Function.identity()));
    }
}
