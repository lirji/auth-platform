package com.lrj.authz.governance.catalog.application;

import static com.lrj.authz.governance.shared.application.GovernanceException.Code.*;

import com.lrj.authz.governance.access.application.PortalPermissions;
import com.lrj.authz.governance.access.persistence.CapabilityLifecycleMapper;
import com.lrj.authz.governance.catalog.domain.CatalogGuardModels.*;
import com.lrj.authz.governance.catalog.domain.CatalogModels.*;
import com.lrj.authz.governance.catalog.domain.CatalogReleaseModels.*;
import com.lrj.authz.governance.catalog.persistence.CatalogGuardMapper;
import com.lrj.authz.governance.catalog.persistence.CatalogMapper;
import com.lrj.authz.governance.catalog.persistence.CatalogReleaseMapper;
import com.lrj.authz.governance.identity.application.BootstrapCommand;
import com.lrj.authz.governance.identity.application.IdentityGovernance;
import com.lrj.authz.governance.identity.authentication.VerifiedLogin;
import com.lrj.authz.governance.identity.domain.IdentityModels.GlobalStatus;
import com.lrj.authz.governance.identity.domain.IdentityModels.PrincipalKind;
import com.lrj.authz.governance.identity.persistence.IdentityMapper;
import com.lrj.authz.governance.shared.application.AccessValues;
import com.lrj.authz.governance.shared.application.GovernanceException;
import com.lrj.authz.governance.shared.persistence.SafetyMapper;

import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** 应用目录唯一写用例；清单发布不产生或修改业务授权。 */
public final class ApplicationCatalog {
    private final CatalogMapper mapper;
    private final CapabilityLifecycleMapper lifecycles;
    private final SafetyMapper safety;
    private final CatalogReleaseMapper releases;
    private final CatalogGuardMapper guards;
    private final IdentityMapper identities;
    private final IdentityGovernance identity;
    private final TransactionTemplate transaction;

    /** 事务与Mapper由既有治理Runtime提供。 */
    public ApplicationCatalog(
            CatalogMapper mapper,
            IdentityMapper identities,
            IdentityGovernance identity,
            TransactionTemplate transaction,
            SafetyMapper safety,
            CatalogReleaseMapper releases,
            CatalogGuardMapper guards,
            CapabilityLifecycleMapper lifecycles) {
        this.lifecycles = lifecycles;
        this.guards = guards;
        this.releases = releases;
        this.safety = safety;
        this.mapper = mapper;
        this.identities = identities;
        this.identity = identity;
        this.transaction = transaction;
    }

    /** 仅受控引导调用；应用拥有者必须已存在且有效，重复登记不能夺取所有权。 */
    public Application register(
            String application, String owner, String entryOrigin, String operator, String command) {
        CatalogManifest.code(application);
        CatalogManifest.origin(entryOrigin);
        BootstrapCommand.uuid(owner);
        BootstrapCommand.bounded(operator, 100);
        BootstrapCommand.bounded(command, 100);
        return transaction.execute(
                status -> {
                    var principal = identities.principal(owner);
                    if (principal == null
                            || principal.kind() != PrincipalKind.HUMAN
                            || principal.status() != GlobalStatus.ACTIVE) {
                        throw new GovernanceException(MEMBERSHIP_UNAVAILABLE);
                    }
                    int inserted =
                            mapper.register(
                                    new Application(application, owner, entryOrigin, 0), operator);
                    Application current = mapper.lockApplication(application);
                    if (current == null
                            || !owner.equals(current.ownerPrincipalId())
                            || !entryOrigin.equals(current.entryOrigin())) {
                        throw new GovernanceException(BINDING_CONFLICT);
                    }
                    if (inserted == 1) {
                        requireOne(
                                mapper.audit(
                                        UUID.randomUUID().toString(),
                                        application,
                                        operator,
                                        "REGISTER",
                                        0,
                                        command));
                    }
                    return current;
                });
    }

    /** 预览验证拥有者和增量语义，没有写副作用。 */
    public Preview preview(VerifiedLogin login, Manifest input) {
        Manifest manifest = CatalogManifest.normalize(input);
        Application app = requireOwner(login, manifest.application(), false);
        return preview(app, manifest);
    }

    /** 锁定当前版本并事务发布；同版重试返回同一快照，不重复审计。 */
    public Preview publish(VerifiedLogin login, Manifest input, String command) {
        return publishCandidate(
                login, new Candidate(input, null, null, null), command, true, null, null);
    }

