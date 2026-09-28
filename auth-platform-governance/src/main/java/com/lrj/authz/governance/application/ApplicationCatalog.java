package com.lrj.authz.governance.application;

import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.CatalogModels.*;
import com.lrj.authz.governance.domain.IdentityModels.GlobalStatus;
import com.lrj.authz.governance.domain.IdentityModels.PrincipalKind;
import com.lrj.authz.governance.persistence.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import static com.lrj.authz.governance.application.GovernanceException.Code.*;

/** 应用目录唯一写用例；清单发布不产生或修改业务授权。 */
public final class ApplicationCatalog {
    private final CatalogMapper mapper;
    private final SafetyMapper safety;
    private final IdentityMapper identities;
    private final IdentityGovernance identity;
    private final TransactionTemplate transaction;
    /** 事务与Mapper由既有治理Runtime提供。 */
    public ApplicationCatalog(CatalogMapper mapper, IdentityMapper identities, IdentityGovernance identity, TransactionTemplate transaction, SafetyMapper safety) {
        this.safety=safety;
        this.mapper=mapper; this.identities=identities; this.identity=identity; this.transaction=transaction;
    }
    /** 仅受控引导调用；应用拥有者必须已存在且有效，重复登记不能夺取所有权。 */
    public Application register(String application, String owner, String entryOrigin, String operator, String command) {
        CatalogManifest.code(application); CatalogManifest.origin(entryOrigin); BootstrapCommand.uuid(owner);
        BootstrapCommand.bounded(operator,100); BootstrapCommand.bounded(command,100);
        return transaction.execute(status -> {
            var principal=identities.principal(owner);
            if (principal==null || principal.kind()!=PrincipalKind.HUMAN || principal.status()!=GlobalStatus.ACTIVE) { throw new GovernanceException(MEMBERSHIP_UNAVAILABLE); }
            int inserted=mapper.register(new Application(application,owner,entryOrigin,0),operator);
            Application current=mapper.lockApplication(application);
            if (current==null || !owner.equals(current.ownerPrincipalId()) || !entryOrigin.equals(current.entryOrigin())) { throw new GovernanceException(BINDING_CONFLICT); }
            if (inserted==1) { requireOne(mapper.audit(UUID.randomUUID().toString(),application,operator,"REGISTER",0,command)); }
            return current;
        });
    }
    /** 预览验证拥有者和增量语义，没有写副作用。 */
    public Preview preview(VerifiedLogin login, Manifest input) {
        Manifest manifest=CatalogManifest.normalize(input);
        Application app=requireOwner(login,manifest.application(),false);
        return preview(app,manifest);
    }
    /** 锁定当前版本并事务发布；同版重试返回同一快照，不重复审计。 */
    public Preview publish(VerifiedLogin login, Manifest input, String command) {
        Manifest manifest=CatalogManifest.normalize(input); BootstrapCommand.bounded(command,100);
        return transaction.execute(status -> {
            Application app=requireOwner(login,manifest.application(),true);
            Preview result=preview(app,manifest);
            Snapshot existing=mapper.snapshot(app.applicationId(),manifest.manifestVersion());
            if (existing!=null) {
                if (!existing.contentHash().equals(result.contentHash()) || app.manifestVersion()!=existing.version()) { throw new GovernanceException(VERSION_CONFLICT); }
                return result;
            }
            requireOne(mapper.insertSnapshot(new Snapshot(app.applicationId(),manifest.manifestVersion(),result.contentHash(),CatalogManifest.json(manifest)),app.ownerPrincipalId()));
            requireOne(mapper.advance(app.applicationId(),app.manifestVersion(),manifest.manifestVersion()));
            requireOne(mapper.audit(UUID.randomUUID().toString(),app.applicationId(),app.ownerPrincipalId(),"PUBLISH",manifest.manifestVersion(),command));
            return result;
        });
    }
    /** 当前清单仅供已拥有该应用的管理用户查询。 */
    public Manifest current(VerifiedLogin login, String application) {
        Application app=requireOwner(login,application,false);
        Snapshot row=mapper.snapshot(application,app.manifestVersion());
        return row==null ? null : CatalogManifest.read(row.manifestJson());
    }
    /** 紧急停用不修改已发布RoleVersion；恢复也必须显式拥有者命令和新版本。 */
    public CapabilityState changeCapability(VerifiedLogin login,String application,String capability,boolean disabled,long expectedVersion,String reason,String command) {
        CatalogManifest.code(capability);BootstrapCommand.uuid(command);BootstrapCommand.bounded(reason,500);
        if(expectedVersion<0)throw new GovernanceException(INVALID_ARGUMENT);
        return transaction.execute(status->{
            Application app=requireOwner(login,application,true);
            var snapshot=mapper.snapshot(application,app.manifestVersion());
            if(snapshot==null||CatalogManifest.read(snapshot.manifestJson()).capabilities().stream().noneMatch(c->c.code().equals(capability)))throw new GovernanceException(ACCESS_DENIED);
            String hash=AccessValues.hash(application,capability,disabled,expectedVersion,reason);
            String previous=safety.commandHash(application,command);
            if(previous!=null){if(!previous.equals(hash))throw new GovernanceException(COMMAND_CONFLICT);return safety.commandResult(application,command);}
            var state=safety.capability(application,capability);
            if((state==null?0:state.version())!=expectedVersion)throw new GovernanceException(VERSION_CONFLICT);
            requireOne(safety.changeCapability(application,capability,disabled,expectedVersion+1));
            requireOne(safety.capabilityAudit(application,capability,command,hash,app.ownerPrincipalId(),reason,expectedVersion,disabled));
            return safety.capability(application,capability);
        });
    }
    private Application requireOwner(VerifiedLogin login,String application,boolean lock) {
        CatalogManifest.code(application);
        String principal=identity.principalForLogin(login.issuer(),login.subject()).id();
        Application app=lock?mapper.lockApplication(application):mapper.application(application);
        if (app==null || !principal.equals(app.ownerPrincipalId())) { throw new GovernanceException(ACCESS_DENIED); }
        return app;
    }
    private Preview preview(Application app,Manifest manifest) {
        if (manifest.manifestVersion()<app.manifestVersion()) { throw new GovernanceException(VERSION_CONFLICT); }
        Snapshot previous=mapper.snapshot(app.applicationId(),app.manifestVersion());
        Map<String,Capability> old=previous==null?Map.of():CatalogManifest.read(previous.manifestJson()).capabilities().stream().collect(Collectors.toMap(Capability::code,Function.identity()));
        Map<String,Capability> next=manifest.capabilities().stream().collect(Collectors.toMap(Capability::code,Function.identity()));
        if (!next.keySet().containsAll(old.keySet()) || old.values().stream().anyMatch(c -> !c.equals(next.get(c.code())))) { throw new GovernanceException(VERSION_CONFLICT); }
        String hash=CatalogManifest.hash(manifest);
        if (manifest.manifestVersion()==app.manifestVersion() && (previous==null || !hash.equals(previous.contentHash()))) { throw new GovernanceException(VERSION_CONFLICT); }
        return new Preview(app.applicationId(),app.manifestVersion(),manifest.manifestVersion(),hash,
                next.keySet().stream().filter(k -> !old.containsKey(k)).sorted().toList(),old.keySet().stream().sorted().toList());
    }
    private static void requireOne(int count) { if(count!=1) { throw new GovernanceException(VERSION_CONFLICT); } }
}
