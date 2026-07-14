package com.lrj.authz.admin;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/** 授权操作审计(内存环形缓冲,最近 CAP 条)。v2 可换 JDBC 持久化。 */
@Component
public class AuditStore {

    public record AuditRecord(String at, String actor, String action, String detail) {
    }

    private static final int CAP = 500;
    private final ConcurrentLinkedDeque<AuditRecord> ring = new ConcurrentLinkedDeque<>();

    public void record(String actor, String action, String detail) {
        ring.addFirst(new AuditRecord(Instant.now().toString(), actor == null ? "-" : actor, action, detail));
        while (ring.size() > CAP) {
            ring.pollLast();
        }
    }

    public List<AuditRecord> recent(int limit) {
        return ring.stream().limit(Math.max(1, limit)).toList();
    }
}
