package com.lrj.authz.governance.web;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.domain.AccessModels.*;
import com.lrj.authz.protocol.AccessDtos.*;
import java.io.InputStream;
import java.time.Instant;

/** RBAC专用严格JSON边界，不改变旧接口的序列化配置。 */
public final class AccessWeb {
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    private AccessWeb() {}
    /** body有界且不允许请求伪造principal、actor或额外策略字段。 */
    public static <T>T read(InputStream input,Class<T> type){try{byte[] bytes=input.readNBytes(16385);if(bytes.length>16384)throw invalid();T value=JSON.readValue(bytes,type);if(value==null)throw invalid();return value;}catch(Exception e){throw invalid();}}
    /** 固定UTC格式，禁止默认时区和模糊本地时间。 */
    public static Instant instant(String value){try{if(value==null||!value.endsWith("Z"))throw invalid();return Instant.parse(value);}catch(Exception e){throw invalid();}}
    /** 领域权限字符串投影成公开数组。 */
    public static RoleView role(RoleVersion r){return new RoleView(r.id(),r.roleCode(),r.version(),AccessValues.read(r.capabilitiesJson()));}
    /** 水位只在平台内部使用，不将其误作授权票据发给浏览器。 */
    public static GrantView grant(Grant g){return new GrantView(g.id(),g.membershipId(),g.generation(),g.roleId(),g.scope(),g.sourceType(),g.sourceId(),g.validFrom().toString(),g.validTo().toString(),g.state().code(),g.version());}
    /** 稳定有界公开查询结果。 */
    public static StateView state(State s){return new StateView(s.roles().stream().map(AccessWeb::role).toList(),s.grants().stream().map(AccessWeb::grant).toList(),s.nextRoleCursor(),s.nextGrantCursor());}
    private static GovernanceException invalid(){return new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);}
}
