package com.lrj.authz.governance.portal.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.authz.governance.access.application.AccessManagement;
import com.lrj.authz.governance.access.domain.AccessModels.Partition;
import com.lrj.authz.governance.access.persistence.AccessMapper;
import com.lrj.authz.governance.catalog.application.CatalogManifest;
import com.lrj.authz.governance.catalog.persistence.CatalogMapper;
import com.lrj.authz.governance.identity.application.BootstrapCommand;
import com.lrj.authz.governance.identity.application.IdentityGovernance;
import com.lrj.authz.governance.identity.authentication.VerifiedLogin;
import com.lrj.authz.governance.portal.domain.PortalCatalogModels.*;
import com.lrj.authz.governance.portal.persistence.PortalMapper;
import com.lrj.authz.governance.shared.application.AccessValues;
import com.lrj.authz.governance.shared.application.GovernanceException;
import com.lrj.authz.protocol.PortalDtos.*;
import com.lrj.authz.protocol.RequestDtos.Page;
import com.lrj.authz.protocol.ScopeDtos.Kind;
import com.lrj.authz.protocol.ScopeResourceBindings;

import java.util.*;

/** 管理表单与版本影响的有界读模型；复用原委派校验，不能自造管理权。 */
public final class PortalManagement {
    private static final int PAGE_SIZE = 100;
    private static final ObjectMapper STATE_JSON = new ObjectMapper();
    private final AccessManagement access;
    private final IdentityGovernance identity;
    private final AccessMapper grants;
    private final CatalogMapper catalog;
    private final PortalMapper mapper;

    /** 只读取本服务权威关系数据，普通读接口不调用外部工作流。 */
    public PortalManagement(
            AccessManagement access,
            IdentityGovernance identity,
            AccessMapper grants,
            CatalogMapper catalog,
            PortalMapper mapper) {
        this.access = access;
        this.identity = identity;
        this.grants = grants;
        this.catalog = catalog;
        this.mapper = mapper;
    }

    /** 两次新SQL基准重验资格；完整菜单可读不意味着可授予其全部能力。 */
    public PublishedCatalog publishedCatalog(VerifiedLogin login, Partition p) {
        var initial = access.authority(login, p);
        var before = mapper.publishedCatalogBasis(p, login.issuer(), login.subject());
        requireBasis(before, initial);
        var manifest = CatalogManifest.read(before.manifestJson());
        try {
            manifest =
                    CatalogManifest.withPresentation(
                            manifest, before.presentationJson(), before.presentationHash());
        } catch (GovernanceException corrupt) {
            throw new GovernanceException(GovernanceException.Code.DEPENDENCY_UNAVAILABLE);
        }
        if (!manifest.application().equals(p.applicationId())
                || manifest.manifestVersion() != before.manifestVersion()
                || !CatalogManifest.hash(manifest).equals(before.contentHash())) {
            throw new GovernanceException(GovernanceException.Code.DEPENDENCY_UNAVAILABLE);
        }
        var allowed = AccessValues.read(before.capabilitiesJson());
        var states =
                states(
                        before.capabilityStatesJson(),
                        manifest.capabilities().stream().map(c -> c.code()).toList());
        var lifecycle =
                lifecycles(
                        before.lifecycleStatesJson(),
                        manifest.capabilities().stream().map(c -> c.code()).toList());
        var capabilities =
                manifest.capabilities().stream()
                        .map(
                                c -> {
                                    var state = states.get(c.code());
                                    boolean disabled = state != null && state.disabled();
                                    var life = lifecycle.get(c.code());
                                    var stateCode =
                                            life == null
                                                    ? com.lrj.authz.protocol.CapabilityLifecycleDtos
                                                            .State.ACTIVE
                                                    : com.lrj.authz.protocol.CapabilityLifecycleDtos
                                                            .State.valueOf(life.state());
                                    return new PublishedCapability(
                                            c.code(),
                                            c.resourceType(),
                                            c.riskLevel().name(),
                                            disabled,
                                            allowed.contains(c.code())
                                                    && !disabled
                                                    && stateCode
                                                            == com.lrj.authz.protocol
                                                                    .CapabilityLifecycleDtos.State
                                                                    .ACTIVE,
                                            stateCode,
                                            life == null ? 0L : life.version(),
                                            life == null ? null : life.reason());
                                })
                        .toList();
        var resources =
                capabilities.stream()
                        .map(PublishedCapability::resourceType)
                        .distinct()
                        .sorted()
                        .map(
                                type ->
                                        new PublishedResourceType(
                                                type,
                                                ScopeResourceBindings.supports(type),
                                                Arrays.stream(Kind.values())
                                                        .filter(
                                                                kind ->
                                                                        ScopeResourceBindings
                                                                                .allows(type, kind))
                                                        .sorted(Comparator.comparing(Enum::name))
                                                        .toList()))
                        .toList();
        var menus =
                manifest.menus().stream()
                        .map(
                                m ->
                                        new PublishedMenu(
                                                m.code(),
                                                m.parent(),
                                                m.route(),
                                                m.anyOf(),
                                                m.label(),
                                                m.position()))
                        .toList();
        // 不能把清单摘要当作选项版本：委派缩减、成员变化和紧急开关也必须使旧选择失效。
        String viewHash =
                AccessValues.hash(
                        p.tenantId(),
                        p.applicationId(),
                        p.environment(),
                        before.principalId(),
                        before.principalVersion(),
                        before.tenantVersion(),
                        before.membershipId(),
                        before.generation(),
                        before.membershipVersion(),
                        AccessValues.json(allowed),
                        before.maxDurationSeconds(),
                        before.manifestVersion(),
                        before.contentHash(),
                        before.capabilityStatesJson(),
                        before.presentationHash(),
                        before.lifecycleStatesJson(),
                        before.ownerPrincipalId());
        var latest = access.authority(login, p);
        var after = mapper.publishedCatalogBasis(p, login.issuer(), login.subject());
        requireBasis(after, latest);
        if (!before.equals(after))
            throw new GovernanceException(GovernanceException.Code.VERSION_CONFLICT);
        return new PublishedCatalog(
                p.tenantId(),
                p.applicationId(),
                p.environment(),
                before.membershipId(),
                before.generation(),
                before.maxDurationSeconds(),
                before.manifestVersion(),
                before.contentHash(),
                viewHash,
                menus,
                capabilities,
                resources,
                before.principalId().equals(before.ownerPrincipalId()));
    }