    /** 来源发布仍使用同一目录事务；不会向旧快照补造来源或自动授予。 */
    public Release publishSource(VerifiedLogin login, Candidate input, String command) {
        var candidate = CatalogPublication.normalize(input);
        return transaction.execute(
                status -> {
                    publishCandidate(login, candidate, command, false, null, null);
                    return release(releases.command(candidate.manifest().application(), command));
                });
    }

    private Preview publishCandidate(
            VerifiedLogin login,
            Candidate input,
            String command,
            boolean legacy,
            CatalogGuardMapper.PreviewRow ticket,
            PortalPermissions diagnostics) {
        var candidate = CatalogPublication.normalize(input);
        BootstrapCommand.bounded(command, 100);
        return transaction.execute(
                status -> {
                    Application app = requireOwner(login, candidate.manifest().application(), true);
                    if (!app.ownerPrincipalId()
                            .equals(
                                    mapper.lockOwner(
                                            login.issuer(),
                                            login.subject(),
                                            app.ownerPrincipalId())))
                        throw new GovernanceException(ACCESS_DENIED);
                    if (ticket != null && guards.machineSource(ticket.id()))
                        throw new GovernanceException(ACCESS_DENIED);
                    return publishLocked(
                            app,
                            app.ownerPrincipalId(),
                            candidate,
                            command,
                            legacy,
                            ticket,
                            () ->
                                    confirmImpact(
                                            login,
                                            candidate,
                                            CatalogPublication.impact(ticket.impactJson()),
                                            diagnostics));
                });
    }

    /** 已授权并持有应用锁的共同内核；只有实际publisher写审计，不把SERVICE映射成HUMAN。 */
    private Preview publishLocked(
            Application app,
            String publisherPrincipal,
            Candidate candidate,
            String command,
            boolean legacy,
            CatalogGuardMapper.PreviewRow ticket,
            Runnable impactConfirmation) {
        Manifest manifest = candidate.manifest();
        if (ticket != null
                && (!app.applicationId().equals(ticket.applicationId())
                        || !app.ownerPrincipalId().equals(ticket.ownerPrincipal())
                        || !CatalogPublication.hash(candidate).equals(ticket.candidateHash())))
            throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
        String bodyHash =
                ticket == null
                        ? CatalogPublication.hash(candidate)
                        : AccessValues.hash("catalog-release-publish/v1", ticket.id());
        var prior = releases.command(app.applicationId(), command);
        // 同键成功重试先读取原回执，不用当前更高版本重算差异或倒退指针。
        if (prior != null) {
            if (!publisherPrincipal.equals(prior.publishedBy()))
                throw new GovernanceException(COMMAND_CONFLICT);
            // 旧摘要使用String.valueOf(null)；元数据逐字段比较避免原因文本"null"与缺省原因碰撞，兼容已保存摘要。
            var source = candidate.source();
            if (!bodyHash.equals(prior.commandHash())
                    || !Objects.equals(candidate.reason(), prior.reason())
                    || !Objects.equals(
                            source == null ? null : source.commit(), prior.sourceCommit())
                    || !Objects.equals(
                            source == null ? null : source.artifactHash(), prior.artifactHash())
                    || !Objects.equals(
                            candidate.decision() == null ? null : candidate.decision().code(),
                            prior.decision())) throw new GovernanceException(COMMAND_CONFLICT);
            return CatalogPublication.preview(prior.previewJson());
        }
        if (ticket == null && guards.policy(app.applicationId()) != null)
            throw new GovernanceException(VERSION_CONFLICT);
        if (ticket != null) {
            var current = basis(app);
            var fresh = guards.ticket(ticket.id());
            if (fresh == null
                    || !fresh.unexpired()
                    || ticket.baseVersion() != app.manifestVersion()
                    || !Objects.equals(ticket.baseContentHash(), current.contentHash())
                    || !Objects.equals(ticket.basePresentationHash(), current.presentationHash()))
                throw new GovernanceException(VERSION_CONFLICT);
            impactConfirmation.run();
            // 排队与影响SQL会消耗有效期，不能仅用获取票据时的旧unexpired标志。
            if (!guards.ticket(ticket.id()).unexpired())
                throw new GovernanceException(VERSION_CONFLICT);
        }
        Preview result = preview(app, manifest);
        if (!result.publishable()) {
            throw new GovernanceException(VERSION_CONFLICT);
        }
        Snapshot existing = mapper.snapshot(app.applicationId(), manifest.manifestVersion());
        if (existing != null) {
            if (!legacy) throw new GovernanceException(VERSION_CONFLICT);
            if (!existing.contentHash().equals(result.contentHash())
                    || app.manifestVersion() != existing.version()) {
                throw new GovernanceException(VERSION_CONFLICT);
            }
            return result;
        }
        // 历史成功命令已在前面只读返回；新菜单引用墓碑不能借目录发布重新启用。
        var menuCapabilities =
                manifest.menus().stream().flatMap(m -> m.anyOf().stream()).distinct().toList();
        if (!menuCapabilities.isEmpty()
                && lifecycles.retired(app.applicationId(), menuCapabilities))
            throw new GovernanceException(CAPABILITY_DEPRECATED);
        var base = mapper.snapshot(app.applicationId(), app.manifestVersion());
        var baseShown = mapper.presentation(app.applicationId(), app.manifestVersion());
        requireOne(
                mapper.insertSnapshot(
                        new Snapshot(
                                app.applicationId(),
                                manifest.manifestVersion(),
                                result.contentHash(),
                                CatalogManifest.json(manifest)),
                        publisherPrincipal));
        // 名称与权限同事务提交，审计失败不能留下半个可见目录。
        String presentation = CatalogManifest.presentationJson(manifest);
        if (!"[]".equals(presentation)) {
            requireOne(
                    mapper.insertPresentation(
                            new PresentationSnapshot(
                                    app.applicationId(),
                                    manifest.manifestVersion(),
                                    CatalogManifest.presentationHash(manifest),
                                    presentation),
                            publisherPrincipal));
        }
        requireOne(
                mapper.advance(
                        app.applicationId(), app.manifestVersion(), manifest.manifestVersion()));
        requireOne(
                mapper.audit(
                        UUID.randomUUID().toString(),
                        app.applicationId(),
                        publisherPrincipal,
                        "PUBLISH",
                        manifest.manifestVersion(),
                        command));
        var source = candidate.source();
        requireOne(
                releases.insert(
                        new CatalogReleaseMapper.Row(
                                app.applicationId(),
                                manifest.manifestVersion(),
                                result.contentHash(),
                                result.presentationHash(),
                                publisherPrincipal,
                                null,
                                command,
                                bodyHash,
                                source == null ? null : source.commit(),
                                source == null ? null : source.artifactHash(),
                                candidate.reason(),
                                candidate.decision() == null ? null : candidate.decision().code(),
                                app.manifestVersion(),
                                base == null ? null : base.contentHash(),
                                base == null
                                        ? null
                                        : baseShown == null
                                                ? CatalogManifest.presentationHash(
                                                        CatalogManifest.read(base.manifestJson()))
                                                : baseShown.presentationHash(),
                                CatalogPublication.previewJson(result))));
        return result;
    }

