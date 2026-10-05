package com.lrj.authz.admin.workspace.application;

import com.lrj.authz.admin.workspace.configuration.WorkspaceProperties;

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

    private WorkspaceAccess() {}

    /** 按原成员组织与平台操作者规则判断工作区访问，选择工作区不产生额外授权。 */
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

    /** 过滤已登记工作区，只返回当前可信登录满足既有访问规则的项目。 */
    public static List<WorkspaceProperties.Item> visible(
            Jwt jwt, List<WorkspaceProperties.Item> all) {
        List<WorkspaceProperties.Item> out = new ArrayList<>();
        for (WorkspaceProperties.Item item : all) {
            if (allowed(jwt, item)) {
                out.add(item);
            }
        }
        return out;
    }

    /** 沿用原平台操作资格判断，不能把任意内部成员自动当作平台管理员。 */
    public static boolean isPlatformOperator(Jwt jwt, String owner) {
        return hasAuthority(jwt, "authz-admin") && "built-in".equals(owner);
    }

    /** 从受治理工作区配置解析原所有者，不能从请求参数推断组织归属。 */
    public static String owner(Jwt jwt) {
        if (jwt == null) {
            return "";
        }
        String owner = jwt.getClaimAsString("owner");
        return owner == null ? "" : owner.trim();
    }

    /** 按既有认证声明与规范化规则检查资格，不能扩大不同组织的授权。 */
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

    /** 合并当前工作区配置的组织条件，保持原单组织与组织集合的兼容语义。 */
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

    /** 沿用原身份规范化规则，资格比较不能在不同入口采用不同表示。 */
    public static String normalizeId(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
}
