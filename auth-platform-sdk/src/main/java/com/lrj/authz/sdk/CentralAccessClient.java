package com.lrj.authz.sdk;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.lrj.authz.protocol.CentralAccessDtos.*;
import com.lrj.authz.protocol.ScopeAccessDtos.*;
import com.lrj.authz.protocol.ScopeDtos;
import com.lrj.authz.protocol.ScopeResourceBindings;
import com.lrj.authz.protocol.GovernanceDtos.AccessContext;
import java.time.Instant;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** 可信双身份中央检查客户端；独立Jackson2避免宿主Boot3/4消息转换差异。 */
public final class CentralAccessClient {
    private static final String HUMAN_ACTOR_TYPE="HUMAN";
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
    /** 本人导航只提供当前提示，独立协议不能由单能力执行引用或管理Token代替。 */
    public com.lrj.authz.protocol.NavigationDtos.View navigation(String user,com.lrj.authz.protocol.NavigationDtos.Request request) {
        if(request==null||!uuid(request.tenantId())||!uuid(request.requestId())
                ||(request.expectedMembershipGeneration()!=null&&request.expectedMembershipGeneration()<1))throw new IllegalArgumentException("invalid navigation request");
        var view=post("navigation",user,request,com.lrj.authz.protocol.NavigationDtos.View.class,com.lrj.authz.protocol.NavigationDtos.MAX_RESPONSE_BYTES);
        if(view==null||!"1".equals(view.schemaVersion())||!request.requestId().equals(view.requestId())||view.context()==null
                ||view.manifestVersion()<1||!hash(view.contentHash())||!hash(view.presentationHash())||view.state()==null
                ||view.menus()==null||view.menus().size()>com.lrj.authz.protocol.NavigationDtos.MAX_MENUS
                ||view.capabilityHints()==null||view.capabilityHints().size()>com.lrj.authz.protocol.NavigationDtos.MAX_CAPABILITIES)throw unavailable();
        com.lrj.authz.protocol.NavigationDtos.State state;
        try { state=com.lrj.authz.protocol.NavigationDtos.State.from(view.state()); }
        catch(IllegalArgumentException unknown) { throw unavailable(); }
        validateContext(new Check(request.tenantId(),request.expectedMembershipGeneration(),request.requestId(),"navigation","navigation"),view.context());
        try {
            var observed=Instant.parse(view.observedAt());var now=Instant.now();
            if(!view.observedAt().endsWith("Z")||observed.isAfter(now.plusSeconds(5))||observed.isBefore(now.minusSeconds(31)))throw unavailable();
        } catch(RuntimeException invalid) { throw unavailable(); }
        var caps=new HashSet<String>();
        for(String capability:view.capabilityHints())if(!code(capability)||!capability.startsWith(application+".")||!caps.add(capability))throw unavailable();
        var menus=new HashMap<String,com.lrj.authz.protocol.NavigationDtos.Menu>();var positions=new HashSet<Integer>();
        for(var menu:view.menus()) {
            if(menu==null||!code(menu.code())||menus.put(menu.code(),menu)!=null||(menu.parent()!=null&&!code(menu.parent()))
                    ||(menu.route()!=null&&(!menu.route().matches("/[a-zA-Z0-9/_-]*")||menu.route().contains("//")))
                    ||((menu.label()==null)!=(menu.position()==null)))throw unavailable();
            if(menu.label()!=null&&(menu.label().isBlank()||!menu.label().equals(menu.label().strip())||menu.label().codePointCount(0,menu.label().length())>80
                    ||menu.label().codePoints().anyMatch(c->Character.isISOControl(c)||c=='<'||c=='>')||menu.position()<0
                    ||menu.position()>=com.lrj.authz.protocol.NavigationDtos.MAX_MENUS||!positions.add(menu.position())))throw unavailable();
        }
        for(var menu:view.menus()) {
            Set<String> ancestors=new HashSet<>();var current=menu;
            while(current!=null) {
                if(!ancestors.add(current.code())||(current.parent()!=null&&!menus.containsKey(current.parent())))throw unavailable();
                current=current.parent()==null?null:menus.get(current.parent());
            }
        }
        if(state==com.lrj.authz.protocol.NavigationDtos.State.NO_ACCESS?(!caps.isEmpty()||!menus.isEmpty()):(caps.isEmpty()||menus.isEmpty()))throw unavailable();
        return view;
    }
    /** 只在当前后端调用使用，完整验证版本/主体/范围后才允许交给SQL适配器。 */
    public Plan scopePlan(String user,Check request){
        validate(request);Plan p=post("scope-plan",user,request,Plan.class,ScopeDtos.MAX_PLAN_BYTES);
        return validatePlan(request,p);
    }
    private Plan validatePlan(Check request,Plan p) {
        if(p==null||!ScopeResourceBindings.supports(request.resourceType()))throw unavailable();
        validateEnvelope(request,p.schemaVersion(),p.requestId(),p.capability(),p.resourceType(),p.decision(),p.decisionId(),p.context());
        validUntil(p.validUntil());
        if(!uuid(p.policyPartitionId())||!uuid(p.directoryPartitionId())||p.policyEpoch()<1||p.directoryEpoch()<1||p.manifestVersion()<1||p.tenantVersion()<1
            ||p.alternatives()==null||p.alternatives().size()>100||("ALLOW".equals(p.decision())==p.alternatives().isEmpty()))throw unavailable();
        Set<String> ids=new HashSet<>();
        for(var a:p.alternatives()){
            if(a==null||!uuid(a.grantId())||!ids.add(a.grantId())||a.scopeVersion()!=1||a.clauses()==null||a.clauses().isEmpty()||a.clauses().size()>4)throw unavailable();
            Set<ScopeDtos.Kind> kinds=EnumSet.noneOf(ScopeDtos.Kind.class);
            for(var c:a.clauses()){
                if(c==null||c.kind()==null||!kinds.add(c.kind())||c.includeRoot()||c.values()==null||c.values().size()>100
                    ||!ScopeResourceBindings.allows(request.resourceType(),c.kind()))throw unavailable();
                if((c.kind()==ScopeDtos.Kind.TENANT_ALL)!=c.values().isEmpty()||new HashSet<>(c.values()).size()!=c.values().size()
                    ||c.values().stream().anyMatch(v->!resourceId(v)))throw unavailable();
            }
            if(kinds.contains(ScopeDtos.Kind.TENANT_ALL)&&kinds.size()!=1)throw unavailable();
        }
        return p;
    }
    /** 只用服务凭据复核目录引用，不能改成代理任意用户或缓存一次ALLOW。 */
    public Plan executionScope(String executionId, Check request) {
        validate(request); if(!uuid(executionId)) throw new IllegalArgumentException("invalid execution reference");
        var p=postService("execution-scope",null,new com.lrj.authz.protocol.ExecutionAccessDtos.ScopeCheck(executionId,request),Plan.class,ScopeDtos.MAX_PLAN_BYTES);
        return validatePlan(request,p);
    }
    /** 无允许路径直接拒绝，不让业务层把空列表解释成全范围。 */
    public Plan requireScope(String user,Check request){var p=scopePlan(user,request);if(!"ALLOW".equals(p.decision()))throw new AccessDeniedException("CENTRAL_ACCESS_DENIED");return p;}
    /** 资源事实只能由已登记Owner后端读库构造，响应必须精确匹配本次资源版本。 */
    public ResourceDecision checkResource(String user,Check request,ScopeDtos.Facts facts){
        validate(request);if(facts==null||!request.tenantId().equals(facts.tenantId())||!request.resourceType().equals(facts.resourceType())||!resourceId(facts.resourceId())||facts.resourceVersion()<0)throw new IllegalArgumentException("invalid resource facts");
        var d=post("check-resource",user,new ResourceCheck(request,facts),ResourceDecision.class);
        if(d==null)throw unavailable();validateEnvelope(request,d.schemaVersion(),d.requestId(),d.capability(),d.resourceType(),d.decision(),d.decisionId(),d.context());
        validUntil(d.validUntil());if(!facts.resourceId().equals(d.resourceId())||facts.resourceVersion()!=d.resourceVersion())throw unavailable();return d;
    }
    /** 用户签发的执行引用不含Token；TTL不代替后续实时授权检查。 */
    public com.lrj.authz.protocol.ExecutionAccessDtos.Reference issueExecution(String user,Check request,Instant expiresAt) {
        validate(request);if(expiresAt==null)throw new IllegalArgumentException("invalid expiry");
        // PostgreSQL持久化为微秒精度，签发前规范化，避免重放响应因精度截断被误判。
        Instant target=expiresAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var ref=post("executions",user,new com.lrj.authz.protocol.ExecutionAccessDtos.Issue(request,target.toString()),com.lrj.authz.protocol.ExecutionAccessDtos.Reference.class);
        if(ref==null||!"1".equals(ref.schemaVersion())||!request.requestId().equals(ref.requestId())||!uuid(ref.executionId())
            ||!request.capability().equals(ref.capability())||!request.resourceType().equals(ref.resourceType())||ref.context()==null)throw unavailable();
        validateContext(request,ref.context());
        try { if(!Instant.parse(ref.expiresAt()).equals(target)||!target.isAfter(Instant.now()))throw unavailable(); }
        catch(RuntimeException e) { throw unavailable(); }
        return ref;
    }
    /** 服务只能复核既有引用，不能用服务凭据重新指定用户；拒绝和依赖故障均不回退。 */
    public ResourceDecision checkExecution(String executionId,Check request,ScopeDtos.Facts facts) {
        validate(request);if(!uuid(executionId)||facts==null||!request.tenantId().equals(facts.tenantId())||!request.resourceType().equals(facts.resourceType()))throw new IllegalArgumentException("invalid execution facts");
        var d=postService("execution-check",null,new com.lrj.authz.protocol.ExecutionAccessDtos.Check(executionId,new ResourceCheck(request,facts)),ResourceDecision.class,65536);
        if(d==null)throw unavailable();
        validateEnvelope(request,d.schemaVersion(),d.requestId(),d.capability(),d.resourceType(),d.decision(),d.decisionId(),d.context());
        validUntil(d.validUntil());if(!facts.resourceId().equals(d.resourceId())||facts.resourceVersion()!=d.resourceVersion())throw unavailable();return d;
    }
    /** 主体、策略、目录或同Grant范围变化后，旧游标/导出任务不能复用。 */
    public static String scopeFingerprint(Plan p){
        if(p==null||p.context()==null)throw unavailable();var c=p.context();
        var paths=p.alternatives().stream().sorted(Comparator.comparing(ScopeDtos.Alternative::grantId)).map(a->new ScopeDtos.Alternative(a.grantId(),a.scopeVersion(),
            a.clauses().stream().sorted(Comparator.comparing(v->v.kind().name())).map(v->new ScopeDtos.Clause(v.kind(),v.values().stream().sorted().toList(),v.includeRoot())).toList())).toList();
        var binding=List.of(p.schemaVersion(),c.principalId(),c.membershipId(),c.membershipGeneration(),c.membershipVersion(),c.principalVersion(),c.tenantId(),c.applicationId(),c.environment(),
            p.capability(),p.resourceType(),p.policyPartitionId(),p.policyEpoch(),p.directoryPartitionId(),p.directoryEpoch(),p.manifestVersion(),p.tenantVersion(),paths);
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(binding)));}
        catch(Exception failure){throw unavailable();}
    }
    /** 返回数据前的新检查点必须保持同一允许上下文，旧plan过期也不能继续使用。 */
    public static void requireSameScope(Plan before,Plan after){
        validUntil(before.validUntil());validUntil(after.validUntil());
        if(!"ALLOW".equals(before.decision())||!"ALLOW".equals(after.decision())||!scopeFingerprint(before).equals(scopeFingerprint(after)))throw new AccessDeniedException("CENTRAL_CONTEXT_CHANGED");
    }
    private void validateEnvelope(Check r,String schema,String request,String capability,String resource,String decision,String id,AccessContext context){
        if(!"1".equals(schema)||!r.requestId().equals(request)||!r.capability().equals(capability)||!r.resourceType().equals(resource)
            ||!Set.of("ALLOW","DENY").contains(decision==null?"":decision)||!uuid(id)||context==null)throw unavailable();
        validateContext(r,context);
    }
    private void validateContext(Check r,AccessContext c){
        if(!r.tenantId().equals(c.tenantId())||!application.equals(c.applicationId())||!environment.equals(c.environment())||!HUMAN_ACTOR_TYPE.equals(c.actorType())
            ||!uuid(c.principalId())||!uuid(c.membershipId())||!uuid(c.traceId())||!code(c.callerServiceId())||c.membershipGeneration()<1||c.membershipVersion()<1||c.principalVersion()<1
            ||(r.expectedMembershipGeneration()!=null&&r.expectedMembershipGeneration()!=c.membershipGeneration()))throw unavailable();
    }
    private static void validUntil(String value){
        try{Instant until=Instant.parse(value),now=Instant.now();if(!until.isAfter(now)||until.isAfter(now.plusSeconds(31)))throw unavailable();}
        catch(RuntimeException failure){throw unavailable();}
    }
    private static boolean resourceId(String value){return value!=null&&value.matches("[A-Za-z0-9_:/.-]{1,100}");}
    private <T>T post(String path,String user,Object request,Class<T> type){return post(path,user,request,type,65536);}
    private <T>T post(String path,String user,Object request,Class<T> type,int maximumBytes){
        if(user==null||user.isBlank()||user.length()>16384||user.chars().anyMatch(Character::isWhitespace))throw new CentralAccessException(401);
        return postService(path,user,request,type,maximumBytes);
    }
    private <T>T postService(String path,String user,Object request,Class<T> type,int maximumBytes){
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try{
            var message=HttpRequest.newBuilder(base.resolve("/internal/governance/v1/access/"+path)).timeout(timeout)
                    .header("Authorization","Bearer "+credential).header("Content-Type","application/json");
            if(user!=null)message.header("X-User-Access-Token",user);
            var built=message.POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(request))).build();
            // HttpRequest超时不足以约束已收响应头后的body停滞；总期限覆盖完整响应并取消在途交换。
            pending=http.sendAsync(built,ignored->new BoundedBody(maximumBytes));
            var response=pending.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
            if(response.statusCode()==403)throw new AccessDeniedException("CENTRAL_ACCESS_DENIED");
            if(response.statusCode()!=200)throw new CentralAccessException(response.statusCode()==401?401:503);
            if(!response.headers().firstValue("Content-Type").orElse("").split(";",2)[0].trim().equalsIgnoreCase("application/json"))throw unavailable();
            return JSON.readValue(response.body(),type);
        }catch(CentralAccessException | AccessDeniedException failure){throw failure;}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw unavailable();}
        catch(Exception failure){throw unavailable();}
        finally{if(pending!=null&&!pending.isDone())pending.cancel(true);}
    }
    private void validate(Check request){
        if(request==null||!uuid(request.tenantId())||!uuid(request.requestId())||!code(request.capability())||!code(request.resourceType())
                ||(request.expectedMembershipGeneration()!=null&&request.expectedMembershipGeneration()<1))throw new IllegalArgumentException("invalid central check");
    }
    private void validateResponse(Check request,Decision d){
        if(d==null||!"1".equals(d.schemaVersion())||!request.requestId().equals(d.requestId())||!request.capability().equals(d.capability())||!request.resourceType().equals(d.resourceType())
                ||!Set.of("ALLOW","DENY").contains(d.decision()==null?"":d.decision())||!uuid(d.decisionId())||d.context()==null)throw unavailable();
        validateContext(request,d.context());
        if("ALLOW".equals(d.decision())?!"TENANT_ALL".equals(d.scope()):d.scope()!=null)throw unavailable();
    }

    private static boolean code(String value){return value!=null&&value.matches("[a-z][a-z0-9._-]{0,99}");}
    private static boolean hash(String value){return value!=null&&value.matches("[a-f0-9]{64}");}
    private static boolean uuid(String value){try{return value!=null&&UUID.fromString(value).toString().equals(value);}catch(IllegalArgumentException e){return false;}}
    private static CentralAccessException unavailable(){return new CentralAccessException(503);}
    /** 限制响应字节数，恶意/损坏服务不能用无限body占用内存；HttpClient请求超时覆盖订阅完成。 */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();private Flow.Subscription subscription;private int received;private final int maximum;
        BoundedBody(int maximum){this.maximum=maximum;}
        public CompletionStage<byte[]> getBody(){return delegate.getBody();}
        public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;delegate.onSubscribe(subscription);}
        public void onNext(List<ByteBuffer> items){for(ByteBuffer item:items){received+=item.remaining();if(received>maximum){subscription.cancel();delegate.onError(unavailable());return;}}delegate.onNext(items);}
        public void onError(Throwable failure){delegate.onError(failure);}
        public void onComplete(){delegate.onComplete();}
    }
}
