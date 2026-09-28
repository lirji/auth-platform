package com.lrj.authz.governance.application;

import com.lrj.authz.governance.domain.AccessModels.Partition;
import com.lrj.authz.governance.domain.ScopeModels.*;
import com.lrj.authz.governance.persistence.*;
import com.lrj.authz.protocol.GovernanceDtos.AccessContext;
import com.lrj.authz.protocol.ScopeDtos.*;
import com.lrj.authz.protocol.StrictGraphReader;
import com.lrj.authz.protocol.ProjectionGraph;
import java.time.Instant;
import java.util.*;
import static com.lrj.authz.governance.application.GovernanceException.Code.*;

/** 主库A→策略/目录两次图检查→主库新快照C，所有条件同一路径成立才给出范围。 */
public final class ReliableAuthorization {
    private final ReadFence fences;
    private final ScopeMapper scopes;
    private final CatalogMapper catalog;
    private final AccessMapper access;
    private final StrictGraphReader graph;

    /** 依赖均由同一治理Runtime装配，不接受OA同步调用或跨请求ALLOW缓存。 */
    public ReliableAuthorization(ReadFence fences, ScopeMapper scopes, CatalogMapper catalog, AccessMapper access, StrictGraphReader graph) {
        this.fences=fences;this.scopes=scopes;this.catalog=catalog;this.access=access;this.graph=graph;
    }

    /** 完整结果有界且一次性使用；策略变化或协议异常不能降级为部分允许。 */
    public Evaluation evaluate(AccessContext context,String capability,String resourceType) {
        CatalogManifest.code(capability);CatalogManifest.code(resourceType);
        Partition partition=new Partition(context.tenantId(),context.applicationId(),context.environment());AccessValues.partition(partition);
        long deadline=System.nanoTime()+8_000_000_000L;
        var before=fences.snapshot(context,()->candidates(context,partition,capability,resourceType));
        var candidates=before.data();var ids=candidates.grants().stream().map(GrantPath::id).toList();
        Map<String,Boolean> policy,directory;
        try {
            policy=graph.checkEligible(context.membershipId(),context.membershipGeneration(),ids,before.stamp().policyToken());
            deadline(deadline);
            directory=graph.checkEligible(context.membershipId(),context.membershipGeneration(),ids,before.stamp().directoryToken());
        }catch(ProjectionGraph.Failure failure){
            throw new GovernanceException(failure.code()==ProjectionGraph.Failure.Code.PROTOCOL_INVALID?AUTHZ_PROTOCOL_INVALID:DEPENDENCY_UNAVAILABLE);
        }
        deadline(deadline);
        if(policy.size()!=ids.size()||directory.size()!=ids.size()||!policy.keySet().equals(new HashSet<>(ids))||!directory.keySet().equals(policy.keySet()))throw new GovernanceException(AUTHZ_PROTOCOL_INVALID);
        var after=fences.snapshot(context,()->candidates(context,partition,capability,resourceType));
        fences.requireSame(before.stamp(),after.stamp());
        if(!candidates.grants().equals(after.data().grants()))throw new GovernanceException(AUTHZ_STATE_NOT_READY);
        Set<String> eligible=new HashSet<>();
        for(String id:ids){
            if(policy.get(id)==null||directory.get(id)==null)throw new GovernanceException(AUTHZ_PROTOCOL_INVALID);
            if(policy.get(id)&&directory.get(id))eligible.add(id);
        }
        var alternatives=ScopeRules.select(capability,resourceType,candidates.grants().stream().map(g->new ScopeRules.Candidate(g.id(),AccessValues.read(g.capabilitiesJson()),rule(g,resourceType))).toList(),eligible);
        Instant until=after.data().now().plusSeconds(30);
        for(GrantPath grant:candidates.grants())if(eligible.contains(grant.id())&&grant.validTo().isBefore(until))until=grant.validTo();
        deadline(deadline);
        return new Evaluation(UUID.randomUUID().toString(),after.stamp(),alternatives,until);
    }

    /** 可信事实来自资源Owner，租户及每条范围路径必须再次匹配。 */
    public boolean allowed(AccessContext context,String capability,Facts facts) {
        if(facts==null||!context.tenantId().equals(facts.tenantId()))return false;
        var result=evaluate(context,capability,facts.resourceType());
        return ScopeRules.matches(context.tenantId(),context.principalId(),result.alternatives(),facts);
    }

    private Candidates candidates(AccessContext context,Partition partition,String capability,String resourceType) {
        var app=catalog.application(partition.applicationId());
        if(app==null||app.manifestVersion()==0||catalog.disabled(partition.applicationId(),capability))return new Candidates(List.of(),access.now());
        var manifest=CatalogManifest.read(catalog.snapshot(app.applicationId(),app.manifestVersion()).manifestJson());
        if(manifest.capabilities().stream().noneMatch(c->c.code().equals(capability)&&c.resourceType().equals(resourceType)))return new Candidates(List.of(),access.now());
        List<GrantPath> all=scopes.eligible(partition,context.membershipId(),context.membershipGeneration());
        if(all.size()>100)throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
        return new Candidates(all.stream().filter(g->AccessValues.read(g.capabilitiesJson()).contains(capability)).toList(),access.now());
    }
    private Rule rule(GrantPath grant,String resource) {
        return switch(grant.scope()) {
            case "TENANT_ALL" -> ScopeRules.validated(new Rule(1,resource,List.of(new Clause(Kind.TENANT_ALL,List.of(),false))));
            case "SCOPED" -> ScopeRules.decode(grant.ruleJson());
            default -> throw new GovernanceException(SCOPE_UNSUPPORTED);
        };
    }
    private static void deadline(long deadline){if(System.nanoTime()>=deadline)throw new GovernanceException(DEPENDENCY_UNAVAILABLE);}
    private record Candidates(List<GrantPath> grants,Instant now){}
}
