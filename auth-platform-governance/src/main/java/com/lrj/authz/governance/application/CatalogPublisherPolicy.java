package com.lrj.authz.governance.application;

import com.lrj.authz.governance.domain.CatalogModels.*;
import com.lrj.authz.governance.domain.CatalogPublisherModels.Eligibility;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 自动化只接受可证明不改变业务执行入口的变更；能力声明的扩大必须由真实Owner处理。 */
public final class CatalogPublisherPolicy {
    private CatalogPublisherPolicy() {}

    private record Entry(String route, List<String> anyOf) {}

    /** 完整能力定义和有业务意义的菜单保持；纯分组父级和展示不成为权限。 */
    public static Eligibility classify(Manifest current, Manifest candidate) {
        var normalized = CatalogManifest.normalize(candidate);
        if (current == null) return Eligibility.REQUIRES_OWNER_REVIEW;
        var previous = CatalogManifest.normalize(current);
        if (!previous.application().equals(normalized.application())
                || !previous.capabilities().equals(normalized.capabilities())
                || !entries(previous).equals(entries(normalized)))
            return Eligibility.REQUIRES_OWNER_REVIEW;
        return Eligibility.AUTOMATION_ALLOWED;
    }

    private static Map<String, Entry> entries(Manifest manifest) {
        Map<String, Entry> entries = new HashMap<>();
        // 无执行路由且不绑定能力的节点仅组织显示；其他节点即使当前无Grant也必须保持。
        for (var menu : manifest.menus())
            if (menu.route() != null || !menu.anyOf().isEmpty()) {
                entries.put(menu.code(), new Entry(menu.route(), menu.anyOf()));
            }
        return Map.copyOf(entries);
    }
}
