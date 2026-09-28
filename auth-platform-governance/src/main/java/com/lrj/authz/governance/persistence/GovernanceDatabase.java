package com.lrj.authz.governance.persistence;

import com.lrj.authz.governance.application.BootstrapCommand;
import com.lrj.authz.governance.application.GovernanceException;
import java.util.Properties;

/** 专用治理连接配置，缺失即拒绝，不退回共享认证或图数据库。 */
public record GovernanceDatabase(String jdbcUrl, String username, String password, int maximumPoolSize) {
    /** URL 不携带密码或查询参数，驱动超时由专用池统一配置。 */
    public GovernanceDatabase {
        if (jdbcUrl == null || !jdbcUrl.matches("jdbc:postgresql://[a-zA-Z0-9.:-]+/[a-zA-Z0-9_]+")
                || password == null || password.isBlank() || maximumPoolSize < 1 || maximumPoolSize > 64) {
            throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
        }
        BootstrapCommand.bounded(username, 100);
    }

    /** 读取显式配置，不使用旧审计池或 Spring 默认 DataSource 猜测目标。 */
    public static GovernanceDatabase from(Properties properties) {
        try {
            return new GovernanceDatabase(properties.getProperty("jdbc.url"), properties.getProperty("jdbc.username"),
                    properties.getProperty("jdbc.password"), Integer.parseInt(properties.getProperty("jdbc.maximum-pool-size", "8")));
        } catch (NumberFormatException e) {
            throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
        }
    }

    /** 防止 record 默认 toString 将凭据打印到日志。 */
    @Override public String toString() { return "GovernanceDatabase[credentials=redacted]"; }
}
