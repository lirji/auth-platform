package com.lrj.authz.admin.audit.persistence;

import com.lrj.authz.admin.audit.port.AuditStore;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/** 审计的内存实现（环形缓冲，最近 capacity 条，重启即失）。持久化需求用 {@link JdbcAuditStore}。 */
public class InMemoryAuditStore implements AuditStore {

    private final int capacity;
    private final ConcurrentLinkedDeque<AuditRecord> ring = new ConcurrentLinkedDeque<>();

    /** 显式绑定 InMemoryAuditStore 的协作对象与配置，后续实例操作必须沿用同一组依赖与生命周期。 */
    public InMemoryAuditStore(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /** 记录当前操作审计并沿用既有保留预算，不能在后续读取时临时编造历史。 */
    @Override
    public void record(String actor, String action, String detail) {
        ring.addFirst(
                new AuditRecord(
                        Instant.now().toString(), actor == null ? "-" : actor, action, detail));
        while (ring.size() > capacity) {
            ring.pollLast();
        }
    }

    /** 按原倒序与调用数量读取既有审计，内存与数据库实现保持同一结果契约。 */
    @Override
    public List<AuditRecord> recent(int limit) {
        return ring.stream().limit(Math.max(1, limit)).toList();
    }
}
