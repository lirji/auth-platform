package com.lrj.authz.governance.access.application;

import static com.lrj.authz.governance.shared.application.GovernanceException.Code.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.lrj.authz.governance.access.domain.AccessModels.Partition;
import com.lrj.authz.governance.access.persistence.PermissionMapper;
import com.lrj.authz.governance.access.persistence.PersonnelImpactMapper;
import com.lrj.authz.governance.identity.application.BootstrapCommand;
import com.lrj.authz.governance.shared.application.AccessValues;
import com.lrj.authz.governance.shared.application.GovernanceException;
import com.lrj.authz.protocol.PersonnelImpactDtos.*;

import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** 人员核对不写Grant，不由历史状态推导当前资源ALLOW；外层仍需独立诊断授权。 */
public final class PersonnelImpact {
    private static final int PAGE_SIZE = 100, MAX_BASIS = 10000, MAX_CURSOR_BYTES = 256;
    private static final String CHANGE_CURSOR = "CHANGE", SOURCE_CURSOR = "SOURCE";
    private static final ObjectMapper JSON =
            new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final PersonnelImpactMapper mapper;
    private final PermissionMapper permissions;
    private final TransactionTemplate tx;

    /** 复用同一数据库和事务，前后两次全依据查询防止拼接不同时点事实。 */
    public PersonnelImpact(
            PersonnelImpactMapper mapper, PermissionMapper permissions, TransactionTemplate tx) {
        this.mapper = mapper;
        this.permissions = permissions;
        this.tx = tx;
    }

    private record Position(String source, long sequence, String grant) {}

    /** 仅PortalPermissions调用；不存在与跨租户统一拒绝，不以空报告泄露人员存在性。 */
    Report analyze(Partition p, String member, String changeCursor, String sourceCursor) {
        AccessValues.partition(p);
        BootstrapCommand.uuid(member);
        return tx.execute(
                status -> {
                    var target = mapper.target(p, member);
                    if (target == null) throw error(ACCESS_DENIED);
                    var before = mapper.basis(p, member);
                    String basis = AccessValues.hash(p, member, before.total(), before.hash());
                    var changesAt = decode(changeCursor, basis, CHANGE_CURSOR);
                    var sourcesAt = decode(sourceCursor, basis, SOURCE_CURSOR);
                    var dirs =
                            mapper.directories(p, member).stream()
                                    .limit(PAGE_SIZE)
                                    .map(
                                            d ->
                                                    new DirectoryFact(
                                                            d.sourceId(),
                                                            d.aggregateId(),
                                                            d.aggregateVersion(),
                                                            d.payloadHash(),
                                                            facts(d.factsJson()),
                                                            d.historyMissing()))
                                    .toList();
                    var upstream =
                            mapper.directorySources(p).stream()
                                    .limit(PAGE_SIZE)
                                    .map(
                                            s ->
                                                    new Source(
                                                            s.sourceId(),
                                                            s.lastSequence(),
                                                            s.quarantined(),
                                                            reasons(s.conflictReasons()),
                                                            SourceSync.UNKNOWN))
                                    .toList();
                    var changeRows =
                            mapper.changes(p, member, changesAt.source(), changesAt.sequence());
                    var changePage =
                            changeRows.stream()
                                    .limit(PAGE_SIZE)
                                    .map(
                                            c ->
                                                    new Change(
                                                            c.sourceId(),
                                                            c.eventId(),
                                                            c.partitionSequence(),
                                                            c.aggregateType(),
                                                            c.aggregateId(),
                                                            c.aggregateVersion(),
                                                            c.eventFingerprint(),
                                                            c.outcome(),
                                                            PersonnelFacts.readSnapshot(
                                                                    c.beforeJson()),
                                                            PersonnelFacts.readSnapshot(
                                                                    c.afterJson()),
                                                            c.occurredAt()))
                                    .toList();
                    var rows = permissions.memberHistory(p, member, sourcesAt.grant());
                    var page = rows.stream().limit(PAGE_SIZE).toList();
                    var ids = page.stream().map(PermissionMapper.Row::grantId).toList();
                    var relations =
                            ids.isEmpty()
                                    ? List.<PersonnelImpactMapper.GroupRow>of()
                                    : mapper.relations(p, member, ids);
                    var receipts =
                            ids.isEmpty() ? List.<RevocationReceipt>of() : mapper.receipts(p, ids);
                    var sourcePage =
                            page.stream()
                                    .map(
                                            g ->
                                                    new GrantSource(
                                                            PortalPermissions.view(g),
                                                            g.generation() != null
                                                                    && g.generation()
                                                                            == target.generation(),
                                                            relations.stream()
                                                                    .limit(MAX_BASIS)
                                                                    .filter(
                                                                            r ->
                                                                                    r.grantId()
                                                                                            .equals(
                                                                                                    g
                                                                                                            .grantId()))
                                                                    .map(
                                                                            r ->
                                                                                    new GroupRelation(
                                                                                            r.id(),
                                                                                            r
                                                                                                    .generation(),
                                                                                            r
                                                                                                    .current(),
                                                                                            r
                                                                                                    .periodsJson(),
                                                                                            r
                                                                                                    .groupActive()))
                                                                    .toList(),
                                                            receipts.stream()
                                                                    .filter(
                                                                            r ->
                                                                                    r.grantId()
                                                                                            .equals(
                                                                                                    g
                                                                                                            .grantId()))
                                                                    .findFirst()
                                                                    .orElse(null)))
                                    .toList();
                    var after = mapper.basis(p, member);
                    if (!before.equals(after) || !target.equals(mapper.target(p, member)))
                        throw error(VERSION_CONFLICT);
                    String nextChange =
                            changeRows.size() > PAGE_SIZE
                                    ? encode(
                                            basis,
                                            CHANGE_CURSOR,
                                            changeRows.get(PAGE_SIZE - 1).sourceId(),
                                            changeRows.get(PAGE_SIZE - 1).partitionSequence(),
                                            "")
                                    : null;
                    String nextSource =
                            rows.size() > PAGE_SIZE
                                    ? encode(
                                            basis,
                                            SOURCE_CURSOR,
                                            "",
                                            0,
                                            rows.get(PAGE_SIZE - 1).grantId())
                                    : null;
                    return new Report(
                            target,
                            dirs,
                            upstream,
                            changePage,
                            nextChange,
                            sourcePage,
                            nextSource,
                            basis,
                            before.total() <= MAX_BASIS && relations.size() <= MAX_BASIS,
                            Instant.now().toString());
                });
    }