    /** 单语句资格必须与原管理用例一致，晚到撤权不允许返回旧选项。 */
    private static void requireBasis(
            PublishedCatalogBasis basis,
            com.lrj.authz.governance.access.domain.AccessModels.Delegation authority) {
        if (basis == null) throw new GovernanceException(GovernanceException.Code.ACCESS_DENIED);
        if (!basis.membershipId().equals(authority.membershipId())
                || basis.generation() != authority.generation()
                || basis.maxDurationSeconds() != authority.maxDurationSeconds()
                || !AccessValues.read(basis.capabilitiesJson())
                        .equals(AccessValues.read(authority.capabilitiesJson()))) {
            throw new GovernanceException(GovernanceException.Code.VERSION_CONFLICT);
        }
    }

    /** 紧急状态只接受当前清单内的有限typed行，损坏数据不能静默当作未停用。 */
    private static Map<String, CapabilityState> states(String json, List<String> codes) {
        try {
            var values = STATE_JSON.readValue(json, CapabilityState[].class);
            if (values.length > 200) throw new IllegalArgumentException();
            Map<String, CapabilityState> result = new HashMap<>();
            for (var state : values) {
                if (state == null
                        || state.version() < 1
                        || !codes.contains(state.capability())
                        || result.put(state.capability(), state) != null)
                    throw new IllegalArgumentException();
            }
            return Map.copyOf(result);
        } catch (Exception failure) {
            throw new GovernanceException(GovernanceException.Code.DEPENDENCY_UNAVAILABLE);
        }
    }