    /** 新预览保存固定候选及依据，不改变目录、角色或Grant。 */
    public Ticket releasePreview(
            VerifiedLogin login, PreviewInput input, PortalPermissions diagnostics) {
        if (input == null) throw new GovernanceException(INVALID_ARGUMENT);
        var candidate = CatalogPublication.normalize(input.candidate());
        return transaction.execute(
                status -> {
                    var app = requireOwner(login, candidate.manifest().application(), true);
                    var current = basis(app);
                    var diff = preview(app, candidate.manifest());
                    if (!diff.publishable() || diff.proposedVersion() <= diff.currentVersion())
                        throw new GovernanceException(VERSION_CONFLICT);
                    if (CatalogPublication.needsDecision(diff, current.capabilities())
                            && (candidate.reason() == null || candidate.decision() == null))
                        throw new GovernanceException(INVALID_ARGUMENT);
                    confirmImpact(login, candidate, input.impact(), diagnostics);
                    return freezePreview(app, candidate, diff, current, input.impact());
                });
    }

    private Ticket freezePreview(
            Application app, Candidate candidate, Preview diff, Basis current, Impact impact) {
        String id = UUID.randomUUID().toString();
        requireOne(
                guards.preview(
                        new CatalogGuardMapper.PreviewRow(
                                id,
                                app.applicationId(),
                                app.ownerPrincipalId(),
                                CatalogPublication.candidateJson(candidate),
                                CatalogPublication.hash(candidate),
                                CatalogPublication.previewJson(diff),
                                app.manifestVersion(),
                                current.contentHash(),
                                current.presentationHash(),
                                CatalogPublication.impactJson(impact),
                                null,
                                false)));
        var row = guards.ticket(id);
        return new Ticket(
                id,
                row.expiresAt(),
                row.baseVersion(),
                row.baseContentHash(),
                row.basePresentationHash(),
                candidate,
                diff,
                impact);
    }

