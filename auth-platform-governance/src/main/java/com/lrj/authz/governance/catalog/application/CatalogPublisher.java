package com.lrj.authz.governance.catalog.application;

import static com.lrj.authz.governance.shared.application.GovernanceException.Code.*;

import com.lrj.authz.governance.catalog.configuration.CatalogPublisherSettings;
import com.lrj.authz.governance.catalog.domain.CatalogModels.Application;
import com.lrj.authz.governance.catalog.domain.CatalogPublisherModels.*;
import com.lrj.authz.governance.catalog.persistence.CatalogGuardMapper;
import com.lrj.authz.governance.catalog.persistence.CatalogMapper;
import com.lrj.authz.governance.catalog.persistence.CatalogPublisherMapper;
import com.lrj.authz.governance.catalog.persistence.CatalogReleaseMapper;
import com.lrj.authz.governance.identity.application.BootstrapCommand;
import com.lrj.authz.governance.identity.application.IdentityGovernance;
import com.lrj.authz.governance.identity.authentication.MachineTokenAuthority;
import com.lrj.authz.governance.identity.authentication.VerifiedLogin;
import com.lrj.authz.governance.identity.authentication.VerifiedMachine;
import com.lrj.authz.governance.identity.domain.IdentityModels.*;
import com.lrj.authz.governance.identity.persistence.IdentityMapper;
import com.lrj.authz.governance.shared.application.AccessValues;
import com.lrj.authz.governance.shared.application.GovernanceException;

import org.springframework.transaction.support.TransactionTemplate;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** HUMAN委派和SERVICE发布分别授权，固定发布仍调用唯一目录内核，不写任何业务Grant。 */
public final class CatalogPublisher {
    private static final int MAX_DELEGATIONS = 100;
    private final CatalogPublisherSettings settings;
    private final Map<String, MachineTokenAuthority> slots;
    private final CatalogPublisherMapper publishers;
    private final CatalogMapper catalogs;
    private final CatalogGuardMapper guards;
    private final CatalogReleaseMapper releases;
    private final IdentityMapper identities;
    private final IdentityGovernance identity;
    private final ApplicationCatalog catalog;
    private final TransactionTemplate transaction;

