package com.lrj.authz.admin;

import java.util.List;

/**
 * 授权操作审计端口。两个实现按 {@code authz.audit.persistence-enabled} 二选一装配（见 {@link AuditConfig}）：
 * {@link InMemoryAuditStore}（默认，内存环形缓冲，重启即失）与 {@link JdbcAuditStore}（Postgres 持久化）。
 */
public interface AuditStore {

    /** 一条审计记录。{@code at} 为 ISO-8601 Instant 字符串（两实现返回形状一致，前端/API 不感知实现）。 */
    record AuditRecord(String at, String actor, String action, String detail) {
    }

    /** 记录一次授权操作（actor 为空归一为 "-"）。 */
    void record(String actor, String action, String detail);

    /** 最近 limit 条（新→旧；limit<1 归一为 1）。 */
    List<AuditRecord> recent(int limit);
}