    /** 机器发布预览的包内回执，发布继续使用已锁定候选与依据，不扩大公开调用边界。 */
    record MachinePreview(
            com.lrj.authz.governance.catalog.domain.CatalogPublisherModels.Eligibility eligibility,
            Preview preview,
            Ticket ticket) {}

    /** 机器只能生成GUARDED应用的非语义票据；需要Owner的候选只返回差异。 */
    MachinePreview machinePreviewLocked(Application app, Candidate input) {
        var candidate = CatalogPublication.normalize(input);
        var current = basis(app);
        if (guards.policy(app.applicationId()) == null)
            throw new GovernanceException(VERSION_CONFLICT);
        var diff = preview(app, candidate.manifest());
        if (!diff.publishable() || diff.proposedVersion() <= diff.currentVersion())
            throw new GovernanceException(VERSION_CONFLICT);
        var eligibility = CatalogPublisherPolicy.classify(current.manifest(), candidate.manifest());
        return new MachinePreview(
                eligibility,
                diff,
                eligibility
                                == com.lrj.authz.governance.catalog.domain.CatalogPublisherModels
                                        .Eligibility.AUTOMATION_ALLOWED
                        ? freezePreview(app, candidate, diff, current, null)
                        : null);
    }

    /** 包内受限适配器已锁定SERVICE和委派；原回执优先于新基础或过期票据。 */
    Release machinePublishLocked(
            Application app,
            String servicePrincipal,
            String command,
            CatalogGuardMapper.PreviewRow ticket) {
        if (ticket == null || !guards.machineSource(ticket.id()))
            throw new GovernanceException(ACCESS_DENIED);
        var candidate = CatalogPublication.candidate(ticket.candidateJson());
        publishLocked(
                app,
                servicePrincipal,
                candidate,
                command,
                false,
                ticket,
                () -> {
                    if (ticket.impactJson() != null || candidate.decision() != null)
                        throw new GovernanceException(ACCESS_DENIED);
                    if (CatalogPublisherPolicy.classify(basis(app).manifest(), candidate.manifest())
                            != com.lrj.authz.governance.catalog.domain.CatalogPublisherModels
                                    .Eligibility.AUTOMATION_ALLOWED)
                        throw new GovernanceException(VERSION_CONFLICT);
                });
        return release(releases.command(app.applicationId(), command));
    }

    /** 仅包内受限回执用例调用；对外仍由原Owner或机器当前资格保护。 */
    Release publisherReceipt(String app, String command) {
        return release(releases.command(app, command));
    }

    /** 发布内容只能来自不可变票据；已成功原命令先于期限／当前基础检查返回。 */
    public Release releasePublish(
            VerifiedLogin login, PublishCommand command, PortalPermissions diagnostics) {
        if (command == null) throw new GovernanceException(INVALID_ARGUMENT);
        BootstrapCommand.uuid(command.commandId());
        BootstrapCommand.uuid(command.previewId());
        return transaction.execute(
                status -> {
                    var row = guards.ticket(command.previewId());
                    if (row == null) throw new GovernanceException(ACCESS_DENIED);
                    var candidate = CatalogPublication.candidate(row.candidateJson());
                    publishCandidate(
                            login, candidate, command.commandId(), false, row, diagnostics);
                    return release(releases.command(row.applicationId(), command.commandId()));
                });
    }

    /** 单向启用与审计同一行提交；Owner显式确认旧节点退出，不自动生产启用。 */
    public Policy enableGuard(VerifiedLogin login, Enable command) {
        if (command == null) throw new GovernanceException(INVALID_ARGUMENT);
        BootstrapCommand.uuid(command.commandId());
        BootstrapCommand.bounded(command.reason(), 500);
        if (!command.legacyWritersExited() || command.expectedVersion() != 0)
            throw new GovernanceException(INVALID_ARGUMENT);
        return transaction.execute(
                status -> {
                    var app = requireOwner(login, command.applicationId(), true);
                    String hash =
                            AccessValues.hash(
                                    command.applicationId(),
                                    command.expectedVersion(),
                                    command.legacyWritersExited(),
                                    command.reason());
                    var prior = guards.policy(app.applicationId());
                    if (prior != null) {
                        if (!prior.commandId().equals(command.commandId()))
                            throw new GovernanceException(VERSION_CONFLICT);
                        if (!prior.commandHash().equals(hash))
                            throw new GovernanceException(COMMAND_CONFLICT);
                        return policy(prior);
                    }
                    if (!app.ownerPrincipalId()
                            .equals(
                                    mapper.lockOwner(
                                            login.issuer(),
                                            login.subject(),
                                            app.ownerPrincipalId())))
                        throw new GovernanceException(ACCESS_DENIED);
                    requireOne(
                            guards.enable(
                                    app.applicationId(),
                                    app.ownerPrincipalId(),
                                    command.reason(),
                                    command.commandId(),
                                    hash));
                    return policy(guards.policy(app.applicationId()));
                });
    }

