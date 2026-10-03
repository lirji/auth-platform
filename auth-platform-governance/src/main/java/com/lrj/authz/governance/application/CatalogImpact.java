package com.lrj.authz.governance.application;

import com.fasterxml.jackson.databind.*;
import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.AccessModels.Partition;
import com.lrj.authz.governance.domain.CatalogImpactModels.*;
import com.lrj.authz.governance.domain.CatalogModels.Manifest;
import com.lrj.authz.governance.persistence.CatalogImpactMapper;
import com.lrj.authz.governance.persistence.PortalMapper;
import java.util.*;
import static com.lrj.authz.governance.application.GovernanceException.Code.*;

/** 有界潜在影响分析，仅由PortalPermissions独立诊断入口调用，不签发业务授权。 */
public final class CatalogImpact {
    private static final int PAGE_SIZE = 100, BASIS_LIMIT = 10000;
    private static final ObjectMapper JSON = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
    private final CatalogImpactMapper mapper;
    private final PortalMapper portal;
    /** 存储范围使用既有camelCase编码；必须通过原范围解释器转换，不能按公开snake_case直接猜读。 */
    private record SourceRow(String grantId, String roleId, String memberId, Long generation, String groupId, String sourceType,
                             String sourceId, String validFrom, String validTo, String grantState, String scope, String scopeRuleJson) {}
    /** 分区基准与聚合均复用真实治理Mapper，不读取页面缓存。 */
    public CatalogImpact(CatalogImpactMapper mapper, PortalMapper portal) { this.mapper = mapper; this.portal = portal; }

