package com.lrj.authz.sdk;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.lrj.authz.protocol.CentralAccessDtos.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** 可信双身份中央检查客户端；独立Jackson2避免宿主Boot3/4消息转换差异。 */
public final class CentralAccessClient {
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    private final URI base;private final String credential;private final String application;private final String environment;
    private final HttpClient http;private final Duration timeout;
    /** 调用方必须绑定预期app/env，不接受从响应学习自己的安全边界。 */
    public CentralAccessClient(String base,String credential,String application,String environment,Duration connectTimeout,Duration timeout){
        this.base=URI.create(base);this.credential=credential;this.application=application;this.environment=environment;this.timeout=timeout;
        if(this.base.getHost()==null||this.base.getUserInfo()!=null||this.base.getQuery()!=null||this.base.getFragment()!=null
                ||(!this.base.getPath().isEmpty()&&!"/".equals(this.base.getPath()))
                ||!("https".equals(this.base.getScheme())||("http".equals(this.base.getScheme())&&Set.of("localhost","127.0.0.1").contains(this.base.getHost())))
                ||credential==null||credential.length()<32||credential.length()>512||credential.chars().anyMatch(Character::isWhitespace)
                ||!code(application)||environment==null||!environment.matches("[a-z0-9][a-z0-9-]{0,39}")
                ||connectTimeout==null||timeout==null||connectTimeout.isNegative()||connectTimeout.isZero()||connectTimeout.toMillis()>5000
                ||timeout.isNegative()||timeout.isZero()||timeout.toMillis()>10000)throw new IllegalArgumentException("invalid central access configuration");
        http=HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    /** 每次独立认证和判权；凭据只在请求header，不保存在Decision里。 */
    public Decision check(String userAccessToken,Check request){
        validate(request);Decision decision=post("check",userAccessToken,request,Decision.class);validateResponse(request,decision);return decision;
    }
    /** 有界批次严格按关联ID和契约验证，缺项/重复/未知结果均视为故障。 */
    public List<Decision> checkBulk(String userAccessToken,List<Check> checks){
        if(checks==null||checks.isEmpty()||checks.size()>20)throw new IllegalArgumentException("invalid check batch");
        Set<String> ids=new HashSet<>();for(Check check:checks){validate(check);if(!ids.add(check.requestId()))throw new IllegalArgumentException("duplicate request id");}
        Decisions response=post("check-bulk",userAccessToken,new Batch(List.copyOf(checks)),Decisions.class);
        if(response==null||response.results()==null||response.results().size()!=checks.size())throw unavailable();
        Map<String,Decision> indexed=new HashMap<>();
        for(Decision decision:response.results())if(decision==null||indexed.put(decision.requestId(),decision)!=null)throw unavailable();
        List<Decision> result=new ArrayList<>();for(Check check:checks){Decision decision=indexed.get(check.requestId());validateResponse(check,decision);result.add(decision);}return List.copyOf(result);
    }
    /** 无代理/自调用等路径必须显式使用此入口，DENY和故障不混为成功。 */
    public Decision requireAllowed(String token,Check request){Decision decision=check(token,request);if(!"ALLOW".equals(decision.decision()))throw new AccessDeniedException("CENTRAL_ACCESS_DENIED");return decision;}
    private <T>T post(String path,String user,Object request,Class<T> type){
        if(user==null||user.isBlank()||user.length()>16384||user.chars().anyMatch(Character::isWhitespace))throw new CentralAccessException(401);
        try{
            var message=HttpRequest.newBuilder(base.resolve("/internal/governance/v1/access/"+path)).timeout(timeout)
                    .header("Authorization","Bearer "+credential).header("X-User-Access-Token",user).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(request))).build();
            var response=http.send(message,ignored->new BoundedBody());
            if(response.statusCode()!=200)throw new CentralAccessException(response.statusCode()==401?401:503);
            if(!response.headers().firstValue("Content-Type").orElse("").split(";",2)[0].trim().equalsIgnoreCase("application/json"))throw unavailable();
            return JSON.readValue(response.body(),type);
        }catch(CentralAccessException failure){throw failure;}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw unavailable();}
        catch(Exception failure){throw unavailable();}
    }
    private void validate(Check request){
        if(request==null||!uuid(request.tenantId())||!uuid(request.requestId())||!code(request.capability())||!code(request.resourceType())
                ||(request.expectedMembershipGeneration()!=null&&request.expectedMembershipGeneration()<1))throw new IllegalArgumentException("invalid central check");
    }
    private void validateResponse(Check request,Decision d){
        if(d==null||!"1".equals(d.schemaVersion())||!request.requestId().equals(d.requestId())||!request.capability().equals(d.capability())||!request.resourceType().equals(d.resourceType())
                ||!Set.of("ALLOW","DENY").contains(d.decision()==null?"":d.decision())||!uuid(d.decisionId())||d.context()==null)throw unavailable();
        var c=d.context();
        if(!request.tenantId().equals(c.tenantId())||!application.equals(c.applicationId())||!environment.equals(c.environment())||!"HUMAN".equals(c.actorType())
                ||!uuid(c.principalId())||!uuid(c.membershipId())||!uuid(c.traceId())||!code(c.callerServiceId())||c.membershipGeneration()<1||c.membershipVersion()<1||c.principalVersion()<1
                ||(request.expectedMembershipGeneration()!=null&&request.expectedMembershipGeneration()!=c.membershipGeneration())
                ||("ALLOW".equals(d.decision())?!"TENANT_ALL".equals(d.scope()):d.scope()!=null))throw unavailable();
    }
    private static boolean code(String value){return value!=null&&value.matches("[a-z][a-z0-9._-]{0,99}");}
    private static boolean uuid(String value){try{return value!=null&&UUID.fromString(value).toString().equals(value);}catch(IllegalArgumentException e){return false;}}
    private static CentralAccessException unavailable(){return new CentralAccessException(503);}
    /** 限制响应字节数，恶意/损坏服务不能用无限body占用内存；HttpClient请求超时覆盖订阅完成。 */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();private Flow.Subscription subscription;private int received;
        public CompletionStage<byte[]> getBody(){return delegate.getBody();}
        public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;delegate.onSubscribe(subscription);}
        public void onNext(List<ByteBuffer> items){for(ByteBuffer item:items){received+=item.remaining();if(received>65536){subscription.cancel();delegate.onError(unavailable());return;}}delegate.onNext(items);}
        public void onError(Throwable failure){delegate.onError(failure);}
        public void onComplete(){delegate.onComplete();}
    }
}