    /** 当前Owner查询写模式，没有记录的原应用继续LEGACY。 */
    public Policy guardPolicy(VerifiedLogin login, String application) {
        requireOwner(login, application, false);
        var row = guards.policy(application);
        return row == null
                ? new Policy(application, Mode.LEGACY, 0, null, null, null, null)
                : policy(row);
    }

    /** 当前Owner只读核对固定来源；部署声明来自服务配置，运行仍明确未知。 */
    public com.lrj.authz.governance.catalog.domain.CatalogDriftModels.Report drift(
            VerifiedLogin login,
            Candidate input,
            java.util.List<
                            com.lrj.authz.governance.catalog.domain.CatalogDriftModels
                                    .DeploymentDeclaration>
                    declarations) {
        var candidate = CatalogPublication.normalize(input);
        var manifest = candidate.manifest();
        var app = requireOwner(login, manifest.application(), false);
        var declared =
                declarations.stream()
                        .filter(value -> value.applicationId().equals(app.applicationId()))
                        .findFirst()
                        .orElse(null);
        if (declared != null) CatalogDrift.validate(declared);
        var published =
                app.manifestVersion() == 0
                        ? null
                        : release(
                                releases.version(app.applicationId(), app.manifestVersion()),
                                basis(app).presentationHash());
        String content = CatalogManifest.hash(manifest),
                presentation = CatalogManifest.presentationHash(manifest);
        var state =
                CatalogDrift.compare(
                        manifest.manifestVersion(),
                        content,
                        presentation,
                        candidate.source(),
                        published);
        // 旧发布没有显示sidecar仍可从固定Manifest计算空展示摘要，不伪造来源记录。
        var diff = app.manifestVersion() == 0 ? null : preview(app, manifest);
        var deployment =
                new com.lrj.authz.governance.catalog.domain.CatalogDriftModels.Deployment(
                        declared == null
                                ? com.lrj.authz.governance.catalog.domain.CatalogDriftModels.State
                                        .UNKNOWN
                                : CatalogDrift.compare(
                                        declared.manifestVersion(),
                                        declared.contentHash(),
                                        declared.presentationHash(),
                                        declared.source(),
                                        published),
                        declared);
        // 再读当前Owner／版本，发现并发发布要求重查，不把读到的旧基础冒充最新。
        if (requireOwner(login, app.applicationId(), false).manifestVersion()
                != app.manifestVersion()) throw new GovernanceException(VERSION_CONFLICT);
        return new com.lrj.authz.governance.catalog.domain.CatalogDriftModels.Report(
                app.applicationId(),
                java.time.Instant.now().toString(),
                manifest.manifestVersion(),
                content,
                presentation,
                candidate.source(),
                published,
                state,
                diff,
                deployment,
                com.lrj.authz.governance.catalog.domain.CatalogDriftModels.State.UNKNOWN);
    }

    private static Policy policy(CatalogGuardMapper.PolicyRow row) {
        return new Policy(
                row.applicationId(),
                Mode.GUARDED,
                1,
                row.enabledBy(),
                row.enabledAt(),
                row.reason(),
                row.commandId());
    }

    private void confirmImpact(
            VerifiedLogin login,
            Candidate candidate,
            Impact impact,
            PortalPermissions diagnostics) {
        if (impact == null) return;
        CatalogPublication.validateImpact(impact);
        if (!candidate.manifest().application().equals(impact.applicationId()))
            throw new GovernanceException(INVALID_ARGUMENT);
        if (diagnostics == null) throw new GovernanceException(ACCESS_DENIED);
        var report =
                diagnostics.catalogImpact(
                        login,
                        new com.lrj.authz.governance.access.domain.AccessModels.Partition(
                                impact.tenantId(), impact.applicationId(), impact.environment()),
                        candidate.manifest(),
                        null);
        if (report.completeness()
                        != com.lrj.authz.governance.catalog.domain.CatalogImpactModels.Completeness
                                .COMPLETE
                || !impact.basisHash().equals(report.basisHash()))
            throw new GovernanceException(VERSION_CONFLICT);
    }