    private static Position decode(String cursor, String basis, String kind) {
        if (cursor == null || cursor.isEmpty()) return new Position("", 0, "");
        if (cursor.length() > MAX_CURSOR_BYTES) throw error(INVALID_ARGUMENT);
        try {
            var v =
                    new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                            .split("\\|", -1);
            if (v.length != 5 || !v[0].equals(basis) || !v[1].equals(kind))
                throw error(VERSION_CONFLICT);
            long sequence = Long.parseLong(v[3]);
            if (kind.equals(CHANGE_CURSOR)) {
                BootstrapCommand.uuid(v[2]);
                if (sequence < 1 || !v[4].isEmpty()) throw error(INVALID_ARGUMENT);
            } else {
                BootstrapCommand.uuid(v[4]);
                if (!v[2].isEmpty() || sequence != 0) throw error(INVALID_ARGUMENT);
            }
            return new Position(v[2], sequence, v[4]);
        } catch (IllegalArgumentException invalid) {
            throw error(INVALID_ARGUMENT);
        }
    }

    private static String encode(
            String basis, String kind, String source, long sequence, String grant) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        (basis + "|" + kind + "|" + source + "|" + sequence + "|" + grant)
                                .getBytes(StandardCharsets.UTF_8));
    }

    private static Facts facts(String json) {
        try {
            return JSON.readValue(json, Facts.class);
        } catch (IOException corrupt) {
            throw error(DEPENDENCY_UNAVAILABLE);
        }
    }

    private static List<String> reasons(String json) {
        try {
            return List.of(JSON.readValue(json, String[].class));
        } catch (IOException corrupt) {
            throw error(DEPENDENCY_UNAVAILABLE);
        }
    }

    private static GovernanceException error(GovernanceException.Code code) {
        return new GovernanceException(code);
    }
}
