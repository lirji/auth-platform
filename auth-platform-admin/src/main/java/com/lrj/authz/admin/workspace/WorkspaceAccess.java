package com.lrj.authz.admin.workspace;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 工作区准入：built-in 的 authz-admin 是平台运营，可进全部工作区；
 * 其余身份只能进 JWT owner 命中的组织对应工作区。
 */
public final class WorkspaceAccess {

    private WorkspaceAccess() {
    }

    public static boolean allowed(Jwt jwt, WorkspaceProperties.Item workspace) {
        if (jwt == null || workspace == null) {
            return false;
        }
        String owner = owner(jwt);
        if (isPlatformOperator(jwt, owner)) {
            return true;
        }
        return orgsOf(workspace).contains(owner);
    }

    public static List<WorkspaceProperties.Item> visible(Jwt jwt, List<WorkspaceProperties.Item> all) {
        List<WorkspaceProperties.Item> out = new ArrayList<>();
        for (WorkspaceProperties.Item item : all) {
            if (allowed(jwt, item)) {
                out.add(item);
            }
        }
        return out;
    }

    public static boolean isPlatformOperator(Jwt jwt, String owner) {
        return hasAuthority(jwt, "authz-admin") && "built-in".equals(owner);
    }

    public static String owner(Jwt jwt) {
        if (jwt == null) {
            return "";
        }
        String owner = jwt.getClaimAsString("owner");
        return owner == null ? "" : owner.trim();
    }

    public static boolean hasAuthority(Jwt jwt, String shortName) {
        if (jwt == null) {
            return false;
        }
        Object groups = jwt.getClaim("groups");
        if (!(groups instanceof Collection<?> col)) {
            return false;
        }
        for (Object g : col) {
            String s = String.valueOf(g);
            int i = s.lastIndexOf('/');
            String shortGroup = i >= 0 ? s.substring(i + 1) : s;
            if (shortName.equalsIgnoreCase(shortGroup)) {
                return true;
            }
        }
        return false;
    }

    public static Set<String> orgsOf(WorkspaceProperties.Item workspace) {
        Set<String> orgs = new LinkedHashSet<>();
        if (workspace.getOrganization() != null && !workspace.getOrganization().isBlank()) {
            orgs.add(workspace.getOrganization().trim());
        }
        if (workspace.getOrganizations() != null) {
            for (String org : workspace.getOrganizations()) {
                if (org != null && !org.isBlank()) {
                    orgs.add(org.trim());
                }
            }
        }
        return orgs;
    }

    public static String normalizeId(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
}
