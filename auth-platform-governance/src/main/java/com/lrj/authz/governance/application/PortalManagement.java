package com.lrj.authz.governance.application;

import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.AccessModels.Partition;
import com.lrj.authz.governance.persistence.*;
import com.lrj.authz.protocol.PortalDtos.*;
import com.lrj.authz.protocol.RequestDtos.Page;
import java.util.List;

/** 管理表单与版本影响的有界读模型；复用原委派校验，不能自造管理权。 */
public final class PortalManagement {
    private static final int PAGE_SIZE = 100;
    private final AccessManagement access;
    private final IdentityGovernance identity;
    private final AccessMapper grants;
    private final CatalogMapper catalog;
    private final PortalMapper mapper;

    /** 只读取本服务权威关系数据，普通读接口不调用外部工作流。 */
    public PortalManagement(AccessManagement access, IdentityGovernance identity, AccessMapper grants, CatalogMapper catalog, PortalMapper mapper) {
        this.access = access; this.identity = identity; this.grants = grants; this.catalog = catalog; this.mapper = mapper;
    }

    /** 所有表单选项来自当前清单和委派交集，禁用能力保留明确状态但不可新授予。 */
    public Management management(VerifiedLogin login, Partition p) {
        var ceiling = access.authority(login, p);
        var app = catalog.application(p.applicationId());
        if (app == null || app.manifestVersion() == 0) throw new GovernanceException(GovernanceException.Code.ACCESS_DENIED);
        var allowed = AccessValues.read(ceiling.capabilitiesJson());
        var disabled = mapper.disabledCapabilities(p.applicationId());
        var capabilities = CatalogManifest.read(catalog.snapshot(p.applicationId(), app.manifestVersion()).manifestJson()).capabilities().stream()
                .filter(c -> allowed.contains(c.code())).map(c -> new Capability(c.code(), c.resourceType(), c.riskLevel().name(), disabled.contains(c.code()))).toList();
        var progress = mapper.progress(p);
        return new Management(ceiling.membershipId(), ceiling.generation(), ceiling.maxDurationSeconds(), capabilities,
                app.ownerPrincipalId().equals(identity.principalForLogin(login.issuer(), login.subject()).id()), app.manifestVersion(),
                progress == null ? "LEGACY" : progress.policyState(), progress == null ? "LEGACY" : progress.directoryState(),
                progress == null ? null : progress.desiredEpoch(), progress == null ? null : progress.appliedEpoch());
    }

    /** 目标成员查询也须有当前分区委派，完整租户谓词在Mapper中再次执行。 */
    public Page<Member> members(VerifiedLogin login, Partition p, String after) {
        access.authority(login, p);
        if (after != null && !after.isEmpty()) BootstrapCommand.uuid(after);
        var rows = mapper.members(p.tenantId(), after == null ? "" : after);
        return new Page<>(List.copyOf(rows.subList(0, Math.min(rows.size(), PAGE_SIZE))), rows.size() > PAGE_SIZE ? rows.get(PAGE_SIZE - 1).membershipId() : null);
    }

    /** 固定版本比较限定同一分区与角色编码，不能通过UUID探测其他应用角色。 */
    public RoleImpact roleImpact(VerifiedLogin login, Partition p, String roleId) {
        access.authority(login, p); BootstrapCommand.uuid(roleId);
        var role = grants.role(p, roleId);
        if (role == null) throw new GovernanceException(GovernanceException.Code.ACCESS_DENIED);
        var previous = mapper.previousRole(p, role.roleCode(), role.version());
        var current = AccessValues.read(role.capabilitiesJson());
        List<String> before = previous == null ? List.of() : AccessValues.read(previous.capabilitiesJson());
        return new RoleImpact(roleId, previous == null ? null : previous.id(), current.stream().filter(c -> !before.contains(c)).toList(),
                before.stream().filter(c -> !current.contains(c)).toList(), mapper.referencingGrants(p, roleId));
    }
}
