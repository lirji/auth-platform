package com.lrj.authz.sdk;

import com.fasterxml.jackson.databind.*;
import com.lrj.authz.protocol.CentralAccessDtos.*;
import com.lrj.authz.protocol.GovernanceDtos.AccessContext;
import com.lrj.authz.protocol.ScopeAccessDtos.*;
import com.lrj.authz.protocol.ScopeDtos.*;
import java.time.Instant;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

/** 真实HTTP协议边界证明严格响应关联、超时和拒绝不会被解释为允许。 */
class CentralAccessClientTest {
    private HttpServer server;private CentralAccessClient client;private final AtomicReference<String> response=new AtomicReference<>();private final AtomicInteger status=new AtomicInteger(200);
    private final ObjectMapper json=new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final String tenant=UUID.randomUUID().toString();private final Check request=new Check(tenant,1L,UUID.randomUUID().toString(),"commerce.store.read","store");
    @BeforeEach void start()throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/governance/v1/access",exchange->{exchange.getRequestBody().readAllBytes();byte[] bytes=response.get().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status.get(),bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
        client=new CentralAccessClient("http://127.0.0.1:"+server.getAddress().getPort(),"s".repeat(48),"commerce","test",Duration.ofSeconds(1),Duration.ofSeconds(1));response.set(json.writeValueAsString(decision(request,"ALLOW")));
    }
    @AfterEach void stop(){server.stop(0);}
    private Decision decision(Check r,String result){return new Decision("1",r.requestId(),r.capability(),r.resourceType(),result,"ALLOW".equals(result)?"TENANT_ALL":null,
        new AccessContext(UUID.randomUUID().toString(),UUID.randomUUID().toString(),1,1,1,tenant,"commerce","test","commerce-p2","HUMAN",UUID.randomUUID().toString()),UUID.randomUUID().toString());}
    @Test void explicitAllowDenyAndAuthenticationRemainDistinct()throws Exception{
        assertThat(client.requireAllowed("user-token",request).scope()).isEqualTo("TENANT_ALL");
        response.set(json.writeValueAsString(decision(request,"DENY")));assertThatThrownBy(()->client.requireAllowed("user-token",request)).isInstanceOf(AccessDeniedException.class);
        status.set(401);assertThatThrownBy(()->client.check("user-token",request)).isInstanceOfSatisfying(CentralAccessException.class,e->assertThat(e.status()).isEqualTo(401));
        status.set(503);assertThatThrownBy(()->client.check("user-token",request)).isInstanceOf(CentralAccessException.class);
    }
    @Test void executionUsesServiceOnlyAndRejectsForeignOrExpiredReferences()throws Exception {
        var captured=new AtomicReference<String>();
        server.removeContext("/internal/governance/v1/access");
        server.createContext("/internal/governance/v1/access",exchange->{captured.set(exchange.getRequestHeaders().getFirst("X-User-Access-Token"));exchange.getRequestBody().readAllBytes();byte[] bytes=response.get().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status.get(),bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});
        var c=decision(request,"ALLOW").context();String execution=UUID.randomUUID().toString();Instant until=Instant.now().plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var ref=new com.lrj.authz.protocol.ExecutionAccessDtos.Reference("1",request.requestId(),execution,c,request.capability(),request.resourceType(),until.toString());
        response.set(json.writeValueAsString(ref));assertThat(client.issueExecution("user-token",request,until.plusNanos(1)).executionId()).isEqualTo(execution);assertThat(captured.get()).isEqualTo("user-token");
        response.set(json.writeValueAsString(ref).replace(tenant,UUID.randomUUID().toString()));assertThatThrownBy(()->client.issueExecution("user-token",request,until)).isInstanceOf(CentralAccessException.class);
        var facts=new Facts(tenant,"store","S001",2,null,null,List.of(),"S001",null);
        var decision=new ResourceDecision("1",request.requestId(),request.capability(),"store","S001",2,"ALLOW",UUID.randomUUID().toString(),c,Instant.now().plusSeconds(25).toString());
        response.set(json.writeValueAsString(decision));assertThat(client.checkExecution(execution,request,facts).decision()).isEqualTo("ALLOW");assertThat(captured.get()).isNull();
        response.set(json.writeValueAsString(decision).replace("S001","S002"));assertThatThrownBy(()->client.checkExecution(execution,request,facts)).isInstanceOf(CentralAccessException.class);
        status.set(403);assertThatThrownBy(()->client.checkExecution(execution,request,facts)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void invalidOrMismatchedResponseFailsClosed()throws Exception{
        String good=response.get();
        for(String invalid:List.of("{}","null",good.replace("ALLOW","UNKNOWN"),good.replace("TENANT_ALL","STORE"),good.replace("commerce\"","other\""),good.replace("test\"","prod\""),
                good.replace(tenant,UUID.randomUUID().toString()),good.replace(request.requestId(),UUID.randomUUID().toString()),good.replace("\"membership_generation\":1","\"membership_generation\":2"),good.replace("\"principal_version\":1","\"principal_version\":0")," ".repeat(70000))){
            response.set(invalid);assertThatThrownBy(()->client.check("user-token",request)).isInstanceOf(CentralAccessException.class);
        }
    }
    @Test void bulkRejectsMissingDuplicateForeignAndConflictingItems()throws Exception{
        Check second=new Check(tenant,1L,UUID.randomUUID().toString(),"commerce.store.write","store");
        var first=decision(request,"ALLOW");var next=decision(second,"DENY");
        response.set(json.writeValueAsString(new Decisions(List.of(next,first))));assertThat(client.checkBulk("user-token",List.of(request,second))).extracting(Decision::requestId).containsExactly(request.requestId(),second.requestId());
        for(List<Decision> malformed:List.of(List.of(first),List.of(first,first),List.of(first,decision(new Check(tenant,1L,UUID.randomUUID().toString(),second.capability(),"store"),"ALLOW")))){
            response.set(json.writeValueAsString(new Decisions(malformed)));assertThatThrownBy(()->client.checkBulk("user-token",List.of(request,second))).isInstanceOf(CentralAccessException.class);
        }
    }
    @Test void readTimeoutCannotFallbackToAllow()throws Exception{
        server.removeContext("/internal/governance/v1/access");server.createContext("/internal/governance/v1/access",exchange->{try{Thread.sleep(300);}catch(InterruptedException e){Thread.currentThread().interrupt();}exchange.close();});
        client=new CentralAccessClient("http://127.0.0.1:"+server.getAddress().getPort(),"s".repeat(48),"commerce","test",Duration.ofMillis(100),Duration.ofMillis(100));
        assertThatThrownBy(()->client.check("user-token",request)).isInstanceOf(CentralAccessException.class);
    }
    @Test void timeoutAlsoBoundsAStalledResponseBody() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        server.removeContext("/internal/governance/v1/access");
        server.createContext("/internal/governance/v1/access", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 1000);
            exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
            try { release.await(2, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        client = new CentralAccessClient("http://127.0.0.1:" + server.getAddress().getPort(), "s".repeat(48),
                "commerce", "test", Duration.ofMillis(100), Duration.ofMillis(100));
        try {
            org.junit.jupiter.api.Assertions.assertTimeout(Duration.ofMillis(750), () ->
                    assertThatThrownBy(() -> client.check("user-token", request)).isInstanceOf(CentralAccessException.class));
        } finally { release.countDown(); }
    }

    private Plan plan(){
        return new Plan("1",request.requestId(),request.capability(),request.resourceType(),"ALLOW",UUID.randomUUID().toString(),decision(request,"ALLOW").context(),
            UUID.randomUUID().toString(),1,UUID.randomUUID().toString(),1,1,1,Instant.now().plusSeconds(25).toString(),
            List.of(new Alternative(UUID.randomUUID().toString(),1,List.of(new Clause(Kind.SPECIFIED_STORES,List.of("S001"),false)))));
    }
    @Test void scopePlanRejectsForeignIdentityStaleVersionsExpiredAndMalformedRules()throws Exception{
        String good=json.writeValueAsString(plan());response.set(good);assertThat(client.requireScope("user-token",request).alternatives()).hasSize(1);
        for(String bad:List.of("{}",good.replace("SPECIFIED_STORES","SELF"),good.replace("SPECIFIED_STORES","UNKNOWN"),good.replace("\"policy_epoch\":1","\"policy_epoch\":0"),
            good.replace("\"include_root\":false","\"include_root\":true"),good.replace("\"scope_version\":1","\"scope_version\":2"),good.replace("\"values\":[\"S001\"]","\"values\":[]"),
            good.replace("\"membership_generation\":1","\"membership_generation\":2"),good.replace(tenant,UUID.randomUUID().toString())," ".repeat(262145))){
            response.set(bad);assertThatThrownBy(()->client.scopePlan("user-token",request)).isInstanceOf(CentralAccessException.class);
        }
        var expired=json.readTree(good).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)expired).put("valid_until","2020-01-01T00:00:00Z");response.set(expired.toString());
        assertThatThrownBy(()->client.scopePlan("user-token",request)).isInstanceOf(CentralAccessException.class);
    }
    @Test void emptyDeniedScopeCannotBecomeUnrestrictedSql()throws Exception{
        var data=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(plan());data.put("decision","DENY");data.putArray("alternatives");response.set(data.toString());
        assertThat(client.scopePlan("user-token",request).alternatives()).isEmpty();assertThatThrownBy(()->client.requireScope("user-token",request)).isInstanceOf(AccessDeniedException.class);
        data.put("decision","ALLOW");response.set(data.toString());assertThatThrownBy(()->client.scopePlan("user-token",request)).isInstanceOf(CentralAccessException.class);
    }
    @Test void scopeFingerprintIgnoresRequestNonceButBindsPolicyAndPrincipal()throws Exception{
        var before=plan();var node=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(before);
        node.put("decision_id",UUID.randomUUID().toString());node.put("request_id",UUID.randomUUID().toString());
        ((com.fasterxml.jackson.databind.node.ObjectNode)node.get("context")).put("trace_id",UUID.randomUUID().toString());
        var same=json.treeToValue(node,Plan.class);assertThat(CentralAccessClient.scopeFingerprint(before)).isEqualTo(CentralAccessClient.scopeFingerprint(same));CentralAccessClient.requireSameScope(before,same);
        node.put("policy_epoch",2);var newer=json.treeToValue(node,Plan.class);assertThatThrownBy(()->CentralAccessClient.requireSameScope(before,newer)).isInstanceOf(AccessDeniedException.class);
        node.put("policy_epoch",1);((com.fasterxml.jackson.databind.node.ObjectNode)node.get("context")).put("principal_id",UUID.randomUUID().toString());
        var foreign=json.treeToValue(node,Plan.class);assertThatThrownBy(()->CentralAccessClient.requireSameScope(before,foreign)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void resourceResponseMustMatchExactOwnerFactsVersion()throws Exception{
        var p=plan();var facts=new Facts(tenant,"store","S001",7,null,null,List.of(),"S001",null);
        var d=new ResourceDecision("1",request.requestId(),request.capability(),"store","S001",7,"ALLOW",p.decisionId(),p.context(),p.validUntil());
        response.set(json.writeValueAsString(d));assertThat(client.checkResource("user-token",request,facts).decision()).isEqualTo("ALLOW");
        response.set(json.writeValueAsString(d).replace("\"resource_version\":7","\"resource_version\":8"));
        assertThatThrownBy(()->client.checkResource("user-token",request,facts)).isInstanceOf(CentralAccessException.class);
    }

    @Test void newResourceResponseCannotSmuggleStoreOrIndividualMemberScopes() throws Exception {
        var member = new Check(tenant, 1L, UUID.randomUUID().toString(), "commerce.member.read", "commerce_member");
        var original = plan();
        var allowed = new Plan("1", member.requestId(), member.capability(), member.resourceType(), "ALLOW", UUID.randomUUID().toString(),
                original.context(), original.policyPartitionId(), 1, original.directoryPartitionId(), 1, 1, 1,
                original.validUntil(), List.of(new Alternative(UUID.randomUUID().toString(), 1, List.of(new Clause(Kind.TENANT_ALL, List.of(), false)))));
        response.set(json.writeValueAsString(allowed));
        assertThat(client.requireScope("user-token", member).alternatives()).hasSize(1);
        for (Kind kind : List.of(Kind.SPECIFIED_STORES, Kind.SPECIFIED_RESOURCES)) {
            var bad = new Plan(allowed.schemaVersion(), allowed.requestId(), allowed.capability(), allowed.resourceType(), allowed.decision(),
                    allowed.decisionId(), allowed.context(), allowed.policyPartitionId(), 1, allowed.directoryPartitionId(), 1, 1, 1,
                    allowed.validUntil(), List.of(new Alternative(UUID.randomUUID().toString(), 1, List.of(new Clause(kind, List.of("S1"), false)))));
            response.set(json.writeValueAsString(bad));
            assertThatThrownBy(() -> client.requireScope("user-token", member)).isInstanceOf(CentralAccessException.class);
        }
    }
}