    /** 两个新SQL快照核对基准；主体诊断授权由调用入口在本方法前后独立检查。 */
    public Report analyze(VerifiedLogin login, Partition p, Manifest candidate, Cursor inputCursor) {
        AccessValues.partition(p);
        Manifest next = CatalogManifest.normalize(candidate);
        if (!p.applicationId().equals(next.application())) throw new GovernanceException(INVALID_ARGUMENT);
        Cursor cursor = cursor(inputCursor);
        var base = portal.publishedCatalogBasis(p, login.issuer(), login.subject());
        if (base == null) throw new GovernanceException(ACCESS_DENIED);
        Manifest old;
        try {
            old = CatalogManifest.withPresentation(CatalogManifest.read(base.manifestJson()), base.presentationJson(), base.presentationHash());
            if (!old.application().equals(p.applicationId()) || old.manifestVersion() != base.manifestVersion()
                    || !CatalogManifest.hash(old).equals(base.contentHash())) throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
        } catch (GovernanceException corrupt) { throw new GovernanceException(DEPENDENCY_UNAVAILABLE); }
        var diff = CatalogDiff.compare(p.applicationId(), base.manifestVersion(), old, next);
        var row = query(login, p, diff.affectedCapabilities(), cursor);
        if (row.manifestVersion() != base.manifestVersion() || !row.contentHash().equals(base.contentHash())
                || !Objects.equals(row.presentationHash(), base.presentationHash())) throw new GovernanceException(VERSION_CONFLICT);
        boolean complete = row.basisSize() <= BASIS_LIMIT;
        String basis = complete ? AccessValues.hash(p.tenantId(), p.applicationId(), p.environment(), diff.contentHash(), diff.presentationHash(), row.basisJson(), row.factsHash()) : null;
        if (inputCursor != null && !Objects.equals(inputCursor.basisHash(), basis)) throw new GovernanceException(VERSION_CONFLICT);
        var roles = values(row.rolesJson(), Role[].class);
        List<Source> sources;
        try {
            sources = values(row.sourcesJson(), SourceRow[].class).stream().map(source -> new Source(source.grantId(), source.roleId(), source.memberId(),
                    source.generation(), source.groupId(), source.sourceType(), source.sourceId(), source.validFrom(), source.validTo(), source.grantState(),
                    source.scope(), source.scopeRuleJson() == null ? null : ScopeRules.decode(source.scopeRuleJson()))).toList();
        } catch (GovernanceException corrupt) { throw new GovernanceException(DEPENDENCY_UNAVAILABLE); }
        var policies = values(row.policiesJson(), Policy[].class);
        Fences fences;
        try { fences = JSON.readValue(row.fencesJson(), Fences.class); }
        catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException corrupt) { throw new GovernanceException(DEPENDENCY_UNAVAILABLE); }
        // 重新读取发生在首个聚合语句之后，不能在REPEATABLE_READ里只重读原快照假装新资格。
        var latest = query(login, p, diff.affectedCapabilities(), cursor);
        if (!row.basisJson().equals(latest.basisJson()) || !row.factsHash().equals(latest.factsHash())
                || row.basisSize() != latest.basisSize() || !stats(row).equals(stats(latest))
                || !row.rolesJson().equals(latest.rolesJson()) || !row.sourcesJson().equals(latest.sourcesJson())
                || !row.policiesJson().equals(latest.policiesJson())) throw new GovernanceException(VERSION_CONFLICT);
        boolean more = roles.size() > PAGE_SIZE || sources.size() > PAGE_SIZE || policies.size() > PAGE_SIZE;
        var rolePage = page(roles); var sourcePage = page(sources); var policyPage = page(policies);
        Cursor after = null;
        if (complete && more) {
            Source last = sourcePage.isEmpty() ? null : sourcePage.getLast();
            after = new Cursor(basis, rolePage.isEmpty() ? cursor.afterRole() : rolePage.getLast().id(),
                    last == null ? cursor.afterGrant() : last.grantId(), last == null ? cursor.afterMember() : Objects.requireNonNullElse(last.memberId(), ""),
                    policyPage.isEmpty() ? cursor.afterPolicy() : policyPage.getLast().id());
        }
        return new Report(p.tenantId(), p.applicationId(), p.environment(), row.manifestVersion(), diff.contentHash(), diff.presentationHash(),
                row.observedAt(), basis, complete ? Completeness.COMPLETE : Completeness.BASIS_LIMIT_EXCEEDED,
                diff, stats(row), fences, rolePage, sourcePage, policyPage, after);
    }

    private CatalogImpactMapper.Row query(VerifiedLogin login, Partition p, List<String> capabilities, Cursor cursor) {
        var row = mapper.report(p, login.issuer(), login.subject(), capabilities, cursor, PAGE_SIZE + 1, BASIS_LIMIT + 1);
        if (row == null) throw new GovernanceException(ACCESS_DENIED);
        return row;
    }
    private static Stats stats(CatalogImpactMapper.Row r) {
        return new Stats(r.roleCount(), r.activeGrantCount(), r.pendingGrantCount(), r.activePeopleCount(), r.pendingPeopleCount(), r.peopleCount(), r.groupGrantCount(), r.requestPolicyCount());
    }
    private static Cursor cursor(Cursor value) {
        if (value == null) return new Cursor(null, "", "", "", "");
        if (value.basisHash() == null || !value.basisHash().matches("[0-9a-f]{64}")) throw new GovernanceException(INVALID_ARGUMENT);
        for (String position : Arrays.asList(value.afterRole(), value.afterGrant(), value.afterMember(), value.afterPolicy())) {
            if (position == null) throw new GovernanceException(INVALID_ARGUMENT);
            if (!position.isEmpty()) BootstrapCommand.uuid(position);
        }
        if (!value.afterMember().isEmpty() && value.afterGrant().isEmpty()) throw new GovernanceException(INVALID_ARGUMENT);
        return value;
    }
    private static <T> List<T> values(String json, Class<T[]> type) {
        try {
            T[] values = JSON.readValue(json, type);
            if (values == null || values.length > PAGE_SIZE + 1 || Arrays.stream(values).anyMatch(Objects::isNull)) throw new IllegalArgumentException();
            return List.of(values);
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException corrupt) { throw new GovernanceException(DEPENDENCY_UNAVAILABLE); }
    }
    private static <T> List<T> page(List<T> values) { return List.copyOf(values.subList(0, Math.min(PAGE_SIZE, values.size()))); }
}
