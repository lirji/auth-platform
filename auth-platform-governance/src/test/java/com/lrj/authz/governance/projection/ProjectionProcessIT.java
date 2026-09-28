package com.lrj.authz.governance.projection;

import com.lrj.authz.core.SpiceDbAuthzEngine;
import com.lrj.authz.core.SpiceDbProjectionGraph;
import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.domain.AccessModels.*;
import com.lrj.authz.governance.domain.CatalogModels.*;
import com.lrj.authz.governance.domain.ProjectionModels.*;
import com.lrj.authz.governance.persistence.*;
import com.lrj.authz.protocol.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.*;
import java.util.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.net.*;
import java.net.http.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

/** 两个真实子JVM与HTTP故障代理验证暂停旧写、kill和恢复，不模拟图原子性。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ProjectionProcessIT {
    private GovernanceRuntime runtime;
    private GovernanceRuntime second;
    private JdbcTemplate jdbc;
    private ProjectionGraph graph;
    private AuthzEngine observer;
    private Properties databaseConfig;
    private Properties graphConfig;
    @BeforeAll void open() {
        databaseConfig=GovernanceConfigurationFile.read(System.getenv("GOVERNANCE_TEST_CONFIG"));
        var db = GovernanceDatabase.from(databaseConfig);
        assertThat(db.jdbcUrl()).matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/auth_gov_p1_test_[a-z0-9_]+");
        runtime = GovernanceRuntime.open(db, true); second = GovernanceRuntime.open(db, false);
        jdbc = new JdbcTemplate(new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password()));
        var p = GovernanceConfigurationFile.read(System.getenv("GOVERNANCE_P3_GRAPH_CONFIG")); graphConfig=p;
        assertThat(p.getProperty("graph.http")).isEqualTo("http://127.0.0.1:18544");
        graph = new SpiceDbProjectionGraph(p.getProperty("graph.http"), p.getProperty("graph.key"), Duration.ofSeconds(3));
        observer = new SpiceDbAuthzEngine(p.getProperty("graph.http"), p.getProperty("graph.key"), Duration.ofSeconds(1), Duration.ofSeconds(3));
    }
    @AfterAll void close() { if (second != null) second.close(); if (runtime != null) runtime.close(); }
    private String id() { return UUID.randomUUID().toString(); }
    private BootstrapCommand person(String tenant, String code) {
        var c = new BootstrapCommand(id(), "projection-test", tenant, code, id(), "https://projection.example", id(), id(),
                Instant.parse("2020-01-01T00:00:00Z"), null, "fixture", id(), id());
        runtime.identity().bootstrapEmployee(c); return c;
    }
    private Fixture fixture() {
        String tenant = id(), code = "t-" + id(), app = "app-" + id();
        var owner = person(tenant, code); var member = person(tenant, code);
        var login = new VerifiedLogin(owner.issuer(), owner.subject());
        runtime.catalog().register(app, owner.principalId(), "https://example.test", "test", id());
        runtime.catalog().publish(login, new Manifest("1", app, 1, List.of(new Capability(app + ".read", "store", Risk.NORMAL)), List.of()), id());
        var p = new Partition(tenant, app, "test");
        runtime.access().bootstrap(p, new Delegation(owner.membershipId(), 1, AccessValues.json(List.of(app + ".read")), 3600), "test", id());
        var role = runtime.access().createRole(login, p, id(), "reader", 1, List.of(app + ".read"));
        runtime.access().enableStrict(login, p, id());
        return new Fixture(p, login, member, role);
    }
    private Grant grant(Fixture f) {
        return runtime.access().grant(f.login, f.p, id(), f.member.membershipId(), 1, f.role.id(), "TENANT_ALL", id(), Instant.now(), Instant.now().plusSeconds(300));
    }
    private String fenceId(Fixture f) { return jdbc.queryForObject("select id from auth_governance.policy_partition where tenant_id=?", String.class, f.p.tenantId()); }
    private String state(Fixture f) { return jdbc.queryForObject("select state from auth_governance.policy_partition where tenant_id=?", String.class, f.p.tenantId()); }
    private boolean graphAllows(Fixture f, Grant grant) {
        return observer.check(SubjectRef.of(ProjectionGraph.MEMBER_TYPE, f.member.membershipId() + "_g1"), "eligible",
                ResourceRef.of(ProjectionGraph.GRANT_TYPE, grant.id()), Consistency.fullyConsistent());
    }
    @Test void pausedProcessLateRequestCannotUndoCompletedRevoke() throws Exception {
        var f=fixture();var g=grant(f);Path dir=directory("paused-worker");
        Process old=null,newer=null;
        try(var proxy=new FaultProxy(false)){
            old=start(f,dir,"old",proxy.url());
            assertThat(proxy.reached.await(10,TimeUnit.SECONDS)).isTrue();
            signal(old,"-STOP");
            long waited=awaitExpired(f);
            runtime.access().revoke(f.login,f.p,id(),g.id(),1);
            newer=start(f,dir,"new",graphConfig.getProperty("graph.http"));
            assertThat(newer.waitFor(15,TimeUnit.SECONDS)).isTrue();assertThat(newer.exitValue()).isZero();
            assertThat(Files.readString(dir.resolve("new.result"))).isEqualTo(Step.READY.name());
            assertThat(state(f)).isEqualTo("READY");assertThat(graphAllows(f,g)).isFalse();
            signal(old,"-CONT");proxy.release.countDown();
            assertThat(proxy.forwarded.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(proxy.remoteStatus.get()).isEqualTo(400);assertThat(proxy.remoteCode.get()).isEqualTo(9);
            assertThat(old.waitFor(15,TimeUnit.SECONDS)).isTrue();
            assertThat(graphAllows(f,g)).isFalse();assertThat(state(f)).isEqualTo("READY");
            evidence(dir,Map.of("scenario","SIGSTOP_REVOKE_SIGCONT","old_pid",old.pid(),"new_pid",newer.pid(),
                    "real_lease_wait_ms",waited,"stale_remote_status",proxy.remoteStatus.get(),"stale_remote_code",proxy.remoteCode.get(),"result","PASS"));
        }finally{stop(old);stop(newer);}
    }
    @Test void killedProcessAfterGraphCommitRecoversMissingSqlReceipt() throws Exception {
        var f=fixture();var g=grant(f);Path dir=directory("kill-after-graph");
        Process killed=null,recovery=null;
        try(var proxy=new FaultProxy(true)){
            killed=start(f,dir,"killed",proxy.url());
            assertThat(proxy.reached.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(proxy.remoteStatus.get()).isEqualTo(200);assertThat(graphAllows(f,g)).isTrue();
            assertThat(state(f)).isEqualTo("UPDATING");assertThat(receipts(f)).isZero();
            killed.destroyForcibly();assertThat(killed.waitFor(10,TimeUnit.SECONDS)).isTrue();
            assertThat(killed.exitValue()).isNotZero();proxy.release.countDown();
            long waited=awaitExpired(f);
            recovery=start(f,dir,"recovery",graphConfig.getProperty("graph.http"));
            assertThat(recovery.waitFor(15,TimeUnit.SECONDS)).isTrue();assertThat(recovery.exitValue()).isZero();
            assertThat(Files.readString(dir.resolve("recovery.result"))).isEqualTo(Step.RECOVERED.name());
            assertThat(state(f)).isEqualTo("READY");assertThat(receipts(f)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from auth_governance.projection_operation where fence_id=?",Integer.class,fenceId(f))).isEqualTo(1);
            assertThat(graphAllows(f,g)).isTrue();
            evidence(dir,Map.of("scenario","SIGKILL_AFTER_REMOTE_COMMIT","killed_pid",killed.pid(),"recovery_pid",recovery.pid(),
                    "killed_exit",killed.exitValue(),"real_lease_wait_ms",waited,"receipt_count",receipts(f),"operation_count",1,"result","PASS"));
        }finally{stop(killed);stop(recovery);}
    }
    private int receipts(Fixture f){return jdbc.queryForObject("select count(*) from auth_governance.projection_receipt r join auth_governance.projection_operation o on o.id=r.operation_id where o.fence_id=?",Integer.class,fenceId(f));}
    private Path directory(String name)throws IOException{
        Path parent=Path.of(System.getenv("GOVERNANCE_TEST_CONFIG")).getParent().resolve("p3/process-tests");
        Files.createDirectories(parent);
        return Files.createDirectory(parent.resolve(name+"-"+id()),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }
    private Process start(Fixture f,Path dir,String name,String graphUrl)throws Exception{
        Properties p=new Properties();p.putAll(databaseConfig);p.putAll(graphConfig);p.setProperty("graph.http",graphUrl);
        p.setProperty("access.tenant",f.p.tenantId());p.setProperty("access.application",f.p.applicationId());p.setProperty("access.environment",f.p.environment());
        Path config=Files.createFile(dir.resolve(name+".properties"),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try(var out=Files.newBufferedWriter(config)){p.store(out,"isolated process fault fixture");}
        Path log=Files.createFile(dir.resolve(name+".log"),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        return new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",cp,WorkerMain.class.getName(),config.toString(),dir.resolve(name+".result").toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }
    private long awaitExpired(Fixture f)throws Exception{
        long start=System.nanoTime(),deadline=start+TimeUnit.SECONDS.toNanos(20);
        while(!Boolean.TRUE.equals(jdbc.queryForObject("select lease_until<=clock_timestamp() from auth_governance.projection_stream where fence_id=?",Boolean.class,fenceId(f)))){
            if(System.nanoTime()>deadline)throw new AssertionError("real lease did not expire");
            Thread.sleep(100);
        }
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
    }
    private void signal(Process process,String signal)throws Exception{
        var result=new ProcessBuilder("kill",signal,Long.toString(process.pid())).start();
        assertThat(result.waitFor(5,TimeUnit.SECONDS)).isTrue();assertThat(result.exitValue()).isZero();
    }
    private void stop(Process process)throws Exception{if(process!=null&&process.isAlive()){process.destroyForcibly();assertThat(process.waitFor(5,TimeUnit.SECONDS)).isTrue();}}
    private void evidence(Path directory,Map<String,Object> values)throws IOException{
        Files.writeString(directory.resolve("evidence.json"),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(values),StandardOpenOption.CREATE_NEW);
    }
    /** 真正独立JVM运行相同生产执行器；没有改写SQL状态或图结果的测试钩子。 */
    public static final class WorkerMain {
        public static void main(String[] args)throws Exception{
            var p=GovernanceConfigurationFile.read(args[0]);
            try(var runtime=GovernanceRuntime.open(GovernanceDatabase.from(p),false)){
                var graph=new SpiceDbProjectionGraph(p.getProperty("graph.http"),p.getProperty("graph.key"),Duration.ofSeconds(10));
                var partition=new Partition(p.getProperty("access.tenant"),p.getProperty("access.application"),p.getProperty("access.environment"));
                Step result=runtime.reliableProjector(graph).step(partition,Kind.POLICY,UUID.randomUUID().toString());
                Files.writeString(Path.of(args[1]),result.name(),StandardOpenOption.CREATE_NEW);
            }
        }
    }
    /** 只延迟自有worker的真实HTTP请求/返回，实际CAS仍由18544的SpiceDB执行。 */
    private final class FaultProxy implements AutoCloseable {
        final CountDownLatch reached=new CountDownLatch(1),release=new CountDownLatch(1),forwarded=new CountDownLatch(1);
        final AtomicInteger remoteStatus=new AtomicInteger(),remoteCode=new AtomicInteger(),disconnects=new AtomicInteger();
        private final HttpServer server;
        private final ExecutorService executor=Executors.newFixedThreadPool(2);
        private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        FaultProxy(boolean after)throws IOException{
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);
            server.createContext("/",exchange->{
                boolean write=exchange.getRequestURI().getPath().equals("/v1/relationships/write");
                try{
                    byte[] body=exchange.getRequestBody().readAllBytes();
                    if(write&&!after){reached.countDown();if(!release.await(35,TimeUnit.SECONDS))throw new IOException("test gate timeout");}
                    var req=HttpRequest.newBuilder(URI.create(graphConfig.getProperty("graph.http")+exchange.getRequestURI().getPath())).timeout(Duration.ofSeconds(5))
                            .header("Authorization","Bearer "+graphConfig.getProperty("graph.key")).header("Content-Type","application/json")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
                    var response=http.send(req,HttpResponse.BodyHandlers.ofByteArray());
                    if(write){remoteStatus.set(response.statusCode());remoteCode.set(new ObjectMapper().readTree(response.body()).path("code").asInt());forwarded.countDown();}
                    if(write&&after){reached.countDown();if(!release.await(35,TimeUnit.SECONDS))throw new IOException("test gate timeout");}
                    exchange.sendResponseHeaders(response.statusCode(),response.body().length);exchange.getResponseBody().write(response.body());
                }catch(InterruptedException failure){Thread.currentThread().interrupt();}
                catch(IOException failure){disconnects.incrementAndGet();}
                finally{exchange.close();}
            });server.start();
        }
        String url(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        public void close(){release.countDown();server.stop(0);executor.shutdownNow();http.close();}
    }
    private record Fixture(Partition p,VerifiedLogin login,BootstrapCommand member,RoleVersion role){}
}