    /** 目录变更的原版本与依据快照，预览与发布不能混用不同观察版本。 */
    private record Basis(
            String contentHash,
            String presentationHash,
            Set<String> capabilities,
            Manifest manifest) {}

    private Basis basis(Application app) {
        if (app.manifestVersion() == 0) return new Basis(null, null, Set.of(), null);
        var row = mapper.snapshot(app.applicationId(), app.manifestVersion());
        if (row == null) throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
        var shown = mapper.presentation(app.applicationId(), app.manifestVersion());
        try {
            var manifest =
                    CatalogManifest.withPresentation(
                            CatalogManifest.read(row.manifestJson()),
                            shown == null ? "[]" : shown.presentationJson(),
                            shown == null ? null : shown.presentationHash());
            if (!app.applicationId().equals(manifest.application())
                    || app.manifestVersion() != manifest.manifestVersion()
                    || !row.contentHash().equals(CatalogManifest.hash(manifest)))
                throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
            return new Basis(
                    row.contentHash(),
                    CatalogManifest.presentationHash(manifest),
                    Set.copyOf(manifest.capabilities().stream().map(Capability::code).toList()),
                    manifest);
        } catch (GovernanceException corrupt) {
            throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
        }
    }

    /** 历史分页受当前Owner保护，旧发布无来源记录仍保留在版本列表。 */
    public History history(VerifiedLogin login, String application, Long before) {
        if (before != null && before < 1) throw new GovernanceException(INVALID_ARGUMENT);
        requireOwner(login, application, false);
        var rows = releases.history(application, before, 101);
        var page = rows.subList(0, Math.min(rows.size(), 100));
        return new History(
                page.stream().map(ApplicationCatalog::release).toList(),
                rows.size() > 100 ? page.getLast().version() : null);
    }

    /** 固定版本重验双摘要；不读取当前版本假装历史详情。 */
    public Detail releaseDetail(VerifiedLogin login, String application, long version) {
        if (version < 1) throw new GovernanceException(INVALID_ARGUMENT);
        requireOwner(login, application, false);
        var row = releases.version(application, version);
        if (row == null) throw new GovernanceException(NOT_FOUND);
        var snapshot = mapper.snapshot(application, version);
        var shown = mapper.presentation(application, version);
        try {
            var manifest =
                    CatalogManifest.withPresentation(
                            CatalogManifest.read(snapshot.manifestJson()),
                            shown == null ? "[]" : shown.presentationJson(),
                            shown == null ? null : shown.presentationHash());
            if (!application.equals(manifest.application())
                    || version != manifest.manifestVersion()
                    || !CatalogManifest.hash(manifest).equals(row.contentHash())
                    || row.presentationHash() != null
                            && !CatalogManifest.presentationHash(manifest)
                                    .equals(row.presentationHash()))
                throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
            return new Detail(release(row), manifest);
        } catch (GovernanceException failure) {
            throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
        }
    }

    private static Release release(CatalogReleaseMapper.Row row) {
        return release(row, row == null ? null : row.presentationHash());
    }

    private static Release release(CatalogReleaseMapper.Row row, String presentationHash) {
        if (row == null) throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
        try {
            return new Release(
                    row.application(),
                    row.version(),
                    row.contentHash(),
                    presentationHash,
                    row.publishedBy(),
                    row.publishedAt(),
                    row.sourceCommit() == null
                            ? null
                            : new Source(row.sourceCommit(), row.artifactHash()),
                    row.reason(),
                    row.decision() == null ? null : Decision.from(row.decision()),
                    row.commandId(),
                    row.baseVersion(),
                    row.baseContentHash(),
                    row.basePresentationHash(),
                    row.previewJson() == null
                            ? null
                            : CatalogPublication.preview(row.previewJson()));
        } catch (IllegalArgumentException corrupt) {
            throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
        }
    }

