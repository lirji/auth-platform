package com.lrj.authz.admin;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/** 审计的内存实现（环形缓冲，最近 capacity 条，重启即失）。持久化需求用 {@link JdbcAuditStore}。 */
public class InMemoryAuditStore implements AuditStore {

    private final int capacity;
    private final ConcurrentLinkedDeque<AuditRecord> ring = new ConcurrentLinkedDeque<>();

    public InMemoryAuditStore(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    @Override
    public void record(String actor, String action, String detail) {
        ring.addFirst(new AuditRecord(Instant.now().toString(), actor == null ? "-" : actor, action, detail));
        while (ring.size() > capacity) {
            ring.pollLast();
        }
    }

    @Override
    public List<AuditRecord> recent(int limit) {
        return ring.stream().limit(Math.max(1, limit)).toList();
    }
}
