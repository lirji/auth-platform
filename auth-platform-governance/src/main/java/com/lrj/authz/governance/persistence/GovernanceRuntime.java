package com.lrj.authz.governance.persistence;

import com.lrj.authz.governance.application.IdentityGovernance;
import com.lrj.authz.governance.domain.IdentityModels.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.LocalCacheScope;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 既有进程内专用治理 Runtime，生命周期由调用者持有，默认不会自动启动或迁移。 */
public final class GovernanceRuntime implements AutoCloseable {
    private final HikariDataSource dataSource;
    private final IdentityMapper mapper;
    private final IdentityGovernance identity;

    private GovernanceRuntime(HikariDataSource dataSource, IdentityMapper mapper, IdentityGovernance identity) {
        this.dataSource = dataSource;
        this.mapper = mapper;
        this.identity = identity;
    }

    /** 显式 migration owner 才能迁移；读取实例必须通过已存在序列的 validate。 */
    public static GovernanceRuntime open(GovernanceDatabase database, boolean migrate) {
        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl(database.jdbcUrl());
        pool.setUsername(database.username());
        pool.setPassword(database.password());
        pool.setMaximumPoolSize(database.maximumPoolSize());
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(3_000);
        pool.setValidationTimeout(1_000);
        pool.setPoolName("auth-governance");
        pool.addDataSourceProperty("connectTimeout", "3");
        pool.addDataSourceProperty("socketTimeout", "5");
        pool.setConnectionInitSql("SET statement_timeout = '4000ms'; SET lock_timeout = '2000ms'");
        HikariDataSource dataSource = new HikariDataSource(pool);
        try {
            Flyway flyway = Flyway.configure().dataSource(dataSource).schemas("auth_governance")
                    .locations("classpath:db/governance/migration").baselineOnMigrate(false)
                    .cleanDisabled(true).validateOnMigrate(true).load();
            if (migrate) { flyway.migrate(); }
            else {
                // 非迁移进程不能在空库上把 validate 空结果当作成功。
                if (flyway.info().current() == null) { throw new IllegalStateException("治理迁移尚未初始化"); }
                flyway.validate();
            }
            org.apache.ibatis.session.Configuration config = new org.apache.ibatis.session.Configuration();
            config.getTypeHandlerRegistry().register(PrincipalKind.class, new IdentityCodeTypeHandler<>(PrincipalKind.class));
            config.getTypeHandlerRegistry().register(GlobalStatus.class, new IdentityCodeTypeHandler<>(GlobalStatus.class));
            config.getTypeHandlerRegistry().register(MemberKind.class, new IdentityCodeTypeHandler<>(MemberKind.class));
            config.getTypeHandlerRegistry().register(MemberStatus.class, new IdentityCodeTypeHandler<>(MemberStatus.class));
            config.setMapUnderscoreToCamelCase(true);
            config.setArgNameBasedConstructorAutoMapping(true);
            config.setCacheEnabled(false);
            config.setLocalCacheScope(LocalCacheScope.STATEMENT);
            config.setDefaultStatementTimeout(4);
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(config);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mappers/governance/*.xml"));
            SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
            IdentityMapper mapper = session.getMapper(IdentityMapper.class);
            TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            transaction.setTimeout(5);
            return new GovernanceRuntime(dataSource, mapper, new IdentityGovernance(mapper, transaction));
        } catch (Exception failure) {
            dataSource.close();
            throw new IllegalStateException("治理库装配失败，拒绝启用新路径", failure);
        }
    }

    /** 唯一应用入口，读取与写入共享同一专用治理数据源。 */
    public IdentityGovernance identity() { return identity; }

    IdentityMapper mapper() { return mapper; }

    /** 所属进程停止或 CLI 退出时释放连接池，不清理数据库。 */
    @Override public void close() { dataSource.close(); }
}
