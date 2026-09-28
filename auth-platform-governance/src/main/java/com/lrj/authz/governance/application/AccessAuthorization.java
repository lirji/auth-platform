package com.lrj.authz.governance.application;

import com.lrj.authz.governance.domain.AccessModels.*;
import com.lrj.authz.governance.persistence.*;
import com.lrj.authz.protocol.*;
import com.lrj.authz.protocol.GovernanceDtos.AccessContext;
import static com.lrj.authz.governance.application.GovernanceException.Code.*;

/** 同一Grant的SQL资格与图eligible共同成立才允许，不组合不同来源的能力和范围。 */
public final class AccessAuthorization {
    private final AccessMapper access;private final ProjectionMapper projection;private final CatalogMapper catalog;private final AuthzEngine graph;
    /** 每次检查都回源，没有跨请求ALLOW缓存。 */
    public AccessAuthorization(AccessMapper access,ProjectionMapper projection,CatalogMapper catalog,AuthzEngine graph){this.access=access;this.projection=projection;this.catalog=catalog;this.graph=graph;}
    /** 输入上下文必须来自InternalContextService；故障和明确拒绝保持不同语义。 */
    public boolean allowed(AccessContext context,String capability,String resourceType){
        CatalogManifest.code(capability);CatalogManifest.code(resourceType);
        Partition p=new Partition(context.tenantId(),context.applicationId(),context.environment());AccessValues.partition(p);
        if(access.strict(p))throw new GovernanceException(AUTHZ_STATE_NOT_READY);
        if(!Boolean.TRUE.equals(access.enabled(p)))return false;
        if(projection.pending(p)>0)throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
        var app=catalog.application(p.applicationId());if(app==null||app.manifestVersion()==0)return false;
        var caps=CatalogManifest.read(catalog.snapshot(p.applicationId(),app.manifestVersion()).manifestJson()).capabilities();
        if(caps.stream().noneMatch(c->c.code().equals(capability)&&c.resourceType().equals(resourceType)))return false;
        var grants=projection.eligible(p,context.membershipId(),context.membershipGeneration());
        if(grants.size()>100)throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
        long deadline=System.nanoTime()+6_000_000_000L;
        for(var grant:grants){
            if(System.nanoTime()>=deadline)throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
            var role=access.role(p,grant.roleId());if(role==null||!AccessValues.read(role.capabilitiesJson()).contains(capability))continue;
            boolean eligible=graph.check(SubjectRef.of("gov_membership",grant.membershipId()+"_g"+grant.generation()),"eligible",ResourceRef.of("gov_grant",grant.id()),Consistency.atLeastAsFresh(grant.zedToken()));
            if(System.nanoTime()>=deadline)throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
            if(!eligible)continue;
            // 新SQL语句读取当前状态，图的旧允许不能覆盖完成撤销、到期和成员停用。
            if(projection.pending(p)>0)throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
            if(projection.eligible(p,context.membershipId(),context.membershipGeneration()).stream().anyMatch(now->now.id().equals(grant.id())&&now.version()==grant.version()))return true;
        }
        return false;
    }
}