    /** 未知生命周期或损坏行必须显式失败，不能扩大新授权选项。 */
    private static Map<
                    String,
                    com.lrj.authz.governance.portal.domain.PortalCatalogModels.LifecycleState>
            lifecycles(String json, List<String> codes) {
        try {
            var values =
                    STATE_JSON.readValue(
                            json,
                            com.lrj.authz.governance.portal.domain.PortalCatalogModels
                                                    .LifecycleState
                                            []
                                    .class);
            if (values.length > 200) throw new IllegalArgumentException();
            Map<String, com.lrj.authz.governance.portal.domain.PortalCatalogModels.LifecycleState>
                    result = new HashMap<>();
            for (var value : values) {
                if (value == null
                        || value.version() < 1
                        || !codes.contains(value.capability())
                        || value.reason() == null
                        || value.reason().isBlank()
                        || value.reason().length() > 500
                        || Arrays.stream(
                                        com.lrj.authz.protocol.CapabilityLifecycleDtos.State
                                                .values())
                                .noneMatch(state -> state.code().equals(value.state()))
                        || result.put(value.capability(), value) != null)
                    throw new IllegalArgumentException();
            }
            return Map.copyOf(result);
        } catch (Exception failure) {
            throw new GovernanceException(GovernanceException.Code.DEPENDENCY_UNAVAILABLE);
        }
    }

    /** 所有表单选项来自当前清单和委派交集，禁用能力保留明确状态但不可新授予。 */
    public Management management(VerifiedLogin login, Partition p) {
        var ceiling = access.authority(login, p);
        var app = catalog.application(p.applicationId());
        if (app == null || app.manifestVersion() == 0)
            throw new GovernanceException(GovernanceException.Code.ACCESS_DENIED);
        var allowed = AccessValues.read(ceiling.capabilitiesJson());
        var disabled = mapper.disabledCapabilities(p.applicationId());
        var capabilities =
                CatalogManifest.read(
                                catalog.snapshot(p.applicationId(), app.manifestVersion())
                                        .manifestJson())
                        .capabilities()
                        .stream()
                        .filter(c -> allowed.contains(c.code()))
                        .map(
                                c ->
                                        new Capability(
                                                c.code(),
                                                c.resourceType(),
                                                c.riskLevel().name(),
                                                disabled.contains(c.code())))
                        .toList();
        var progress = mapper.progress(p);
        return new Management(
                ceiling.membershipId(),
                ceiling.generation(),
                ceiling.maxDurationSeconds(),
                capabilities,
                app.ownerPrincipalId()
                        .equals(identity.principalForLogin(login.issuer(), login.subject()).id()),
                app.manifestVersion(),
                progress == null ? "LEGACY" : progress.policyState(),
                progress == null ? "LEGACY" : progress.directoryState(),
                progress == null ? null : progress.desiredEpoch(),
                progress == null ? null : progress.appliedEpoch());
    }

    /** 目标成员查询也须有当前分区委派，完整租户谓词在Mapper中再次执行。 */
    public Page<Member> members(VerifiedLogin login, Partition p, String after) {
        access.authority(login, p);
        if (after != null && !after.isEmpty()) BootstrapCommand.uuid(after);
        var rows = mapper.members(p.tenantId(), after == null ? "" : after);
        return new Page<>(
                List.copyOf(rows.subList(0, Math.min(rows.size(), PAGE_SIZE))),
                rows.size() > PAGE_SIZE ? rows.get(PAGE_SIZE - 1).membershipId() : null);
    }

    /** 管理配置目录与普通申请目录分开；不会把审批人员返回给普通成员。 */
    public Page<com.lrj.authz.governance.approval.domain.RequestModels.Policy> policies(
            VerifiedLogin login, Partition p, String after) {
        access.authority(login, p);
        if (after != null && !after.isEmpty()) BootstrapCommand.uuid(after);
        var rows = mapper.policies(p, after == null ? "" : after);
        return new Page<>(
                rows.subList(0, Math.min(100, rows.size())),
                rows.size() > 100 ? rows.get(99).id() : null);
    }

    /** 固定版本比较限定同一分区与角色编码，不能通过UUID探测其他应用角色。 */
    public RoleImpact roleImpact(VerifiedLogin login, Partition p, String roleId) {
        access.authority(login, p);
        BootstrapCommand.uuid(roleId);
        var role = grants.role(p, roleId);
        if (role == null) throw new GovernanceException(GovernanceException.Code.ACCESS_DENIED);
        var previous = mapper.previousRole(p, role.roleCode(), role.version());
        var current = AccessValues.read(role.capabilitiesJson());
        List<String> before =
                previous == null ? List.of() : AccessValues.read(previous.capabilitiesJson());
        return new RoleImpact(
                roleId,
                previous == null ? null : previous.id(),
                current.stream().filter(c -> !before.contains(c)).toList(),
                before.stream().filter(c -> !current.contains(c)).toList(),
                mapper.referencingGrants(p, roleId));
    }
}
