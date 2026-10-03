package com.lrj.authz.governance.application;

import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.CatalogModels.*;
import com.lrj.authz.governance.domain.CatalogReleaseModels.*;
import com.lrj.authz.governance.domain.CatalogGuardModels.*;
import com.lrj.authz.governance.domain.IdentityModels.GlobalStatus;
import com.lrj.authz.governance.domain.IdentityModels.PrincipalKind;
import com.lrj.authz.governance.persistence.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static com.lrj.authz.governance.application.GovernanceException.Code.*;

/** 应用目录唯一写用例；清单发布不产生或修改业务授权。 */
public final class ApplicationCatalog {
    private final CatalogMapper mapper;
    private final SafetyMapper safety;
    private final CatalogReleaseMapper releases;
    private final CatalogGuardMapper guards;
    private final IdentityMapper identities;
    private final IdentityGovernance identity;
    private final TransactionTemplate transaction;
    /** 事务与Mapper由既有治理Runtime提供。 */
    public ApplicationCatalog(CatalogMapper mapper, IdentityMapper identities, IdentityGovernance identity, TransactionTemplate transaction, SafetyMapper safety, CatalogReleaseMapper releases, CatalogGuardMapper guards) {
        this.guards=guards;
        this.releases=releases;
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
        return publishCandidate(login,new Candidate(input,null,null,null),command,true,null,null);
    }
    /** 来源发布仍使用同一目录事务；不会向旧快照补造来源或自动授予。 */
    public Release publishSource(VerifiedLogin login,Candidate input,String command) {
        var candidate=CatalogPublication.normalize(input);
        return transaction.execute(status -> {
            publishCandidate(login,candidate,command,false,null,null);
            return release(releases.command(candidate.manifest().application(),command));
        });
    }
    private Preview publishCandidate(VerifiedLogin login,Candidate input,String command,boolean legacy,CatalogGuardMapper.PreviewRow ticket,PortalPermissions diagnostics) {
        var candidate=CatalogPublication.normalize(input); var manifest=candidate.manifest(); BootstrapCommand.bounded(command,100);
        return transaction.execute(status -> {
            Application app=requireOwner(login,manifest.application(),true);
            if(!app.ownerPrincipalId().equals(mapper.lockOwner(login.issuer(),login.subject(),app.ownerPrincipalId())))throw new GovernanceException(ACCESS_DENIED);
            if(ticket!=null && (!app.applicationId().equals(ticket.applicationId())||!app.ownerPrincipalId().equals(ticket.ownerPrincipal())
                    ||!CatalogPublication.hash(candidate).equals(ticket.candidateHash())))throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
            String bodyHash=ticket==null?CatalogPublication.hash(candidate):AccessValues.hash("catalog-release-publish/v1",ticket.id()); var prior=releases.command(app.applicationId(),command);
            // 同键成功重试先读取原回执，不用当前更高版本重算差异或倒退指针。
            if(prior!=null) {
                // 旧摘要使用String.valueOf(null)；元数据逐字段比较避免原因文本"null"与缺省原因碰撞，兼容已保存摘要。
                var source=candidate.source();
                if(!bodyHash.equals(prior.commandHash())||!Objects.equals(candidate.reason(),prior.reason())
                        ||!Objects.equals(source==null?null:source.commit(),prior.sourceCommit())||!Objects.equals(source==null?null:source.artifactHash(),prior.artifactHash())
                        ||!Objects.equals(candidate.decision()==null?null:candidate.decision().code(),prior.decision()))throw new GovernanceException(COMMAND_CONFLICT);
                return CatalogPublication.preview(prior.previewJson());
            }
            if(ticket==null && guards.policy(app.applicationId())!=null)throw new GovernanceException(VERSION_CONFLICT);
            if(ticket!=null) {
                var current=basis(app);
                var fresh=guards.ticket(ticket.id());
                if(fresh==null || !fresh.unexpired() || ticket.baseVersion()!=app.manifestVersion() || !Objects.equals(ticket.baseContentHash(),current.contentHash())
                        ||!Objects.equals(ticket.basePresentationHash(),current.presentationHash()))throw new GovernanceException(VERSION_CONFLICT);
                confirmImpact(login,candidate,CatalogPublication.impact(ticket.impactJson()),diagnostics);
                // 排队与影响SQL会消耗有效期，不能仅用获取票据时的旧unexpired标志。
                if(!guards.ticket(ticket.id()).unexpired())throw new GovernanceException(VERSION_CONFLICT);
            }
            Preview result=preview(app,manifest);
            if (!result.publishable()) { throw new GovernanceException(VERSION_CONFLICT); }
            Snapshot existing=mapper.snapshot(app.applicationId(),manifest.manifestVersion());
            if (existing!=null) {
                if(!legacy)throw new GovernanceException(VERSION_CONFLICT);
                if (!existing.contentHash().equals(result.contentHash()) || app.manifestVersion()!=existing.version()) { throw new GovernanceException(VERSION_CONFLICT); }
                return result;
            }
            var base=mapper.snapshot(app.applicationId(),app.manifestVersion());
            var baseShown=mapper.presentation(app.applicationId(),app.manifestVersion());
            requireOne(mapper.insertSnapshot(new Snapshot(app.applicationId(),manifest.manifestVersion(),result.contentHash(),CatalogManifest.json(manifest)),app.ownerPrincipalId()));
            // 名称与权限同事务提交，审计失败不能留下半个可见目录。
            String presentation = CatalogManifest.presentationJson(manifest);
            if (!"[]".equals(presentation)) { requireOne(mapper.insertPresentation(new PresentationSnapshot(app.applicationId(),manifest.manifestVersion(),CatalogManifest.presentationHash(manifest),presentation),app.ownerPrincipalId())); }
            requireOne(mapper.advance(app.applicationId(),app.manifestVersion(),manifest.manifestVersion()));
            requireOne(mapper.audit(UUID.randomUUID().toString(),app.applicationId(),app.ownerPrincipalId(),"PUBLISH",manifest.manifestVersion(),command));
            var source=candidate.source();
            requireOne(releases.insert(new CatalogReleaseMapper.Row(app.applicationId(),manifest.manifestVersion(),result.contentHash(),result.presentationHash(),
                    app.ownerPrincipalId(),null,command,bodyHash,source==null?null:source.commit(),source==null?null:source.artifactHash(),candidate.reason(),
                    candidate.decision()==null?null:candidate.decision().code(),app.manifestVersion(),base==null?null:base.contentHash(),
                    base==null?null:baseShown==null?CatalogManifest.presentationHash(CatalogManifest.read(base.manifestJson())):baseShown.presentationHash(),CatalogPublication.previewJson(result))));
            return result;
        });
    }
    /** 新预览保存固定候选及依据，不改变目录、角色或Grant。 */
    public Ticket releasePreview(VerifiedLogin login,PreviewInput input,PortalPermissions diagnostics) {
        if(input==null)throw new GovernanceException(INVALID_ARGUMENT); var candidate=CatalogPublication.normalize(input.candidate());
        return transaction.execute(status -> {
            var app=requireOwner(login,candidate.manifest().application(),true); var current=basis(app); var diff=preview(app,candidate.manifest());
            if(!diff.publishable()||diff.proposedVersion()<=diff.currentVersion())throw new GovernanceException(VERSION_CONFLICT);
            if(CatalogPublication.needsDecision(diff,current.capabilities())&&(candidate.reason()==null||candidate.decision()==null))throw new GovernanceException(INVALID_ARGUMENT);
            confirmImpact(login,candidate,input.impact(),diagnostics);
            String id=UUID.randomUUID().toString();
            requireOne(guards.preview(new CatalogGuardMapper.PreviewRow(id,app.applicationId(),app.ownerPrincipalId(),CatalogPublication.candidateJson(candidate),
                    CatalogPublication.hash(candidate),CatalogPublication.previewJson(diff),app.manifestVersion(),current.contentHash(),current.presentationHash(),
                    CatalogPublication.impactJson(input.impact()),null,false)));
            var row=guards.ticket(id);
            return new Ticket(id,row.expiresAt(),row.baseVersion(),row.baseContentHash(),row.basePresentationHash(),candidate,diff,input.impact());
        });
    }
    /** 发布内容只能来自不可变票据；已成功原命令先于期限／当前基础检查返回。 */
    public Release releasePublish(VerifiedLogin login,PublishCommand command,PortalPermissions diagnostics) {
        if(command==null)throw new GovernanceException(INVALID_ARGUMENT); BootstrapCommand.uuid(command.commandId()); BootstrapCommand.uuid(command.previewId());
        return transaction.execute(status -> {
            var row=guards.ticket(command.previewId()); if(row==null)throw new GovernanceException(ACCESS_DENIED);
            var candidate=CatalogPublication.candidate(row.candidateJson());
            publishCandidate(login,candidate,command.commandId(),false,row,diagnostics);
            return release(releases.command(row.applicationId(),command.commandId()));
        });
    }
    /** 单向启用与审计同一行提交；Owner显式确认旧节点退出，不自动生产启用。 */
    public Policy enableGuard(VerifiedLogin login,Enable command) {
        if(command==null)throw new GovernanceException(INVALID_ARGUMENT); BootstrapCommand.uuid(command.commandId()); BootstrapCommand.bounded(command.reason(),500);
        if(!command.legacyWritersExited()||command.expectedVersion()!=0)throw new GovernanceException(INVALID_ARGUMENT);
        return transaction.execute(status -> {
            var app=requireOwner(login,command.applicationId(),true); String hash=AccessValues.hash(command.applicationId(),command.expectedVersion(),command.legacyWritersExited(),command.reason());
            var prior=guards.policy(app.applicationId());
            if(prior!=null) {
                if(!prior.commandId().equals(command.commandId()))throw new GovernanceException(VERSION_CONFLICT);
                if(!prior.commandHash().equals(hash))throw new GovernanceException(COMMAND_CONFLICT);
                return policy(prior);
            }
            if(!app.ownerPrincipalId().equals(mapper.lockOwner(login.issuer(),login.subject(),app.ownerPrincipalId())))throw new GovernanceException(ACCESS_DENIED);
            requireOne(guards.enable(app.applicationId(),app.ownerPrincipalId(),command.reason(),command.commandId(),hash)); return policy(guards.policy(app.applicationId()));
        });
    }
    /** 当前Owner查询写模式，没有记录的原应用继续LEGACY。 */
    public Policy guardPolicy(VerifiedLogin login,String application) {
        requireOwner(login,application,false); var row=guards.policy(application);
        return row==null?new Policy(application,Mode.LEGACY,0,null,null,null,null):policy(row);
    }
    private static Policy policy(CatalogGuardMapper.PolicyRow row) { return new Policy(row.applicationId(),Mode.GUARDED,1,row.enabledBy(),row.enabledAt(),row.reason(),row.commandId()); }
    private void confirmImpact(VerifiedLogin login,Candidate candidate,Impact impact,PortalPermissions diagnostics) {
        if(impact==null)return; CatalogPublication.validateImpact(impact);
        if(!candidate.manifest().application().equals(impact.applicationId()))throw new GovernanceException(INVALID_ARGUMENT);
        if(diagnostics==null)throw new GovernanceException(ACCESS_DENIED);
        var report=diagnostics.catalogImpact(login,new com.lrj.authz.governance.domain.AccessModels.Partition(impact.tenantId(),impact.applicationId(),impact.environment()),candidate.manifest(),null);
        if(report.completeness()!=com.lrj.authz.governance.domain.CatalogImpactModels.Completeness.COMPLETE||!impact.basisHash().equals(report.basisHash()))throw new GovernanceException(VERSION_CONFLICT);
    }
    private record Basis(String contentHash,String presentationHash,Set<String> capabilities) {}
    private Basis basis(Application app) {
        if(app.manifestVersion()==0)return new Basis(null,null,Set.of());
        var row=mapper.snapshot(app.applicationId(),app.manifestVersion()); if(row==null)throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
        var shown=mapper.presentation(app.applicationId(),app.manifestVersion());
        try {
            var manifest=CatalogManifest.withPresentation(CatalogManifest.read(row.manifestJson()),shown==null?"[]":shown.presentationJson(),shown==null?null:shown.presentationHash());
            if(!app.applicationId().equals(manifest.application())||app.manifestVersion()!=manifest.manifestVersion()||!row.contentHash().equals(CatalogManifest.hash(manifest)))throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
            return new Basis(row.contentHash(),CatalogManifest.presentationHash(manifest),Set.copyOf(manifest.capabilities().stream().map(Capability::code).toList()));
        } catch(GovernanceException corrupt) { throw new GovernanceException(DEPENDENCY_UNAVAILABLE); }
    }
    /** 历史分页受当前Owner保护，旧发布无来源记录仍保留在版本列表。 */
    public History history(VerifiedLogin login,String application,Long before) {
        if(before!=null&&before<1)throw new GovernanceException(INVALID_ARGUMENT);
        requireOwner(login,application,false); var rows=releases.history(application,before,101);
        var page=rows.subList(0,Math.min(rows.size(),100));
        return new History(page.stream().map(ApplicationCatalog::release).toList(),rows.size()>100?page.getLast().version():null);
    }
    /** 固定版本重验双摘要；不读取当前版本假装历史详情。 */
    public Detail releaseDetail(VerifiedLogin login,String application,long version) {
        if(version<1)throw new GovernanceException(INVALID_ARGUMENT); requireOwner(login,application,false);
        var row=releases.version(application,version); if(row==null)throw new GovernanceException(NOT_FOUND);
        var snapshot=mapper.snapshot(application,version); var shown=mapper.presentation(application,version);
        try {
            var manifest=CatalogManifest.withPresentation(CatalogManifest.read(snapshot.manifestJson()),shown==null?"[]":shown.presentationJson(),shown==null?null:shown.presentationHash());
            if(!application.equals(manifest.application())||version!=manifest.manifestVersion()||!CatalogManifest.hash(manifest).equals(row.contentHash())
                    ||row.presentationHash()!=null&&!CatalogManifest.presentationHash(manifest).equals(row.presentationHash()))throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
            return new Detail(release(row),manifest);
        } catch(GovernanceException failure) { throw new GovernanceException(DEPENDENCY_UNAVAILABLE); }
    }
    private static Release release(CatalogReleaseMapper.Row row) {
        if(row==null)throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
        try {
            return new Release(row.application(),row.version(),row.contentHash(),row.presentationHash(),row.publishedBy(),row.publishedAt(),
                    row.sourceCommit()==null?null:new Source(row.sourceCommit(),row.artifactHash()),row.reason(),row.decision()==null?null:Decision.from(row.decision()),
                    row.commandId(),row.baseVersion(),row.baseContentHash(),row.basePresentationHash(),row.previewJson()==null?null:CatalogPublication.preview(row.previewJson()));
        } catch(IllegalArgumentException corrupt) { throw new GovernanceException(DEPENDENCY_UNAVAILABLE); }
    }
    /** 当前清单仅供已拥有该应用的管理用户查询。 */
    public Manifest current(VerifiedLogin login, String application) {
        Application app=requireOwner(login,application,false);
        Snapshot row=mapper.snapshot(application,app.manifestVersion());
        if (row==null) { return null; }
        var presentation=mapper.presentation(application,app.manifestVersion());
        return CatalogManifest.withPresentation(CatalogManifest.read(row.manifestJson()),presentation==null ? "[]" : presentation.presentationJson(),presentation==null ? null : presentation.presentationHash());
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
        Snapshot previous=mapper.snapshot(app.applicationId(),app.manifestVersion());
        if (app.manifestVersion()>0 && previous==null) { throw new GovernanceException(DEPENDENCY_UNAVAILABLE); }
        var shown=previous==null ? null : mapper.presentation(app.applicationId(),app.manifestVersion());
        Manifest old=previous==null ? null : CatalogManifest.withPresentation(CatalogManifest.read(previous.manifestJson()),
                shown==null ? "[]" : shown.presentationJson(),shown==null ? null : shown.presentationHash());
        return CatalogDiff.compare(app.applicationId(),app.manifestVersion(),old,manifest);
    }
    private static void requireOne(int count) { if(count!=1) { throw new GovernanceException(VERSION_CONFLICT); } }
}
