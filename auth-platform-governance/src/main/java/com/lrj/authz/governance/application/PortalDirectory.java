package com.lrj.authz.governance.application;

import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.AccessModels.Partition;
import com.lrj.authz.governance.persistence.IdentityMapper;
import com.lrj.authz.governance.persistence.PortalMapper;
import java.util.ArrayList;
import java.util.List;

/** 门户只组合本人目录与当前授权提示，不把管理委派转换为业务权限。 */
public final class PortalDirectory {
    private static final int PAGE_SIZE = 20;
    private static final long PAGE_BUDGET_NANOS = 10_000_000_000L;
    private final IdentityGovernance identity;
    private final IdentityMapper identities;
    private final PortalMapper mapper;
    private final AccessPresentation presentation;

    /** 复用治理数据源和既有判权端口，不读取OA或商城数据库。 */
    public PortalDirectory(IdentityGovernance identity, IdentityMapper identities, PortalMapper mapper,
                           AccessPresentation presentation) {
        this.identity = identity; this.identities = identities; this.mapper = mapper; this.presentation = presentation;
    }

    /** 租户名称来自已验证成员对应的权威租户记录，不允许浏览器自行声明。 */
    public List<Organization> organizations(VerifiedLogin login) {
        return identity.membershipsForLogin(login.issuer(), login.subject()).stream().map(member ->
                new Organization(member.id(), member.tenantId(), identities.tenant(member.tenantId()).code(),
                        member.memberKind().code(), member.generation())).toList();
    }

    /** 关联候选有界查询；真正可跳转菜单仍须当次图与SQL允许，历史关联仅保留查询上下文。 */
    public Applications applications(VerifiedLogin login, String tenant, String after) {
        var current = identity.contextForLogin(login.issuer(), login.subject(), tenant, null);
        String application = "", environment = "";
        if (after != null && !after.isEmpty()) {
            String[] parts = after.split("/", -1);
            if (parts.length != 2) throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
            AccessValues.partition(new Partition(tenant, parts[0], parts[1]));
            application = parts[0]; environment = parts[1];
        }
        var candidates = mapper.applications(tenant, current.membershipId(), current.membershipGeneration(), application, environment);
        List<Application> result = new ArrayList<>();
        long deadline = System.nanoTime() + PAGE_BUDGET_NANOS;
        for (var candidate : candidates.subList(0, Math.min(PAGE_SIZE, candidates.size()))) {
            if (System.nanoTime() >= deadline) throw new GovernanceException(GovernanceException.Code.DEPENDENCY_UNAVAILABLE);
            try {
                var view = presentation.current(login, new Partition(tenant, candidate.applicationId(), candidate.environment()));
                result.add(new Application(candidate.applicationId(), candidate.environment(), candidate.management(), view.menus(), view.capabilityHints(),
                        view.capabilityHints().isEmpty() ? EntryState.NO_ACCESS : EntryState.AVAILABLE));
            } catch (GovernanceException failure) {
                if (failure.code() != GovernanceException.Code.DEPENDENCY_UNAVAILABLE
                        && failure.code() != GovernanceException.Code.AUTHZ_STATE_NOT_READY) throw failure;
                // 只保留已验证的本人关联，业务入口全部关闭；申请/管理进度不能因投影故障而消失。
                result.add(new Application(candidate.applicationId(), candidate.environment(), candidate.management(), List.of(), List.of(), EntryState.UNAVAILABLE));
            }
        }
        if (System.nanoTime() >= deadline) throw new GovernanceException(GovernanceException.Code.DEPENDENCY_UNAVAILABLE);
        // 图调用期间可能停用或重建成员；迟到结果不能带回旧身份上下文的入口。
        var latest = identity.contextForLogin(login.issuer(), login.subject(), tenant, current.membershipGeneration());
        if (!latest.membershipId().equals(current.membershipId()) || latest.membershipVersion() != current.membershipVersion()
                || latest.principalVersion() != current.principalVersion()) {
            throw new GovernanceException(GovernanceException.Code.VERSION_CONFLICT);
        }
        String next = candidates.size() > PAGE_SIZE ? candidates.get(PAGE_SIZE - 1).applicationId() + "/" + candidates.get(PAGE_SIZE - 1).environment() : null;
        return new Applications(List.copyOf(result), next);
    }

    /** 只返回本人可选组织，不含任何其他员工目录。 */
    public record Organization(String membershipId, String tenantId, String tenantCode, String memberKind, long generation) {}
    /** 候选关联不是业务准入，是否管理也必须来自当前代际委派。 */
    public record Candidate(String applicationId, String environment, boolean management) {}
    /** 应用卡片只包含当前允许的菜单链接，不发放Token或服务密钥。 */
    public record Application(String applicationId, String environment, boolean management,
                              List<AccessPresentation.Menu> menus, List<String> capabilityHints, EntryState entryState) {}
    /** 错误不能变成无权限，更不能沿用旧入口；显式状态仅影响展示。 */
    public enum EntryState { AVAILABLE, NO_ACCESS, UNAVAILABLE }
    /** 当前页和有界游标，不推断不可见目录总数。 */
    public record Applications(List<Application> items, String nextCursor) {}
}
