package com.lrj.authz.governance.application;

import com.lrj.authz.governance.domain.AccessModels.*;
import com.lrj.authz.governance.persistence.*;
import com.lrj.authz.protocol.*;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import static com.lrj.authz.governance.application.GovernanceException.Code.*;

/** 单执行者有界推进可靠意图；图成功后SQL回执失败时仍保持未就绪。 */
public final class GrantProjection {
    private final DataSource dataSource;private final AccessMapper access;private final ProjectionMapper mapper;private final TransactionTemplate tx;private final AuthzEngine graph;
    /** 独立图适配由宿主注入，不让旧管理引擎默认接管治理图。 */
    public GrantProjection(DataSource dataSource,AccessMapper access,ProjectionMapper mapper,TransactionTemplate tx,AuthzEngine graph){this.dataSource=dataSource;this.access=access;this.mapper=mapper;this.tx=tx;this.graph=graph;}
    /** 一次最多50条/30秒，失败记录并保留意图；满5次需受控恢复，不能无限循环。 */
    public Result drain(Partition p,int limit){
        AccessValues.partition(p);if(limit<1||limit>50)throw new GovernanceException(INVALID_ARGUMENT);
        int completed=0,failed=0;long deadline=System.nanoTime()+30_000_000_000L;
        try(var lock=ProjectionLock.acquire(dataSource)){
            for(Projection intent:mapper.due(p,limit)){
                if(System.nanoTime()>=deadline)break;
                try{
                    Grant current=access.grant(p,intent.grantId());if(current==null)throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
                    if(current.version()!=intent.grantVersion()){
                        // 未发送的旧意图已被新版本覆盖；当前撤销意图仍必须成功完成才能READY。
                        tx.executeWithoutResult(status->one(mapper.complete(intent.grantId(),intent.grantVersion())));completed++;continue;
                    }
                    var resource=ResourceRef.of("gov_grant",current.id());var subject=SubjectRef.of("gov_membership",current.membershipId()+"_g"+current.generation());
                    var update=current.state()==GrantState.REVOKED?RelationshipUpdate.delete(resource,"assignee",subject):RelationshipUpdate.touch(resource,"assignee",subject);
                    String token=graph.writeRelationships(List.of(update)).token();
                    if(token==null||token.isBlank()||token.length()>2048)throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
                    tx.executeWithoutResult(status->{
                        access.lockPartition(p);Grant latest=access.grant(p,current.id());
                        // 图写期间撤销会推进版本；这里不得把新REVOKED改回ACTIVE。
                        if(latest!=null&&latest.version()==intent.grantVersion()&&latest.state()==GrantState.PENDING)one(mapper.activate(current.id(),current.version(),token));
                        one(mapper.complete(intent.grantId(),intent.grantVersion()));
                    });completed++;
                }catch(RuntimeException failure){tx.executeWithoutResult(status->mapper.failed(intent.grantId(),intent.grantVersion()));failed++;}
            }
        }
        return new Result(completed,failed,mapper.pending(p));
    }
    private static void one(int n){if(n!=1)throw new GovernanceException(VERSION_CONFLICT);}
    /** pending明确包含耗尽重试的意图，不能因本轮无工作而伪称就绪。 */
    public record Result(int completed,int failed,int pending){}
}
