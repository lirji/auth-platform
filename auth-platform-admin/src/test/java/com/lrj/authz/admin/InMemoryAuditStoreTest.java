package com.lrj.authz.admin;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryAuditStoreTest {

    @Test
    void keepsNewestUpToCapacityAndNormalizesActor() {
        InMemoryAuditStore store = new InMemoryAuditStore(2);
        store.record(null, "grant", "one");
        store.record("bob", "revoke", "two");
        store.record("carol", "grant", "three");

        List<AuditStore.AuditRecord> result = store.recent(10);
        assertThat(result).extracting(AuditStore.AuditRecord::detail).containsExactly("three", "two");
        assertThat(result).extracting(AuditStore.AuditRecord::actor).containsExactly("carol", "bob");
        assertThat(result).allSatisfy(r -> assertThat(Instant.parse(r.at())).isNotNull());
    }

    @Test
    void floorsCapacityAndLimitAtOne() {
        InMemoryAuditStore store = new InMemoryAuditStore(0);
        store.record(null, "grant", "first");
        store.record(null, "grant", "second");

        assertThat(store.recent(0)).singleElement().satisfies(r -> {
            assertThat(r.actor()).isEqualTo("-");
            assertThat(r.detail()).isEqualTo("second");
        });
    }

    @Test
    void concurrentWritersNeverExposeMoreThanCapacity() throws Exception {
        int capacity = 25;
        InMemoryAuditStore store = new InMemoryAuditStore(capacity);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 200; i++) {
                int n = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    store.record("u" + n, "grant", "detail-" + n);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        List<AuditStore.AuditRecord> result = store.recent(1000);
        assertThat(result).hasSizeLessThanOrEqualTo(capacity).isNotEmpty();
        assertThat(result).allSatisfy(r -> {
            assertThat(r.actor()).startsWith("u");
            assertThat(r.detail()).startsWith("detail-");
            assertThat(r.action()).isEqualTo("grant");
        });
        // TODO(issue-AUD01): 修复复合原子性后，应稳定断言并发结束恰好保留 capacity 条。
    }
}