    /** 当前清单仅供已拥有该应用的管理用户查询。 */
    public Manifest current(VerifiedLogin login, String application) {
        Application app = requireOwner(login, application, false);
        Snapshot row = mapper.snapshot(application, app.manifestVersion());
        if (row == null) {
            return null;
        }
        var presentation = mapper.presentation(application, app.manifestVersion());
        return CatalogManifest.withPresentation(
                CatalogManifest.read(row.manifestJson()),
                presentation == null ? "[]" : presentation.presentationJson(),
                presentation == null ? null : presentation.presentationHash());
    }

    /** 紧急停用不修改已发布RoleVersion；恢复也必须显式拥有者命令和新版本。 */
    public CapabilityState changeCapability(
            VerifiedLogin login,
            String application,
            String capability,
            boolean disabled,
            long expectedVersion,
            String reason,
            String command) {
        CatalogManifest.code(capability);
        BootstrapCommand.uuid(command);
        BootstrapCommand.bounded(reason, 500);
        if (expectedVersion < 0) throw new GovernanceException(INVALID_ARGUMENT);
        return transaction.execute(
                status -> {
                    Application app = requireOwner(login, application, true);
                    var snapshot = mapper.snapshot(application, app.manifestVersion());
                    if (snapshot == null
                            || CatalogManifest.read(snapshot.manifestJson()).capabilities().stream()
                                    .noneMatch(c -> c.code().equals(capability)))
                        throw new GovernanceException(ACCESS_DENIED);
                    String hash =
                            AccessValues.hash(
                                    application, capability, disabled, expectedVersion, reason);
                    String previous = safety.commandHash(application, command);
                    if (previous != null) {
                        if (!previous.equals(hash)) throw new GovernanceException(COMMAND_CONFLICT);
                        return safety.commandResult(application, command);
                    }
                    var state = safety.capability(application, capability);
                    if ((state == null ? 0 : state.version()) != expectedVersion)
                        throw new GovernanceException(VERSION_CONFLICT);
                    requireOne(
                            safety.changeCapability(
                                    application, capability, disabled, expectedVersion + 1));
                    requireOne(
                            safety.capabilityAudit(
                                    application,
                                    capability,
                                    command,
                                    hash,
                                    app.ownerPrincipalId(),
                                    reason,
                                    expectedVersion,
                                    disabled));
                    return safety.capability(application, capability);
                });
    }

    /** 弃用只停止新增使用；应用排他锁、连续版本与原命令审计同事务提交。 */
    public com.lrj.authz.protocol.CapabilityLifecycleDtos.Mutation changeLifecycle(
            VerifiedLogin login, com.lrj.authz.protocol.CapabilityLifecycleDtos.Change input) {
        if (input == null
                || input.state() == null
                || input.state() == com.lrj.authz.protocol.CapabilityLifecycleDtos.State.RETIRED
                || input.expectedVersion() == null
                || input.expectedVersion() < 0
                || input.expectedVersion() == Long.MAX_VALUE)
            throw new GovernanceException(INVALID_ARGUMENT);
        CatalogManifest.code(input.applicationId());
        CatalogManifest.code(input.capability());
        BootstrapCommand.uuid(input.commandId());
        BootstrapCommand.bounded(input.reason(), 500);
        return transaction.execute(
                status -> {
                    var app = requireOwner(login, input.applicationId(), true);
                    if (!app.ownerPrincipalId()
                            .equals(
                                    mapper.lockOwner(
                                            login.issuer(),
                                            login.subject(),
                                            app.ownerPrincipalId())))
                        throw new GovernanceException(ACCESS_DENIED);
                    var snapshot = mapper.snapshot(app.applicationId(), app.manifestVersion());
                    if (snapshot == null
                            || CatalogManifest.read(snapshot.manifestJson()).capabilities().stream()
                                    .noneMatch(c -> c.code().equals(input.capability())))
                        throw new GovernanceException(ACCESS_DENIED);
                    String hash =
                            AccessValues.hash(
                                    input.applicationId(),
                                    input.capability(),
                                    input.state().code(),
                                    input.expectedVersion(),
                                    input.reason());
                    var previous = lifecycles.receipt(app.applicationId(), input.commandId());
                    if (previous != null) {
                        if (!previous.payloadHash().equals(hash))
                            throw new GovernanceException(COMMAND_CONFLICT);
                        return new com.lrj.authz.protocol.CapabilityLifecycleDtos.Mutation(
                                lifecycleValue(app.applicationId(), input.capability()),
                                lifecycleReceipt(previous));
                    }
                    var current = lifecycleValue(app.applicationId(), input.capability());
                    if (current.state()
                                    == com.lrj.authz.protocol.CapabilityLifecycleDtos.State.RETIRED
                            || current.version() != input.expectedVersion()
                            || current.state() == input.state())
                        throw new GovernanceException(VERSION_CONFLICT);
                    var now = java.time.Instant.now();
                    requireOne(
                            lifecycles.change(
                                    new com.lrj.authz.governance.access.domain
                                            .CapabilityLifecycleModels.Value(
                                            app.applicationId(),
                                            input.capability(),
                                            input.state().code(),
                                            current.version() + 1,
                                            input.reason(),
                                            app.ownerPrincipalId(),
                                            now),
                                    current.version()));
                    var receipt =
                            new com.lrj.authz.governance.access.domain.CapabilityLifecycleModels
                                    .Receipt(
                                    app.applicationId(),
                                    input.commandId(),
                                    hash,
                                    input.capability(),
                                    current.state().code(),
                                    input.state().code(),
                                    current.version(),
                                    current.version() + 1,
                                    input.reason(),
                                    app.ownerPrincipalId(),
                                    now);
                    requireOne(lifecycles.audit(receipt));
                    // 实际PG存储按微秒截断时间，响应从权威行重读，重放字节不受Java纳秒影响。
                    return new com.lrj.authz.protocol.CapabilityLifecycleDtos.Mutation(
                            lifecycleValue(app.applicationId(), input.capability()),
                            lifecycleReceipt(
                                    lifecycles.receipt(app.applicationId(), input.commandId())));
                });
    }

