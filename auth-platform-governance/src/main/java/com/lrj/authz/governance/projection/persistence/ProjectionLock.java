package com.lrj.authz.governance.projection.persistence;

import java.sql.Connection;

import javax.sql.DataSource;

/** P2数据库会话互斥仅防误启动第二执行器，不代替P3远端写栅栏。 */
public final class ProjectionLock implements AutoCloseable {
    private static final long LOCK_ID = 7421902001L;
    private final Connection connection;

    private ProjectionLock(Connection connection) {
        this.connection = connection;
    }

    /** 独立连接持有会话锁，网络图写不占用业务SQL事务。 */
    public static ProjectionLock acquire(DataSource source) {
        Connection connection = null;
        try {
            connection = source.getConnection();
            try (var query = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                query.setLong(1, LOCK_ID);
                try (var rows = query.executeQuery()) {
                    if (!rows.next() || !rows.getBoolean(1))
                        throw new IllegalStateException("投影执行器已运行");
                }
            }
            return new ProjectionLock(connection);
        } catch (Exception failure) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw new IllegalStateException("无法取得投影单执行者锁", failure);
        }
    }

    /** 显式释放会话锁；失败必须中止物理连接并报告，不能把持锁会话静默归还池。 */
    @Override
    public void close() {
        Exception failure = null;
        try (var query = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            query.setLong(1, LOCK_ID);
            try (var rows = query.executeQuery()) {
                if (!rows.next() || !rows.getBoolean(1))
                    throw new IllegalStateException("投影锁不属于当前会话");
            }
        } catch (Exception unlock) {
            failure = unlock;
            try {
                connection.abort(Runnable::run);
            } catch (Exception abort) {
                failure.addSuppressed(abort);
            }
        } finally {
            try {
                connection.close();
            } catch (Exception cleanup) {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
        }
        if (failure != null) throw new IllegalStateException("投影会话清理失败", failure);
    }
}
