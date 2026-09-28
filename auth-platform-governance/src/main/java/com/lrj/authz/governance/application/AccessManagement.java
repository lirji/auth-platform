package com.lrj.authz.governance.application;

import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.AccessModels.*;
import com.lrj.authz.governance.domain.IdentityModels.CurrentContext;
import com.lrj.authz.governance.persistence.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.lrj.authz.governance.application.GovernanceException.Code.*;

/** 角色与直接授权用例；任何HTTP/CLI写入均复用管理上限和原子审计。 */
public final class AccessManagement {
    private final AccessMapper mapper;private final CatalogMapper catalog;private final IdentityMapper commands;
    private final IdentityGovernance identity;private final TransactionTemplate tx;
    /** 仅所属治理Runtime装配专用数据库事务。 */
    public AccessManagement(AccessMapper mapper,CatalogMapper catalog,IdentityMapper commands,IdentityGovernance identity,TransactionTemplate tx){this.mapper=mapper;this.catalog=catalog;this.commands=commands;this.identity=identity;this.tx=tx;}
    /** 受控初始化只增加显式准入与管理上限，不恢复停用委派。 */
    public void bootstrap(Partition p,Delegation d,String operator,String command){
        AccessValues.partition(p);BootstrapCommand.uuid(d.membershipId());BootstrapCommand.uuid(command);BootstrapCommand.bounded(operator,100);
        if(d.generation()<1||d.maxDurationSeconds()<1||d.maxDurationSeconds()>31536000)throw new GovernanceException(INVALID_ARGUMENT);
        String caps=AccessValues.json(AccessValues.read(d.capabilitiesJson()));
        tx.executeWithoutResult(status->{
            if(!mapper.memberActive(p,d.membershipId(),d.generation()))throw new GovernanceException(MEMBERSHIP_UNAVAILABLE);
            requireCapabilities(p,AccessValues.read(caps));
            int created=mapper.registerPartition(p,operator);
            if(!Boolean.TRUE.equals(mapper.lockPartition(p)))throw new GovernanceException(ACCESS_DENIED);
            Delegation normalized=new Delegation(d.membershipId(),d.generation(),caps,d.maxDurationSeconds());
            int inserted=mapper.registerDelegation(p,normalized,operator);
            if(!normalized.equals(mapper.delegation(p,d.membershipId(),d.generation())))throw new GovernanceException(BINDING_CONFLICT);
            if(created+inserted>0)one(commands.appendAudit(id(),operator,p.tenantId(),"BOOTSTRAP_ACCESS",d.membershipId(),d.generation(),command));
        });
    }
    /** 不可变版本创建和重放都重新验证操作者当前管理上限。 */
    public RoleVersion createRole(VerifiedLogin login,Partition p,String command,String code,long version,List<String> capabilities){
        CatalogManifest.code(code);if(version<1)throw new GovernanceException(INVALID_ARGUMENT);
        String caps=AccessValues.json(capabilities);
        return tx.execute(status->{
            Manager manager=manager(login,p,true);requireCeiling(manager.delegation(),AccessValues.read(caps));requireCapabilities(p,AccessValues.read(caps));
            String hash=AccessValues.hash(p,code,version,caps);
            String result=command(manager.context(),p,"CREATE_ROLE",command,hash,()->{
                RoleVersion role=new RoleVersion(id(),p.tenantId(),p.applicationId(),p.environment(),code,version,caps,hash);
                int inserted=mapper.insertRole(role,manager.context().membershipId());
                RoleVersion actual=mapper.roleByCode(p,code,version);
                if(actual==null||!hash.equals(actual.contentHash()))throw new GovernanceException(VERSION_CONFLICT);
                if(inserted==1)audit(manager,p,"CREATE_ROLE",actual.id(),version,command);
                return actual.id();
            });
            return mapper.role(p,result);
        });
    }
    /** 完整授权路径与待投影意图同事务；不可用成员/自授予/超范围全部拒绝。 */
    public Grant grant(VerifiedLogin login,Partition p,String command,String member,long generation,String roleId,String scope,String source,Instant from,Instant to){
        BootstrapCommand.uuid(member);BootstrapCommand.uuid(roleId);BootstrapCommand.bounded(source,100);
        if(generation<1||!"TENANT_ALL".equals(scope)||from==null||to==null||!to.isAfter(from))throw new GovernanceException(INVALID_ARGUMENT);
        return tx.execute(status->{
            Manager manager=manager(login,p,true);
            if(manager.context().membershipId().equals(member))throw new GovernanceException(ACCESS_DENIED);
            RoleVersion role=mapper.role(p,roleId);if(role==null)throw new GovernanceException(ACCESS_DENIED);
            requireCeiling(manager.delegation(),AccessValues.read(role.capabilitiesJson()));
            String hash=AccessValues.hash(p,member,generation,roleId,scope,source,from,to);
            String result=command(manager.context(),p,"CREATE_GRANT",command,hash,()->{
                if(!mapper.memberActive(p,member,generation))throw new GovernanceException(MEMBERSHIP_UNAVAILABLE);
                if(!to.isAfter(mapper.now())||Duration.between(from,to).compareTo(Duration.ofSeconds(manager.delegation().maxDurationSeconds()))>0)throw new GovernanceException(ACCESS_DENIED);
                if(mapper.liveCount(p,member,generation)>=100)throw new GovernanceException(INVALID_ARGUMENT);
                Grant g=new Grant(id(),p.tenantId(),p.applicationId(),p.environment(),member,generation,roleId,scope,"DIRECT",source,from,to,GrantState.PENDING,1,null);
                if(mapper.insertGrant(g,manager.context().membershipId())!=1)throw new GovernanceException(BINDING_CONFLICT);
                one(mapper.enqueue(g.id(),1,"UPSERT"));audit(manager,p,"CREATE_GRANT",g.id(),1,command);return g.id();
            });
            return mapper.grant(p,result);
        });
    }
    /** 撤销先去掉SQL资格，再可靠删除图；重复命令返回当前状态，不能恢复授权。 */
    public Grant revoke(VerifiedLogin login,Partition p,String command,String grantId,long version){
        BootstrapCommand.uuid(grantId);if(version<1)throw new GovernanceException(INVALID_ARGUMENT);
        return tx.execute(status->{
            Manager manager=manager(login,p,true);Grant g=mapper.grant(p,grantId);
            if(g==null)throw new GovernanceException(ACCESS_DENIED);
            requireCeiling(manager.delegation(),AccessValues.read(mapper.role(p,g.roleId()).capabilitiesJson()));
            String result=command(manager.context(),p,"REVOKE_GRANT",command,AccessValues.hash(p,grantId,version),()->{
                one(mapper.revoke(p,grantId,version));one(mapper.enqueue(grantId,version+1,"DELETE"));audit(manager,p,"REVOKE_GRANT",grantId,version+1,command);return grantId;
            });return mapper.grant(p,result);
        });
    }
    /** 修复依赖后受控重新领取耗尽的投影；不直接改变Grant或伪造生效回执。 */
    public Grant retryProjection(VerifiedLogin login,Partition p,String command,String grantId,long version){
        BootstrapCommand.uuid(grantId);if(version<1)throw new GovernanceException(INVALID_ARGUMENT);
        return tx.execute(status->{
            Manager manager=manager(login,p,true);Grant g=mapper.grant(p,grantId);
            if(g==null)throw new GovernanceException(ACCESS_DENIED);
            requireCeiling(manager.delegation(),AccessValues.read(mapper.role(p,g.roleId()).capabilitiesJson()));
            String result=command(manager.context(),p,"RETRY_PROJECTION",command,AccessValues.hash(p,grantId,version),()->{
                if(g.version()!=version)throw new GovernanceException(VERSION_CONFLICT);
                one(mapper.retryProjection(p,grantId,version));audit(manager,p,"RETRY_PROJECTION",grantId,version,command);return grantId;
            });return mapper.grant(p,result);
        });
    }
    /** 读取同一管理分区，稳定游标防止无界返回及跨环境泄露。 */
    public State state(VerifiedLogin login,Partition p,String afterRole,String afterGrant){
        manager(login,p,false);String r=cursor(afterRole),g=cursor(afterGrant);
        var roles=mapper.roles(p,r);var grants=mapper.grants(p,g);
        return new State(List.copyOf(roles.subList(0,Math.min(100,roles.size()))),List.copyOf(grants.subList(0,Math.min(100,grants.size()))),
                roles.size()>100?roles.get(99).id():null,grants.size()>100?grants.get(99).id():null);
    }
    private Manager manager(VerifiedLogin login,Partition p,boolean lock){
        AccessValues.partition(p);CurrentContext current=identity.contextForLogin(login.issuer(),login.subject(),p.tenantId(),null);
        if(!Boolean.TRUE.equals(lock?mapper.lockPartition(p):mapper.enabled(p)))throw new GovernanceException(ACCESS_DENIED);
        Delegation d=mapper.delegation(p,current.membershipId(),current.membershipGeneration());
        if(d==null)throw new GovernanceException(ACCESS_DENIED);return new Manager(current,d);
    }
    private void requireCapabilities(Partition p,List<String> caps){
        var app=catalog.application(p.applicationId());if(app==null||app.manifestVersion()==0)throw new GovernanceException(ACCESS_DENIED);
        var snapshot=catalog.snapshot(p.applicationId(),app.manifestVersion());
        var known=CatalogManifest.read(snapshot.manifestJson()).capabilities().stream().map(c->c.code()).toList();
        if(!known.containsAll(caps))throw new GovernanceException(ACCESS_DENIED);
    }
    private void requireCeiling(Delegation d,List<String> caps){if(!AccessValues.read(d.capabilitiesJson()).containsAll(caps))throw new GovernanceException(ACCESS_DENIED);}
    private String command(CurrentContext actor,Partition p,String operation,String command,String hash,Supplier<String> write){
        BootstrapCommand.uuid(command);String operator=actor.membershipId()+":"+actor.membershipGeneration();
        commands.reserveCommand(operator,p.tenantId(),operation,command,hash);
        var receipt=commands.lockCommand(operator,p.tenantId(),operation,command);
        if(receipt==null||!hash.equals(receipt.payloadHash()))throw new GovernanceException(COMMAND_CONFLICT);
        if(receipt.completed())return receipt.resultRef();
        String result=write.get();one(commands.completeCommand(operator,p.tenantId(),operation,command,result));return result;
    }
    private void audit(Manager manager,Partition p,String operation,String target,long version,String command){one(commands.appendAudit(id(),manager.context().membershipId(),p.tenantId(),operation,target,version,command));}
    private String cursor(String cursor){if(cursor==null||cursor.isEmpty())return "";BootstrapCommand.uuid(cursor);return cursor;}
    private static String id(){return UUID.randomUUID().toString();}
    private static void one(int count){if(count!=1)throw new GovernanceException(VERSION_CONFLICT);}
    private record Manager(CurrentContext context,Delegation delegation){}
}
