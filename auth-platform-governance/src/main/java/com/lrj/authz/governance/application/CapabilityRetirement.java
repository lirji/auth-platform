package com.lrj.authz.governance.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.AccessModels.Partition;
import com.lrj.authz.governance.domain.CatalogModels.*;
import com.lrj.authz.governance.persistence.*;
import com.lrj.authz.protocol.CapabilityLifecycleDtos;
import com.lrj.authz.protocol.CapabilityRetirementDtos.*;
import java.time.Instant;
import java.util.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static com.lrj.authz.governance.application.GovernanceException.Code.*;

/** 最终退役用例：应用锁保护全部新增引用，证明缺失时不会把零菜单当退出完成。 */
public final class CapabilityRetirement {
    private static final long MAX_BASIS=10000;
    private final IdentityGovernance identity;
    private final CatalogMapper catalogs;
    private final CapabilityLifecycleMapper lifecycles;
    private final CapabilityRetirementMapper mapper;
    private final CatalogRetirementProof proofs;
    private final TransactionTemplate tx;
    private record Analysis(Report report,CatalogRetirementProof.Result proof) {}
    /** 同一专用治理库／事务，不在事务内调用部署端网络。 */
    public CapabilityRetirement(IdentityGovernance identity,CatalogMapper catalogs,CapabilityLifecycleMapper lifecycles,
            CapabilityRetirementMapper mapper,CatalogRetirementProof proofs,TransactionTemplate transaction){
        this.identity=identity;this.catalogs=catalogs;this.lifecycles=lifecycles;this.mapper=mapper;this.proofs=proofs;
        tx=new TransactionTemplate(transaction.getTransactionManager());tx.setTimeout(5);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    /** 最后管理委派已退出时Owner仍可治理目录，不能顺带取得人员／角色明细。 */
    public OwnerCatalog ownerCatalog(VerifiedLogin login,String application){
        CatalogManifest.code(application);return tx.execute(status->{var a=owner(login,application);var rows=mapper.ownerCapabilities(application).stream().map(c->new com.lrj.authz.protocol.PortalDtos.PublishedCapability(c.code(),c.resourceType(),c.riskLevel(),c.disabled(),false,CapabilityLifecycleDtos.State.valueOf(c.lifecycleState()),c.lifecycleVersion(),c.lifecycleReason())).toList();owner(login,application);return new OwnerCatalog(application,a.manifestVersion(),rows);});
    }
    /** Owner仅获得全应用不含人员的汇总；身份行和目录锁防止读取中失权。 */
    public Report report(VerifiedLogin login,String app,String cap){
        validate(app,cap);return tx.execute(status->{var a=owner(login,app);var result=analyze(a,cap).report();owner(login,app);return result;});
    }
    /** 外层PortalPermissions负责两次独立诊断授权和审计；不开放Owner越权入口。 */
    References references(Partition p,String cap,String cursor){
        AccessValues.partition(p);validate(p.applicationId(),cap);
        return tx.execute(status->{
            var app=catalogs.application(p.applicationId());if(app==null)throw error(ACCESS_DENIED);
            String basis=referenceBasis(app,cap);String kind="",after="";
            if(cursor!=null){
                try{var values=new String(Base64.getUrlDecoder().decode(cursor),java.nio.charset.StandardCharsets.UTF_8).split("\\|",-1);
                    if(values.length!=3||!values[0].equals(basis)||!values[1].matches("[A-Z_]{1,32}")||values[2].length()>100)throw error(VERSION_CONFLICT);
                    kind=values[1];after=values[2];
                }catch(IllegalArgumentException invalid){throw error(INVALID_ARGUMENT);}
            }
            var rows=mapper.references(p,cap,kind,after);if(!basis.equals(referenceBasis(catalogs.application(p.applicationId()),cap)))throw error(VERSION_CONFLICT);
            String next=rows.size()>100?Base64.getUrlEncoder().withoutPadding().encodeToString((basis+"|"+rows.get(99).kind()+"|"+rows.get(99).id()).getBytes(java.nio.charset.StandardCharsets.UTF_8)):null;
            return new References(rows.stream().limit(100).toList(),next,basis);
        });
    }
    /** 固定原命令先重放，再重验当前全部引用及独立证明；失败回滚证据和状态。 */
    public CapabilityLifecycleDtos.Mutation retire(VerifiedLogin login,Retire input){
        if(input==null||input.expectedVersion()==null||input.expectedVersion()<1||input.expectedVersion()==Long.MAX_VALUE||input.basisHash()==null||!input.basisHash().matches("[a-f0-9]{64}"))throw error(INVALID_ARGUMENT);
        validate(input.applicationId(),input.capability());BootstrapCommand.uuid(input.commandId());BootstrapCommand.bounded(input.reason(),500);
        return tx.execute(status->{
            var app=owner(login,input.applicationId());String hash=AccessValues.hash("capability-retire/v1",input.applicationId(),input.capability(),input.expectedVersion(),input.basisHash(),input.reason());
            var prior=lifecycles.receipt(app.applicationId(),input.commandId());
            if(prior!=null){if(!prior.payloadHash().equals(hash))throw error(COMMAND_CONFLICT);return mutation(app.applicationId(),input.capability(),prior);}
            var current=value(app.applicationId(),input.capability());if(current.version()!=input.expectedVersion()||current.state()!=CapabilityLifecycleDtos.State.DEPRECATED)throw error(VERSION_CONFLICT);
            var analysis=analyze(app,input.capability());if(!analysis.report().basisHash().equals(input.basisHash()))throw error(VERSION_CONFLICT);
            if(!analysis.report().eligible())throw error(RETIREMENT_BLOCKED);
            var proof=analysis.proof();var repeated=proofs.verify(app.applicationId(),input.capability(),app.manifestVersion(),analysis.report().contentHash(),analysis.report().presentationHash(),current.version());
            if(!repeated.proven()||!Objects.equals(proof.hash(),repeated.hash()))throw error(VERSION_CONFLICT);
            one(mapper.evidence(app.applicationId(),input.commandId(),input.capability(),current.version(),app.manifestVersion(),analysis.report().contentHash(),analysis.report().presentationHash(),input.basisHash(),proof.hash(),proof.envelope(),proof.validUntil()));
            var now=Instant.now();one(lifecycles.change(new com.lrj.authz.governance.domain.CapabilityLifecycleModels.Value(app.applicationId(),input.capability(),"RETIRED",current.version()+1,input.reason(),app.ownerPrincipalId(),now),current.version()));
            var receipt=new com.lrj.authz.governance.domain.CapabilityLifecycleModels.Receipt(app.applicationId(),input.commandId(),hash,input.capability(),current.state().code(),"RETIRED",current.version(),current.version()+1,input.reason(),app.ownerPrincipalId(),now);
            one(lifecycles.audit(receipt));owner(login,app.applicationId());return mutation(app.applicationId(),input.capability(),lifecycles.receipt(app.applicationId(),input.commandId()));
        });
    }
    private Analysis analyze(Application app,String cap){
        var snapshot=catalogs.snapshot(app.applicationId(),app.manifestVersion());
        if(snapshot==null||CatalogManifest.read(snapshot.manifestJson()).capabilities().stream().noneMatch(c->c.code().equals(cap)))throw error(ACCESS_DENIED);
        var display=catalogs.presentation(app.applicationId(),app.manifestVersion());String presentation=display==null?CatalogManifest.presentationHash(CatalogManifest.read(snapshot.manifestJson())):display.presentationHash();
        var life=value(app.applicationId(),cap);var row=mapper.basis(app.applicationId(),cap);var counts=counts(row.countsJson());
        var proof=proofs.verify(app.applicationId(),cap,app.manifestVersion(),snapshot.contentHash(),presentation,life.version());
        boolean complete=row.total()<=MAX_BASIS,eligible=complete&&life.state()==CapabilityLifecycleDtos.State.DEPRECATED&&counts.stream().noneMatch(c->c.blocking()>0)&&proof.proven();
        String basis=AccessValues.hash(app.applicationId(),cap,life.state().code(),life.version(),app.manifestVersion(),snapshot.contentHash(),presentation,row.total(),row.referenceHash(),proof.state(),proof.reason(),proof.hash());
        return new Analysis(new Report(app.applicationId(),cap,life.state(),life.version(),app.manifestVersion(),snapshot.contentHash(),presentation,complete,counts,proof.state(),proof.reason(),proof.hash(),proof.validUntil(),eligible,basis,row.checkedAt()),proof);
    }
    private String referenceBasis(Application app,String cap){if(app==null)throw error(VERSION_CONFLICT);var row=mapper.basis(app.applicationId(),cap);return AccessValues.hash(app.applicationId(),cap,app.manifestVersion(),row.total(),row.referenceHash());}
    private Application owner(VerifiedLogin login,String app){
        var principal=identity.principalForLogin(login.issuer(),login.subject());var a=catalogs.lockApplication(app);
        if(a==null||!a.ownerPrincipalId().equals(principal.id())||!principal.id().equals(catalogs.lockOwner(login.issuer(),login.subject(),principal.id())))throw error(ACCESS_DENIED);return a;
    }
    private CapabilityLifecycleDtos.Value value(String app,String cap){var r=lifecycles.value(app,cap);return r==null?new CapabilityLifecycleDtos.Value(app,cap,CapabilityLifecycleDtos.State.ACTIVE,0,null,null,null):new CapabilityLifecycleDtos.Value(app,cap,CapabilityLifecycleDtos.State.valueOf(r.state()),r.version(),r.reason(),r.changedBy(),r.updatedAt().toString());}
    private CapabilityLifecycleDtos.Mutation mutation(String app,String cap,com.lrj.authz.governance.domain.CapabilityLifecycleModels.Receipt r){return new CapabilityLifecycleDtos.Mutation(value(app,cap),new CapabilityLifecycleDtos.Receipt(r.commandId(),app,cap,CapabilityLifecycleDtos.State.valueOf(r.beforeState()),CapabilityLifecycleDtos.State.valueOf(r.afterState()),r.beforeVersion(),r.afterVersion(),r.reason(),r.actor(),r.createdAt().toString()));}
    private static List<Count> counts(String json){try{return List.of(new ObjectMapper().readValue(json,Count[].class));}catch(Exception corrupt){throw error(DEPENDENCY_UNAVAILABLE);}}
    private static void validate(String app,String cap){CatalogManifest.code(app);CatalogManifest.code(cap);if(!cap.startsWith(app+"."))throw error(INVALID_ARGUMENT);}
    private static void one(int n){if(n!=1)throw error(VERSION_CONFLICT);}
    private static GovernanceException error(GovernanceException.Code code){return new GovernanceException(code);}
}
