package com.lrj.authz.governance.support;

import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.AccessModels.*;
import com.lrj.authz.governance.domain.CatalogModels.*;
import com.lrj.authz.governance.persistence.*;
import com.lrj.authz.protocol.RoleMigrationTaskDtos.*;
import com.lrj.authz.protocol.ScopeDtos.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/** 每场景创建独立UUID分区；SQL ACTIVE夹具和真实图投影由测试明确区分。 */
public final class RoleMigrationFixture implements AutoCloseable {
    public final GovernanceRuntime runtime;
    public final GovernanceDatabase database;
    public final JdbcTemplate jdbc;
    public record F(BootstrapCommand owner,BootstrapCommand member,Partition partition,RoleVersion oldRole,RoleVersion newRole,List<String> capabilities){
        /** 只有当前真实数据库绑定产生管理身份。 */
        public VerifiedLogin login(){return new VerifiedLogin(owner.issuer(),owner.subject());}
    }
    /** 只允许既有隔离测试数据库，不使用原业务配置。 */
    public RoleMigrationFixture(){
        Properties p=new Properties();String file=System.getenv("GOVERNANCE_TEST_CONFIG");
        if(file!=null)p=GovernanceConfigurationFile.read(file);
        else{p.setProperty("jdbc.url",Objects.requireNonNull(System.getenv("GOVERNANCE_TEST_DB_URL")));p.setProperty("jdbc.username",Objects.requireNonNull(System.getenv("GOVERNANCE_TEST_DB_USER")));p.setProperty("jdbc.password",Objects.requireNonNull(System.getenv("GOVERNANCE_TEST_DB_PASSWORD")));}
        database=GovernanceDatabase.from(p);assertThat(database.jdbcUrl()).matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/auth_gov_p1_test_[a-z0-9_]+");
        runtime=GovernanceRuntime.open(database,true);jdbc=new JdbcTemplate(new DriverManagerDataSource(database.jdbcUrl(),database.username(),database.password()));
    }
    /** 原目标成员与管理员不相同，覆盖自授予限制。 */
    public F fixture(){
        String tenant=id(),code="mg13-"+id(),app="mg13-"+id();var owner=person(tenant,code);var member=person(tenant,code);
        var login=new VerifiedLogin(owner.issuer(),owner.subject());var caps=List.of(app+".read",app+".write");
        runtime.catalog().register(app,owner.principalId(),"https://mg13.example","mg13",id());
        runtime.catalog().publish(login,new Manifest("1",app,1,caps.stream().map(c->new Capability(c,"store",Risk.NORMAL)).toList(),List.of()),id());
        var p=new Partition(tenant,app,"test");runtime.access().bootstrap(p,new Delegation(owner.membershipId(),1,AccessValues.json(caps),3600),"mg13",id());
        var old=runtime.access().createRole(login,p,id(),"reader",1,caps);var next=runtime.access().createRole(login,p,id(),"reader",2,List.of(caps.getFirst()));runtime.access().enableStrict(login,p,id());
        return new F(owner,member,p,old,next,caps);
    }
    /** 正式授权入口创建范围和可靠投影意图，实际激活由调用方选择真实图或SQL夹具。 */
    public Grant grant(F f,boolean scoped){
        Instant from=Instant.now().minusSeconds(2),to=Instant.now().plusSeconds(600);
        return scoped?runtime.access().grantScoped(f.login(),f.partition,id(),f.member.membershipId(),1,f.oldRole.id(),new Rule(1,"store",List.of(new Clause(Kind.SPECIFIED_STORES,List.of("S1"),false))),id(),from,to)
            :runtime.access().grant(f.login(),f.partition,id(),f.member.membershipId(),1,f.oldRole.id(),"TENANT_ALL",id(),from,to);
    }
    /** 仅PG状态测试使用，不作为真实图ALLOW证据。 */
    public Grant sqlActive(F f,boolean scoped){var g=grant(f,scoped);jdbc.update("UPDATE auth_governance.access_grant SET state='ACTIVE' WHERE id=?",g.id());return g;}
    /** 创建请求严格固定当前范围摘要和原截止。 */
    public Create create(F f,Grant... grants){
        var selected=Arrays.stream(grants).map(g->new GrantSelection(g.id(),g.version(),g.validTo().toString(),jdbc.query("SELECT content_hash FROM auth_governance.grant_scope WHERE grant_id=?",(rs,n)->rs.getString(1),g.id()).stream().findFirst().orElse(null))).toList();
        return new Create(f.partition.tenantId(),f.partition.applicationId(),f.partition.environment(),id(),f.oldRole.id(),f.newRole.id(),selected);
    }
    /** 使用读取到的原子项版本推进一次，不猜测后台阶段。 */
    public Detail advance(F f,Detail task,int index){var e=task.items().get(index);return runtime.roleMigrationTasks().advance(f.login(),task.id(),new Advance(f.partition.tenantId(),f.partition.applicationId(),f.partition.environment(),id(),e.id(),e.version()));}
    /** 取消保护任务版本，不修改任何Grant。 */
    public Detail cancel(F f,Detail task){return runtime.roleMigrationTasks().cancel(f.login(),task.id(),new Cancel(f.partition.tenantId(),f.partition.applicationId(),f.partition.environment(),id(),task.version()));}
    /** 实际当前授权记录供状态断言。 */
    public Grant current(F f,String id){return runtime.access().state(f.login(),f.partition,null,null).grants().stream().filter(g->g.id().equals(id)).findFirst().orElseThrow();}
    /** SQL范围字节必须保持一致，不只比较解码后的近似规则。 */
    public String scope(String id){return jdbc.queryForObject("SELECT rule_json FROM auth_governance.grant_scope WHERE grant_id=?",String.class,id);}
    private BootstrapCommand person(String tenant,String code){var c=new BootstrapCommand(id(),"mg13",tenant,code,id(),"https://mg13.example",id(),id(),Instant.parse("2020-01-01T00:00:00Z"),null,"mg13",id(),id());runtime.identity().bootstrapEmployee(c);return c;}
    /** 每个测试来源和命令均使用独立UUID。 */
    public static String id(){return UUID.randomUUID().toString();}
    /** 只关闭本轮连接池，测试数据和图关系保留。 */
    public void close(){runtime.close();}
}