    private com.lrj.authz.protocol.CapabilityLifecycleDtos.Value lifecycleValue(
            String app, String cap) {
        var row = lifecycles.value(app, cap);
        return row == null
                ? new com.lrj.authz.protocol.CapabilityLifecycleDtos.Value(
                        app,
                        cap,
                        com.lrj.authz.protocol.CapabilityLifecycleDtos.State.ACTIVE,
                        0,
                        null,
                        null,
                        null)
                : new com.lrj.authz.protocol.CapabilityLifecycleDtos.Value(
                        app,
                        cap,
                        lifecycleState(row.state()),
                        row.version(),
                        row.reason(),
                        row.changedBy(),
                        row.updatedAt().toString());
    }

    private static com.lrj.authz.protocol.CapabilityLifecycleDtos.State lifecycleState(
            String code) {
        return Arrays.stream(com.lrj.authz.protocol.CapabilityLifecycleDtos.State.values())
                .filter(s -> s.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new GovernanceException(DEPENDENCY_UNAVAILABLE));
    }

    private static com.lrj.authz.protocol.CapabilityLifecycleDtos.Receipt lifecycleReceipt(
            com.lrj.authz.governance.access.domain.CapabilityLifecycleModels.Receipt row) {
        if (row == null) throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
        return new com.lrj.authz.protocol.CapabilityLifecycleDtos.Receipt(
                row.commandId(),
                row.applicationId(),
                row.capability(),
                lifecycleState(row.beforeState()),
                lifecycleState(row.afterState()),
                row.beforeVersion(),
                row.afterVersion(),
                row.reason(),
                row.actor(),
                row.createdAt().toString());
    }

    private Application requireOwner(VerifiedLogin login, String application, boolean lock) {
        CatalogManifest.code(application);
        String principal = identity.principalForLogin(login.issuer(), login.subject()).id();
        Application app =
                lock ? mapper.lockApplication(application) : mapper.application(application);
        if (app == null || !principal.equals(app.ownerPrincipalId())) {
            throw new GovernanceException(ACCESS_DENIED);
        }
        return app;
    }

    private Preview preview(Application app, Manifest manifest) {
        Snapshot previous = mapper.snapshot(app.applicationId(), app.manifestVersion());
        if (app.manifestVersion() > 0 && previous == null) {
            throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
        }
        var shown =
                previous == null
                        ? null
                        : mapper.presentation(app.applicationId(), app.manifestVersion());
        Manifest old =
                previous == null
                        ? null
                        : CatalogManifest.withPresentation(
                                CatalogManifest.read(previous.manifestJson()),
                                shown == null ? "[]" : shown.presentationJson(),
                                shown == null ? null : shown.presentationHash());
        return CatalogDiff.compare(app.applicationId(), app.manifestVersion(), old, manifest);
    }

    private static void requireOne(int count) {
        if (count != 1) {
            throw new GovernanceException(VERSION_CONFLICT);
        }
    }
}
