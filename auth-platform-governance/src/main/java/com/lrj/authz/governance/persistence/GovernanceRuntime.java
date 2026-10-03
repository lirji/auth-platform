package com.lrj.authz.governance.persistence;

import com.lrj.authz.governance.application.IdentityGovernance;
import com.lrj.authz.governance.application.LifecycleGovernance;
import com.lrj.authz.governance.application.InvitationGovernance;
import com.lrj.authz.governance.application.DirectoryGovernance;
import com.lrj.authz.governance.domain.InvitationModels.InvitationState;
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
    private final LifecycleGovernance lifecycle;
    private final InvitationGovernance invitations;
    private final DirectoryGovernance directory;
    private com.lrj.authz.governance.application.ApplicationCatalog catalog;
    private com.lrj.authz.governance.application.AccessManagement access;
    private com.lrj.authz.governance.application.AccessRequests requests;
    private ApprovalInboxMapper inboxMapper;
    private RequestMapper requestMapper;
    private PortalMapper portalMapper;
    private PermissionMapper permissionMapper;
    private CatalogImpactMapper catalogImpactMapper;
    private InvitationMapper invitationMapper;
    private AccessMapper accessMapper;
    private CatalogMapper catalogMapper;
    private ProjectionMapper projectionMapper;
    private FenceMapper fenceMapper;
    private ReliableProjectionMapper reliableMapper;
    private ScopeMapper scopeMapper;
    private ExecutionMapper executionMapper;
    private MigrationMapper migrationMapper;
    private TransactionTemplate transaction;

    private GovernanceRuntime(HikariDataSource dataSource, IdentityMapper mapper, IdentityGovernance identity, LifecycleGovernance lifecycle,
                              InvitationGovernance invitations, DirectoryGovernance directory) {
        this.dataSource = dataSource;
        this.mapper = mapper;
        this.identity = identity;
        this.lifecycle = lifecycle;
        this.invitations = invitations;
        this.directory = directory;
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
            config.getTypeHandlerRegistry().register(InvitationState.class, new IdentityCodeTypeHandler<>(InvitationState.class));
            config.getTypeHandlerRegistry().register(com.lrj.authz.protocol.DirectoryEvents.DirectoryAggregateType.class, new DirectoryEventTypeHandler());
            config.getTypeHandlerRegistry().register(com.lrj.authz.governance.domain.ProjectionModels.OperationState.class, new IdentityCodeTypeHandler<>(com.lrj.authz.governance.domain.ProjectionModels.OperationState.class));
            config.getTypeHandlerRegistry().register(com.lrj.authz.governance.domain.FenceModels.State.class, new IdentityCodeTypeHandler<>(com.lrj.authz.governance.domain.FenceModels.State.class));
            config.getTypeHandlerRegistry().register(com.lrj.authz.governance.domain.AccessModels.GrantState.class, new IdentityCodeTypeHandler<>(com.lrj.authz.governance.domain.AccessModels.GrantState.class));
            config.getTypeHandlerRegistry().register(com.lrj.authz.governance.domain.RequestModels.State.class, new IdentityCodeTypeHandler<>(com.lrj.authz.governance.domain.RequestModels.State.class));
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
            // 目录独占事务边界；失败回滚后再用独立事务留下冲突证据，不在失败事务中吞异常提交。
            TransactionTemplate directoryTransaction = new TransactionTemplate(transaction.getTransactionManager());
            directoryTransaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            directoryTransaction.setTimeout(5);
            TransactionTemplate conflictTransaction = new TransactionTemplate(transaction.getTransactionManager());
            conflictTransaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            conflictTransaction.setTimeout(5);
            GovernanceRuntime runtime = new GovernanceRuntime(dataSource, mapper, new IdentityGovernance(mapper, transaction), new LifecycleGovernance(mapper, transaction),
                    new InvitationGovernance(mapper, session.getMapper(InvitationMapper.class), transaction),
                    new DirectoryGovernance(session.getMapper(DirectoryMapper.class), mapper, session.getMapper(InvitationMapper.class),
                            directoryTransaction, conflictTransaction));
            runtime.catalog = new com.lrj.authz.governance.application.ApplicationCatalog(session.getMapper(CatalogMapper.class), mapper, runtime.identity(), transaction, session.getMapper(SafetyMapper.class), session.getMapper(CatalogReleaseMapper.class), session.getMapper(CatalogGuardMapper.class));
            runtime.access = new com.lrj.authz.governance.application.AccessManagement(session.getMapper(AccessMapper.class), session.getMapper(CatalogMapper.class), mapper, runtime.identity(), transaction, session.getMapper(FenceMapper.class), session.getMapper(SafetyMapper.class));
            runtime.portalMapper=session.getMapper(PortalMapper.class);
            runtime.permissionMapper=session.getMapper(PermissionMapper.class);
            runtime.catalogImpactMapper=session.getMapper(CatalogImpactMapper.class);
            runtime.invitationMapper=session.getMapper(InvitationMapper.class);
            runtime.accessMapper=session.getMapper(AccessMapper.class); runtime.catalogMapper=session.getMapper(CatalogMapper.class);
            runtime.projectionMapper=session.getMapper(ProjectionMapper.class); runtime.transaction=transaction;
            runtime.fenceMapper=session.getMapper(FenceMapper.class);runtime.reliableMapper=session.getMapper(ReliableProjectionMapper.class);runtime.scopeMapper=session.getMapper(ScopeMapper.class);
            runtime.executionMapper=session.getMapper(ExecutionMapper.class);
            runtime.migrationMapper=session.getMapper(MigrationMapper.class);
            runtime.inboxMapper=session.getMapper(ApprovalInboxMapper.class);
            runtime.requestMapper=session.getMapper(RequestMapper.class);
            runtime.requests=new com.lrj.authz.governance.application.AccessRequests(session.getMapper(RequestMapper.class),runtime.accessMapper,runtime.catalogMapper,mapper,runtime.identity,transaction);
            return runtime;
        } catch (Exception failure) {
            dataSource.close();
            throw new IllegalStateException("治理库装配失败，拒绝启用新路径", failure);
        }
    }

    /** 独立诊断配置只约束管理查询，本人解释仍由当前成员关系过滤。 */
    public com.lrj.authz.governance.application.PortalPermissions portalPermissions(java.util.List<com.lrj.authz.governance.application.PortalDiagnosticAuthority> authorities) {
        return new com.lrj.authz.governance.application.PortalPermissions(identity,access,permissionMapper,authorities,transaction,
                new com.lrj.authz.governance.application.CatalogImpact(catalogImpactMapper,portalMapper));
    }

    /** 门户邀请授权来自宿主受控配置，默认空集合不会授权任何管理者。 */
    public com.lrj.authz.governance.application.PortalInvitations portalInvitations(java.util.List<com.lrj.authz.governance.application.PortalInvitationAuthority> authorities, String issuer) {
        return new com.lrj.authz.governance.application.PortalInvitations(identity,access,accessMapper,mapper,portalMapper,invitationMapper,invitations,transaction,authorities,issuer);
    }

    /** 唯一应用入口，读取与写入共享同一专用治理数据源。 */
    public IdentityGovernance identity() { return identity; }

    /** 受控操作适配器使用停用用例，不能由本人只读 HTTP 接口直接暴露。 */
    public LifecycleGovernance lifecycle() { return lifecycle; }

    /** 邀请创建/撤销使用受控身份，接受前必须完成独立 Token 验证。 */
    public InvitationGovernance invitations() { return invitations; }

    /** 仅受控目录适配器可登记来源和提交事件，不向普通用户 HTTP 暴露。 */
    public DirectoryGovernance directory() { return directory; }

    /** 目录发布不接管旧工作区或业务权限。 */
    public com.lrj.authz.governance.application.ApplicationCatalog catalog() { return catalog; }

    /** 应用管理不能绕过委派和当前成员状态。 */
    public com.lrj.authz.governance.application.AccessManagement access() { return access; }

    /** 图投影只使用显式提供的独立引擎。 */
    public com.lrj.authz.governance.application.GrantProjection projector(com.lrj.authz.protocol.AuthzEngine graph) {
        return new com.lrj.authz.governance.application.GrantProjection(dataSource,accessMapper,projectionMapper,transaction,graph);
    }
    /** 公开中央鉴权组合SQL事实和同Grant图资格。 */
    public com.lrj.authz.governance.application.AccessAuthorization authorization(com.lrj.authz.protocol.AuthzEngine graph) {
        return new com.lrj.authz.governance.application.AccessAuthorization(accessMapper,projectionMapper,catalogMapper,graph);
    }

    /** 严格读路径每次从主库获取两份独立快照。 */
    public com.lrj.authz.governance.application.ReadFence readFence(){
        return new com.lrj.authz.governance.application.ReadFence(fenceMapper,transaction.getTransactionManager());
    }

    /** 可靠执行器只使用受控CAS端口，不能回退到旧无前置条件写入。 */
    public com.lrj.authz.governance.application.ReliableProjection reliableProjector(com.lrj.authz.protocol.ProjectionGraph graph){
        return new com.lrj.authz.governance.application.ReliableProjection(reliableMapper,fenceMapper,transaction.getTransactionManager(),graph);
    }

    /** 严格授权使用持久双水位和同Grant范围，没有跨请求允许缓存。 */
    public com.lrj.authz.governance.application.ReliableAuthorization reliableAuthorization(com.lrj.authz.protocol.StrictGraphReader graph){
        return new com.lrj.authz.governance.application.ReliableAuthorization(readFence(),scopeMapper,catalogMapper,accessMapper,graph);
    }

    /** 业务本人导航独立使用严格范围与整次查询栅栏，不签发执行引用。 */
    public com.lrj.authz.governance.application.BusinessNavigation navigation(com.lrj.authz.protocol.StrictGraphReader graph) {
        return new com.lrj.authz.governance.application.BusinessNavigation(catalogMapper,readFence(),reliableAuthorization(graph));
    }

    /** 后台引用复核共享实时栅栏，永远不使用已缓存的ALLOW。 */
    public com.lrj.authz.governance.application.ExecutionAuthorization executions(com.lrj.authz.protocol.StrictGraphReader graph) {
        return new com.lrj.authz.governance.application.ExecutionAuthorization(executionMapper,reliableAuthorization(graph),transaction);
    }

    /** 导入与管理授权共享事务和当前委派，不提供直接Grant写表捷径。 */
    public com.lrj.authz.governance.application.MigrationImport migrationImport() {
        return new com.lrj.authz.governance.application.MigrationImport(migrationMapper,access,accessMapper,transaction,mapper);
    }

    /** 独立通知worker不会更改已提交的申请或授权状态。 */
    public com.lrj.authz.governance.application.RequestNotifications requestNotifications() { return new com.lrj.authz.governance.application.RequestNotifications(requestMapper,transaction); }
    /** 自助申请仍强制当前成员和显式申请策略。 */
    public com.lrj.authz.governance.application.AccessRequests requests() { return requests; }

    /** OA启动投递不进入日常授权读取链路。 */
    public com.lrj.authz.governance.application.ApprovalStartDelivery approvalStarts(com.lrj.authz.governance.application.ApprovalGateway gateway) {
        return new com.lrj.authz.governance.application.ApprovalStartDelivery(requestMapper,transaction,gateway);
    }

    /** 回调接收只持久化消息；日常判权从不依赖OA。 */
    public com.lrj.authz.governance.application.ApprovalInbox approvalInbox() {
        return new com.lrj.authz.governance.application.ApprovalInbox(inboxMapper,requestMapper,transaction);
    }

    /** 审批消费与申请用例复用权威事务与分区串行锁。 */
    public com.lrj.authz.governance.application.ApprovalDecisionConsumer approvalDecisions() {
        return new com.lrj.authz.governance.application.ApprovalDecisionConsumer(requests,accessMapper,inboxMapper,transaction);
    }

    IdentityMapper mapper() { return mapper; }

    /** 本人菜单与业务判权共用SQL/图资格，不能靠前端角色集合推断。 */
    public com.lrj.authz.governance.application.AccessPresentation presentation(com.lrj.authz.protocol.AuthzEngine graph) {
        return new com.lrj.authz.governance.application.AccessPresentation(identity, catalogMapper, authorization(graph));
    }

    /** 严格分区菜单按范围存在性显示，不能调用P2全范围检查或回退旧权限。 */
    public com.lrj.authz.governance.application.AccessPresentation presentation(com.lrj.authz.protocol.AuthzEngine legacy,com.lrj.authz.protocol.StrictGraphReader strict){
        var old=authorization(legacy);var current=reliableAuthorization(strict);
        return new com.lrj.authz.governance.application.AccessPresentation(identity,catalogMapper,(context,capability,resource)->{
            var p=new com.lrj.authz.governance.domain.AccessModels.Partition(context.tenantId(),context.applicationId(),context.environment());
            return accessMapper.strict(p)?!current.evaluate(context,capability,resource).alternatives().isEmpty():old.allowed(context,capability,resource);
        });
    }

    /** 管理展示复用与写入相同的身份/委派边界。 */
    public com.lrj.authz.governance.application.PortalManagement portalManagement() {
        return new com.lrj.authz.governance.application.PortalManagement(access, identity, accessMapper, catalogMapper, portalMapper);
    }

    /** 门户复用本进程现有身份与展示装配，组织/应用目录不依赖OA。 */
    public com.lrj.authz.governance.application.PortalDirectory portal(com.lrj.authz.governance.application.AccessPresentation presentation) {
        return new com.lrj.authz.governance.application.PortalDirectory(identity, mapper, portalMapper, presentation);
    }

    /** 所属进程停止或 CLI 退出时释放连接池，不清理数据库。 */
    @Override public void close() { dataSource.close(); }
}