    /** 启动只验证受控目标，不创建实例身份、委派或目录。 */
    public CatalogPublisher(
            CatalogPublisherSettings settings,
            CatalogPublisherMapper publishers,
            CatalogMapper catalogs,
            CatalogGuardMapper guards,
            CatalogReleaseMapper releases,
            IdentityMapper identities,
            IdentityGovernance identity,
            ApplicationCatalog catalog,
            TransactionTemplate transaction) {
        this.settings = settings;
        this.publishers = publishers;
        this.catalogs = catalogs;
        this.guards = guards;
        this.releases = releases;
        this.identities = identities;
        this.identity = identity;
        this.catalog = catalog;
        this.transaction = transaction;
        this.slots =
                settings.authorities().stream()
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        MachineTokenAuthority::publisherId, value -> value));
        if (!settings.target().equals(publishers.target()))
            throw new GovernanceException(BINDING_CONFLICT);
    }

    /** 仅受控CLI初始化目标；重复精确目标可读取，绝不覆盖既有绑定或秘密。 */
    public static Target initializeTarget(
            CatalogPublisherMapper publishers,
            TransactionTemplate transaction,
            Target target,
            String operator,
            String reason) {
        BootstrapCommand.uuid(target.targetInstanceId());
        CatalogManifest.code(target.environment());
        BootstrapCommand.bounded(operator, 100);
        BootstrapCommand.bounded(reason, 500);
        return transaction.execute(
                status -> {
                    publishers.initializeTarget(target, operator, reason);
                    var current = publishers.target();
                    if (!target.equals(current)) throw new GovernanceException(BINDING_CONFLICT);
                    return current;
                });
    }

    /** 当前HUMAN Owner仅能委派属于自己应用的后端固定slot；不会创建人员成员关系。 */
    public Delegation create(VerifiedLogin login, Create input) {
        if (input == null) throw new GovernanceException(INVALID_ARGUMENT);
        CatalogManifest.code(input.applicationId());
        BootstrapCommand.uuid(input.publisherId());
        BootstrapCommand.uuid(input.commandId());
        BootstrapCommand.bounded(input.reason(), 500);
        var slot = slot(input.publisherId());
        if (!input.applicationId().equals(slot.applicationId()))
            throw new GovernanceException(ACCESS_DENIED);
        String until = instant(input.validUntil());
        String hash =
                AccessValues.hash(
                        "catalog-publisher/create/v1",
                        input.applicationId(),
                        input.publisherId(),
                        slot.servicePrincipal(),
                        slot.targetInstanceId(),
                        slot.environment(),
                        slot.tokenAuthority().issuer(),
                        slot.subject(),
                        slot.tokenAuthority().clientId(),
                        until,
                        input.reason());
        return transaction.execute(
                status -> {
                    var app = owner(login, input.applicationId());
                    var prior = publishers.event(app.applicationId(), input.commandId());
                    if (prior != null) return replay(prior, app.ownerPrincipalId(), hash);
                    if (!publishers.validPeriod(until))
                        throw new GovernanceException(INVALID_ARGUMENT);
                    if (publishers.publisher(app.applicationId(), slot.publisherId()) != null)
                        throw new GovernanceException(BINDING_CONFLICT);
                    // 受控slot只能建立SERVICE；遇到已有人类身份或停用主体不可夺取绑定或恢复。
                    identities.insertPrincipal(
                            new Principal(
                                    slot.servicePrincipal(),
                                    PrincipalKind.SERVICE,
                                    GlobalStatus.ACTIVE,
                                    1));
                    var principal = identities.principal(slot.servicePrincipal());
                    if (principal == null
                            || principal.kind() != PrincipalKind.SERVICE
                            || principal.status() != GlobalStatus.ACTIVE)
                        throw new GovernanceException(BINDING_CONFLICT);
                    var binding =
                            new LoginIdentity(
                                    slot.tokenAuthority().issuer(),
                                    slot.subject(),
                                    slot.servicePrincipal());
                    identities.insertLoginIdentity(binding);
                    if (!binding.equals(
                            identities.loginIdentity(binding.issuer(), binding.subject())))
                        throw new GovernanceException(BINDING_CONFLICT);
                    String id = UUID.randomUUID().toString();
                    if (publishers.create(
                                    id,
                                    slot,
                                    app.ownerPrincipalId(),
                                    until,
                                    input.commandId(),
                                    input.reason())
                            != 1) throw new GovernanceException(BINDING_CONFLICT);
                    var result = delegation(publishers.delegation(app.applicationId(), id));
                    requireOne(
                            publishers.eventInsert(
                                    app.applicationId(),
                                    input.commandId(),
                                    id,
                                    app.ownerPrincipalId(),
                                    "CREATE",
                                    hash,
                                    0,
                                    1,
                                    input.reason(),
                                    CatalogPublisherJson.delegation(result)));
                    return result;
                });
    }

    /** 单向禁用与机器发布争用同一应用锁；成功后旧Token不能取得下一次发布资格。 */
    public Delegation disable(VerifiedLogin login, Disable input) {
        if (input == null) throw new GovernanceException(INVALID_ARGUMENT);
        CatalogManifest.code(input.applicationId());
        BootstrapCommand.uuid(input.delegationId());
        BootstrapCommand.uuid(input.commandId());
        BootstrapCommand.bounded(input.reason(), 500);
        if (input.expectedVersion() < 1) throw new GovernanceException(INVALID_ARGUMENT);
        String hash =
                AccessValues.hash(
                        "catalog-publisher/disable/v1",
                        input.applicationId(),
                        input.delegationId(),
                        input.expectedVersion(),
                        input.reason());
        return transaction.execute(
                status -> {
                    var app = owner(login, input.applicationId());
                    var prior = publishers.event(app.applicationId(), input.commandId());
                    if (prior != null) return replay(prior, app.ownerPrincipalId(), hash);
                    var row = publishers.delegation(app.applicationId(), input.delegationId());
                    if (row == null) throw new GovernanceException(ACCESS_DENIED);
                    if (publishers.disable(
                                    app.applicationId(),
                                    input.delegationId(),
                                    input.expectedVersion())
                            != 1) throw new GovernanceException(VERSION_CONFLICT);
                    var current = delegation(publishers.delegation(app.applicationId(), row.id()));
                    var result =
                            new Delegation(
                                    current.delegationId(),
                                    current.publisherId(),
                                    current.applicationId(),
                                    current.targetInstanceId(),
                                    current.environment(),
                                    current.servicePrincipal(),
                                    current.ownerPrincipal(),
                                    current.status(),
                                    current.version(),
                                    current.validUntil(),
                                    input.commandId(),
                                    input.reason(),
                                    current.createdAt(),
                                    current.disabledAt());
                    requireOne(
                            publishers.eventInsert(
                                    app.applicationId(),
                                    input.commandId(),
                                    row.id(),
                                    app.ownerPrincipalId(),
                                    "DISABLE",
                                    hash,
                                    row.version(),
                                    result.version(),
                                    input.reason(),
                                    CatalogPublisherJson.delegation(result)));
                    return result;
                });
    }

    /** 只返回自己应用的完整有界元数据；凭据永不进入列表。 */
    public Delegations list(VerifiedLogin login, String application) {
        CatalogManifest.code(application);
        return transaction.execute(
                status -> {
                    var app = owner(login, application);
                    var rows = publishers.list(app.applicationId(), MAX_DELEGATIONS + 1);
                    if (rows.size() > MAX_DELEGATIONS)
                        throw new GovernanceException(INVALID_ARGUMENT);
                    return new Delegations(
                            rows.stream().map(CatalogPublisher::delegation).toList());
                });
    }

    /** 机器只能预览固定目标；风险报告不假装具备人员影响诊断资格。 */
    public Report preview(VerifiedMachine machine, PreviewInput input) {
        if (input == null) throw new GovernanceException(INVALID_ARGUMENT);
        var slot = machineSlot(machine, input.targetInstanceId(), input.environment());
        var candidate = CatalogPublication.normalize(input.candidate());
        if (!slot.applicationId().equals(candidate.manifest().application()))
            throw new GovernanceException(ACCESS_DENIED);
        if (candidate.source() == null) throw new GovernanceException(INVALID_ARGUMENT);
        CatalogPublication.candidateJson(candidate);
        return transaction.execute(
                status -> {
                    var app = application(slot);
                    var row = authorize(machine, slot);
                    var result = catalog.machinePreviewLocked(app, candidate);
                    if (result.ticket() != null)
                        requireOne(publishers.previewInsert(result.ticket().previewId(), row));
                    authorize(machine, slot);
                    return new Report(
                            settings.target(),
                            slot.publisherId(),
                            row.id(),
                            CatalogPublication.hash(candidate),
                            result.eligibility(),
                            result.preview(),
                            result.ticket());
                });
    }

    /** 原命令、固定票据、真实SERVICE审计同事务；提交前再次核验期限。 */
    public Receipt publish(VerifiedMachine machine, Publish input) {
        if (input == null) throw new GovernanceException(INVALID_ARGUMENT);
        BootstrapCommand.uuid(input.commandId());
        BootstrapCommand.uuid(input.previewId());
        var slot = machineSlot(machine, input.targetInstanceId(), input.environment());
        return transaction.execute(
                status -> {
                    var app = application(slot);
                    var row = authorize(machine, slot);
                    var prior = releases.command(app.applicationId(), input.commandId());
                    var priorVersion =
                            publishers.receiptVersion(
                                    app.applicationId(), input.commandId(), row.id());
                    if (prior != null && priorVersion == null)
                        throw new GovernanceException(COMMAND_CONFLICT);
                    if (!publishers.ownPreview(input.previewId(), app.applicationId(), row.id()))
                        throw new GovernanceException(ACCESS_DENIED);
                    var ticket = guards.ticket(input.previewId());
                    var release =
                            catalog.machinePublishLocked(
                                    app, row.servicePrincipal(), input.commandId(), ticket);
                    if (prior == null)
                        requireOne(
                                publishers.releaseInsert(
                                        app.applicationId(), release.version(), row));
                    var actor = publishers.actor(app.applicationId(), release.version());
                    if (actor == null) throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
                    // actor写入和查询也消耗时间，不能只复用进入事务时的有效期结果。
                    authorize(machine, slot);
                    return new Receipt(release, actor);
                });
    }

    /** 未知结果只读原命令；当前凭据或委派失效仍然拒绝，不设认证重试后门。 */
    public Receipt receipt(VerifiedMachine machine, ReceiptQuery input) {
        if (input == null) throw new GovernanceException(INVALID_ARGUMENT);
        BootstrapCommand.uuid(input.commandId());
        var slot = machineSlot(machine, input.targetInstanceId(), input.environment());
        return transaction.execute(
                status -> {
                    var app = application(slot);
                    var row = authorize(machine, slot);
                    var version =
                            publishers.receiptVersion(
                                    app.applicationId(), input.commandId(), row.id());
                    if (version == null) throw new GovernanceException(NOT_FOUND);
                    var release = catalog.publisherReceipt(app.applicationId(), input.commandId());
                    var actor = publishers.actor(app.applicationId(), version);
                    if (actor == null || release.version() != version)
                        throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
                    authorize(machine, slot);
                    return new Receipt(release, actor);
                });
    }

    private Application owner(VerifiedLogin login, String app) {
        var principal = identity.principalForLogin(login.issuer(), login.subject());
        var current = catalogs.lockApplication(app);
        if (current == null
                || !principal.id().equals(current.ownerPrincipalId())
                || !principal
                        .id()
                        .equals(
                                catalogs.lockOwner(
                                        login.issuer(), login.subject(), principal.id())))
            throw new GovernanceException(ACCESS_DENIED);
        if (!settings.target().equals(publishers.target()))
            throw new GovernanceException(BINDING_CONFLICT);
        return current;
    }

    private Application application(MachineTokenAuthority slot) {
        var app = catalogs.lockApplication(slot.applicationId());
        if (app == null) throw new GovernanceException(ACCESS_DENIED);
        return app;
    }

    private MachineTokenAuthority slot(String id) {
        var slot = slots.get(id);
        if (slot == null) throw new GovernanceException(ACCESS_DENIED);
        return slot;
    }

    private MachineTokenAuthority machineSlot(
            VerifiedMachine machine, String target, String environment) {
        BootstrapCommand.uuid(target);
        CatalogManifest.code(environment);
        if (machine == null) throw new GovernanceException(INVALID_CREDENTIAL);
        var slot = slot(machine.publisherId());
        if (!slot.tokenAuthority().issuer().equals(machine.issuer())
                || !slot.subject().equals(machine.subject())
                || !slot.tokenAuthority().clientId().equals(machine.clientId())
                || machine.issuedAt() == null
                || machine.expiresAt() == null
                || !machine.expiresAt().isAfter(machine.issuedAt())
                || machine.issuedAt().isAfter(Instant.now())
                || java.time.Duration.between(machine.issuedAt(), machine.expiresAt())
                                .compareTo(java.time.Duration.ofSeconds(300))
                        > 0) throw new GovernanceException(INVALID_CREDENTIAL);
        if (!slot.targetInstanceId().equals(target) || !slot.environment().equals(environment))
            throw new GovernanceException(ACCESS_DENIED);
        return slot;
    }

    private CatalogPublisherMapper.Row authorize(
            VerifiedMachine machine, MachineTokenAuthority slot) {
        var row = publishers.authorize(slot, machine.expiresAt().toString());
        if (row == null)
            throw new GovernanceException(
                    publishers.tokenAlive(machine.expiresAt().toString())
                            ? ACCESS_DENIED
                            : INVALID_CREDENTIAL);
        return row;
    }

    private static Delegation replay(
            CatalogPublisherMapper.Event event, String owner, String hash) {
        if (!owner.equals(event.operatorPrincipal()) || !hash.equals(event.payloadHash()))
            throw new GovernanceException(COMMAND_CONFLICT);
        return CatalogPublisherJson.delegation(event.resultJson());
    }

    private static Delegation delegation(CatalogPublisherMapper.Row row) {
        if (row == null) throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
        try {
            return new Delegation(
                    row.id(),
                    row.publisherId(),
                    row.applicationId(),
                    row.targetInstanceId(),
                    row.environment(),
                    row.servicePrincipal(),
                    row.ownerPrincipal(),
                    Status.from(row.status()),
                    row.version(),
                    row.validUntil(),
                    row.commandId(),
                    row.reason(),
                    row.createdAt(),
                    row.disabledAt());
        } catch (IllegalArgumentException failure) {
            throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
        }
    }

    private static String instant(String input) {
        try {
            return Instant.parse(input).toString();
        } catch (DateTimeException | NullPointerException failure) {
            throw new GovernanceException(INVALID_ARGUMENT);
        }
    }

    private static void requireOne(int count) {
        if (count != 1) throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
    }
}
