package com.lrj.authz.governance.domain;

import java.util.List;

/** 应用清单领域对象，业务能力和菜单来自已发布快照，不读取前端硬编码。 */
public final class CatalogModels {
    private CatalogModels() {}
    /** 与不可变清单分离的紧急状态，未创建前使用版本0。 */
    public record CapabilityState(String capability,boolean disabled,long version) {}
    /** 稳定应用和明确登记的拥有者；入口不从清单自由变更。 */
    public record Application(String applicationId, String ownerPrincipalId, String entryOrigin, long manifestVersion) {}
    /** 能力风险是封闭集合，发布后同编码语义不可改变。 */
    public enum Risk { NORMAL, HIGH }
    /** 能力与资源类型属于同一应用命名空间。 */
    public record Capability(String code, String resourceType, Risk riskLevel) {}
    /** 父菜单只组织展示，业务权限始终逐请求检查。 */
    public record Menu(String code, String parent, String route, List<String> anyOf, String label, Integer position) {
        public Menu { anyOf = anyOf == null ? null : List.copyOf(anyOf); }
        /** 旧调用者仍可构建没有显示元数据的v1菜单。 */
        public Menu(String code, String parent, String route, List<String> anyOf) { this(code,parent,route,anyOf,null,null); }
    }
    /** 名称与顺序仅承载应用Owner的界面事实，不成为新的权限规则。 */
    public record MenuPresentation(String code, String label, Integer position) {}
    /** 展示快照与同版本权限清单分开存储，旧后端可继续读取原始v1JSON。 */
    public record PresentationSnapshot(String applicationId, long version, String presentationHash, String presentationJson) {}
    /** 不可变发布内容；集合防御复制，不能在验收后被调用方修改。 */
    public record Manifest(String schemaVersion, String application, long manifestVersion,
                           List<Capability> capabilities, List<Menu> menus) {
        public Manifest { capabilities = capabilities == null ? null : List.copyOf(capabilities); menus = menus == null ? null : List.copyOf(menus); }
    }
    /** 差异类型来自稳定菜单ID；名称或路由变化不被误认为新菜单。 */
    public enum ChangeKind { ADDED, REMOVED, CHANGED }
    /** 修改前后均保留完整菜单，字段按固定顺序返回以便审查。 */
    public record MenuChange(String code, ChangeKind kind, Menu before, Menu after, List<String> fields) {
        public MenuChange { fields = List.copyOf(fields); }
    }
    /** 发布限制单独展示，语义错误不能通过预览获得发布资格。 */
    public enum ViolationKind { CAPABILITY_REMOVED, CAPABILITY_CHANGED, VERSION_REGRESSION, SAME_VERSION_CHANGED }
    /** 能力冲突明确保留旧／新语义；版本冲突没有能力目标。 */
    public record CatalogViolation(ViolationKind code, String capability, Capability before, Capability after) {}
    /** 预览只报告目录事实及潜在关联能力，不产生角色授予或资源授权证明。 */
    public record Preview(String application, long currentVersion, long proposedVersion, String contentHash,
                          List<String> added, List<String> retained, String presentationHash,
                          List<MenuChange> menuChanges, List<CatalogViolation> violations, boolean publishable,
                          List<String> affectedCapabilities) {
        public Preview {
            added = List.copyOf(added); retained = List.copyOf(retained); menuChanges = List.copyOf(menuChanges);
            violations = List.copyOf(violations); affectedCapabilities = List.copyOf(affectedCapabilities);
        }
    }
    /** 持久化快照保留摘要用于同版本幂等判断。 */
    public record Snapshot(String applicationId, long version, String contentHash, String manifestJson) {}
}
