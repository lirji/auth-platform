package com.lrj.authz.sdk;

import com.fasterxml.jackson.databind.*;
import com.lrj.authz.protocol.CentralAccessDtos.*;
import com.lrj.authz.protocol.GovernanceDtos.AccessContext;
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

}
