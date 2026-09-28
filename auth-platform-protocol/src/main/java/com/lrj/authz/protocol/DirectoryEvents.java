package com.lrj.authz.protocol;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** OA 与治理消费者共享的最小目录事实及摘要协议，不依赖数据库、JSON 框架或 OA 实体。 */
public final class DirectoryEvents {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_RELATIONS = 100;
    private DirectoryEvents() {}

    /** 事件类型采用显式稳定代码，快照控制记录不伪装成员工或组织。 */
    public enum DirectoryAggregateType {
        EMPLOYEE("EMPLOYEE"), ORG("ORG"), SNAPSHOT_BEGIN("SNAPSHOT_BEGIN"), SNAPSHOT_END("SNAPSHOT_END");
        private final String code;
        DirectoryAggregateType(String code) { this.code = code; }
        /** 持久化和协议只使用稳定代码，不使用 ordinal。 */
        public String code() { return code; }
        /** 未知来源类型必须停止导入，不能降级为任意已知类型。 */
        public static DirectoryAggregateType fromCode(String code) {
            for (DirectoryAggregateType value : values()) { if (value.code.equals(code)) { return value; } }
            throw invalid();
        }
    }

    /** 只保留直接任职与有效期；不把 OA 的计算继承路径当作授权关系。 */
    public record Assignment(String id, String orgId, String type, boolean leader, String validFrom, String validTo) {
        public Assignment {
            sourceId(id); sourceId(orgId); choice(type, Set.of("PRIMARY", "CONCURRENT", "DOTTED")); period(validFrom, validTo);
        }
    }
    /** 汇报线只投影源事实，不表示经理拥有下属的任何业务权限。 */
    public record ReportingLine(String id, String managerEmployeeId, String type, String validFrom, String validTo) {
        public ReportingLine {
            sourceId(id); sourceId(managerEmployeeId); choice(type, Set.of("SOLID", "DOTTED")); period(validFrom, validTo);
        }
    }
    /** 不包含邮箱、姓名、证件等非必要字段；未绑定登录标识使用显式 null。 */
    public record Employee(String employeeId, String userId, String status, List<Assignment> assignments, List<ReportingLine> reportingLines) {
        public Employee {
            sourceId(employeeId); if (userId != null) { bounded(userId, 500); }
            choice(status, Set.of("PROBATION", "ACTIVE", "LEAVING", "LEFT"));
            if (assignments == null || reportingLines == null || assignments.size() > MAX_RELATIONS || reportingLines.size() > MAX_RELATIONS
                    || assignments.stream().anyMatch(java.util.Objects::isNull) || reportingLines.stream().anyMatch(java.util.Objects::isNull)) { throw invalid(); }
            assignments = assignments.stream().sorted(Comparator.comparingLong(a -> Long.parseLong(a.id()))).toList();
            reportingLines = reportingLines.stream().sorted(Comparator.comparingLong(r -> Long.parseLong(r.id()))).toList();
            unique(assignments.stream().map(Assignment::id).toList()); unique(reportingLines.stream().map(ReportingLine::id).toList());
        }
    }
    /** 父关系保持源 ID；完整树及环检查属于消费事务，不能在 DTO 中猜测。 */
    public record Organization(String orgId, String parentId, String status) {
        public Organization {
            sourceId(orgId); if (parentId != null) { sourceId(parentId); }
            if (orgId.equals(parentId)) { throw invalid(); }
            choice(status, Set.of("ACTIVE", "FROZEN", "DISSOLVED"));
        }
    }
    /** BEGIN/END 声明包含自身的序号边界，摘要只覆盖中间业务事件，避免递归摘要。 */
    public record Snapshot(long startSequence, long endSequence, long expectedCount, String contentHash) {
        public Snapshot {
            if (startSequence < 1 || endSequence <= startSequence || expectedCount != endSequence - startSequence - 1) { throw invalid(); }
            hash(contentHash);
        }
    }
    /** 三种载荷恰有一种；事件类型还会校验对应关系，避免类型混淆。 */
    public record Payload(Employee employee, Organization organization, Snapshot snapshot) {
        public Payload {
            if ((employee == null ? 0 : 1) + (organization == null ? 0 : 1) + (snapshot == null ? 0 : 1) != 1) { throw invalid(); }
        }
    }
    /** 完整不可变事件；摘要不匹配直接拒绝，不能由消费者替来历不明的内容补签摘要。 */
    public record Event(int schemaVersion, String eventId, String source, String environment, String sourceTenantRef,
                        long partitionSequence, DirectoryAggregateType aggregateType, String aggregateId, long aggregateVersion,
                        String occurredAt, String snapshotId, Payload payload, String payloadHash) {
        public Event {
            if (schemaVersion != SCHEMA_VERSION || partitionSequence < 1 || aggregateVersion < 1 || aggregateType == null || payload == null) { throw invalid(); }
            uuid(eventId); bounded(source, 100); bounded(environment, 40); sourceId(sourceTenantRef);
            if (!source.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*") || !environment.matches("[a-z0-9][a-z0-9-]*")) { throw invalid(); }
            if (snapshotId != null) { uuid(snapshotId); }
            bounded(occurredAt, 40); Instant occurred = Instant.parse(occurredAt);
            if (occurred.getNano() % 1000 != 0 || !occurred.toString().equals(occurredAt)) { throw invalid(); }
            switch (aggregateType) {
                case EMPLOYEE -> { if (payload.employee() == null || !payload.employee().employeeId().equals(aggregateId)) { throw invalid(); } }
                case ORG -> { if (payload.organization() == null || !payload.organization().orgId().equals(aggregateId)) { throw invalid(); } }
                case SNAPSHOT_BEGIN, SNAPSHOT_END -> {
                    if (payload.snapshot() == null || snapshotId == null || !snapshotId.equals(aggregateId) || aggregateVersion != 1) { throw invalid(); }
                    long required = aggregateType == DirectoryAggregateType.SNAPSHOT_BEGIN ? payload.snapshot().startSequence() : payload.snapshot().endSequence();
                    if (partitionSequence != required) { throw invalid(); }
                }
            }
            hash(payloadHash);
            if (!payloadHash.equals(DirectoryEvents.payloadHash(aggregateType, payload))) { throw invalid(); }
        }
    }

    /** 规范业务摘要排除传输元数据，用于同聚合版本异内容检测。 */
    public static String payloadHash(DirectoryAggregateType type, Payload payload) {
        if (type == null || payload == null) { throw invalid(); }
        return digest(out -> {
            out.text(type.code());
            if (type == DirectoryAggregateType.EMPLOYEE && payload.employee() != null) {
                Employee e = payload.employee(); out.text(e.employeeId()); out.text(e.userId()); out.text(e.status());
                out.count(e.assignments().size());
                for (Assignment a : e.assignments()) {
                    out.text(a.id()); out.text(a.orgId()); out.text(a.type()); out.flag(a.leader()); out.text(a.validFrom()); out.text(a.validTo());
                }
                out.count(e.reportingLines().size());
                for (ReportingLine r : e.reportingLines()) {
                    out.text(r.id()); out.text(r.managerEmployeeId()); out.text(r.type()); out.text(r.validFrom()); out.text(r.validTo());
                }
            } else if (type == DirectoryAggregateType.ORG && payload.organization() != null) {
                Organization o = payload.organization(); out.text(o.orgId()); out.text(o.parentId()); out.text(o.status());
            } else if ((type == DirectoryAggregateType.SNAPSHOT_BEGIN || type == DirectoryAggregateType.SNAPSHOT_END) && payload.snapshot() != null) {
                Snapshot s = payload.snapshot(); out.number(s.startSequence()); out.number(s.endSequence()); out.number(s.expectedCount()); out.text(s.contentHash());
            } else { throw invalid(); }
        });
    }

    /** 完整事件摘要同时绑定来源/租户/序号/时间，防止同事件 ID 携带不同元数据重放。 */
    public static String eventFingerprint(Event event) {
        return digest(out -> {
            out.number(event.schemaVersion()); out.text(event.eventId()); out.text(event.source()); out.text(event.environment());
            out.text(event.sourceTenantRef()); out.number(event.partitionSequence()); out.text(event.aggregateType().code());
            out.text(event.aggregateId()); out.number(event.aggregateVersion()); out.text(event.occurredAt());
            out.text(event.snapshotId()); out.text(event.payloadHash());
        });
    }

    private static String digest(Encoding encode) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var data = new DataOutputStream(bytes)) { encode.write(new Encoder(data)); }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException impossible) { throw new IllegalStateException("目录规范摘要不可用", impossible); }
    }
    private interface Encoding { void write(Encoder out) throws IOException; }
    private record Encoder(DataOutputStream data) {
        void text(String value) throws IOException {
            if (value == null) { data.writeInt(-1); return; }
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8); data.writeInt(bytes.length); data.write(bytes);
        }
        void number(long value) throws IOException { data.writeLong(value); }
        void count(int value) throws IOException { data.writeInt(value); }
        void flag(boolean value) throws IOException { data.writeBoolean(value); }
    }
    private static void period(String from, String to) {
        if (from == null) { throw invalid(); }
        LocalDate start = LocalDate.parse(from);
        if (!start.toString().equals(from) || (to != null && (!LocalDate.parse(to).toString().equals(to) || LocalDate.parse(to).isBefore(start)))) { throw invalid(); }
    }
    private static void unique(List<String> ids) { if (ids.stream().distinct().count() != ids.size()) { throw invalid(); } }
    private static void sourceId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}") || Long.parseLong(value) < 1) { throw invalid(); }
    }
    private static void uuid(String value) {
        if (value == null || !UUID.fromString(value).toString().equals(value)) { throw invalid(); }
    }
    private static void bounded(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl) || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) { throw invalid(); }
    }
    private static void hash(String value) { if (value == null || !value.matches("[a-f0-9]{64}")) { throw invalid(); } }
    private static void choice(String value, Set<String> allowed) { if (value == null || !allowed.contains(value)) { throw invalid(); } }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("目录事件不符合契约"); }
}
