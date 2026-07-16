package com.lrj.authz.admin;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** JdbcAuditStore 单测：幂等建表、写读回、actor 归一、retention 裁剪。H2 PostgreSQL 兼容模式。 */
class JdbcAuditStoreTest {

    private static JdbcTemplate freshDb() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        return new JdbcTemplate(ds);
    }

    @Test
    void recordAndRecent_roundTrip_newestFirst() {
        JdbcAuditStore store = new JdbcAuditStore(freshDb(), 0);
        store.record("alice", "grant", "document:t_1#viewer@user:u1");
        store.record("bob", "revoke", "document:t_1#viewer@user:u1");

        List<AuditStore.AuditRecord> recent = store.recent(10);

        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).actor()).isEqualTo("bob");
        assertThat(recent.get(0).action()).isEqualTo("revoke");
        assertThat(recent.get(1).actor()).isEqualTo("alice");
        assertThat(recent.get(0).at()).isNotBlank(); // ISO Instant 字符串,与内存实现形状一致
        assertThat(java.time.Instant.parse(recent.get(0).at())).isNotNull();
    }

    @Test
    void nullActor_normalizedToDash_andLimitFloorsAtOne() {
        JdbcAuditStore store = new JdbcAuditStore(freshDb(), 0);
        store.record(null, "grant", "x");
        store.record("carol", "grant", "y");

        List<AuditStore.AuditRecord> one = store.recent(0);

        assertThat(one).hasSize(1);
        assertThat(store.recent(10)).extracting(AuditStore.AuditRecord::actor).contains("-", "carol");
    }

    @Test
    void retention_prunesOldestBeyondMaxRows() {
        JdbcAuditStore store = new JdbcAuditStore(freshDb(), 3);
        for (int i = 1; i <= 5; i++) {
            store.record("u", "grant", "detail-" + i);
        }

        List<AuditStore.AuditRecord> recent = store.recent(10);

        assertThat(recent).hasSize(3);
        assertThat(recent).extracting(AuditStore.AuditRecord::detail)
                .containsExactly("detail-5", "detail-4", "detail-3");
    }

    @Test
    void createTable_isIdempotent_acrossRestart() {
        JdbcTemplate jdbc = freshDb();
        new JdbcAuditStore(jdbc, 0).record("a", "grant", "before-restart");
        JdbcAuditStore reopened = new JdbcAuditStore(jdbc, 0); // 同库再次构造 = 模拟重启

        assertThat(reopened.recent(10)).extracting(AuditStore.AuditRecord::detail).contains("before-restart");
    }
}
