package com.lrj.authz.governance.application;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.cfg.*;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.lrj.authz.governance.domain.CatalogModels.Preview;
import com.lrj.authz.governance.domain.CatalogReleaseModels.*;
import com.lrj.authz.governance.domain.CatalogGuardModels.*;
import java.io.InputStream;
import static com.lrj.authz.governance.application.GovernanceException.Code.*;

/** 发布信封和原回执的严格编码；不修改旧Manifest历史JSON。 */
public final class CatalogPublication {
    private static final int MAX_REQUEST_BYTES=141312, MAX_MANIFEST_BYTES=131072;
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    static {
        for(var shape: java.util.List.of(CoercionInputShape.Integer,CoercionInputShape.Float,CoercionInputShape.Boolean))
            JSON.coercionConfigFor(LogicalType.Textual).setCoercion(shape,CoercionAction.Fail);
    }
    private CatalogPublication() {}
    /** 有界读入后重验来源，未知主体／自动授予字段拒绝。 */
    public static Candidate read(InputStream input) {
        try {
            byte[] bytes=input.readNBytes(MAX_REQUEST_BYTES+1); if(bytes.length>MAX_REQUEST_BYTES) throw invalid();
            var value=JSON.readValue(bytes,Candidate.class);
            if(value==null||value.manifest()==null||JSON.writeValueAsBytes(value.manifest()).length>MAX_MANIFEST_BYTES)throw invalid();
            return normalize(value);
        } catch(java.io.IOException|IllegalArgumentException failure) { throw invalid(); }
    }
    /** 固定预览／发布命令沿用同一严格信封预算，不接受客户端主体或期限。 */
    public static <T> T read(InputStream input,Class<T> type) {
        try {
            byte[] bytes=input.readNBytes(MAX_REQUEST_BYTES+1); if(bytes.length>MAX_REQUEST_BYTES)throw invalid();
            T value=JSON.readValue(bytes,type); if(value==null)throw invalid(); return value;
        } catch(java.io.IOException|IllegalArgumentException failure) { throw invalid(); }
    }
    /** 服务器票据只写规范化候选；紧凑Manifest保持原128KiB预算。 */
    public static String candidateJson(Candidate input) {
        var candidate=normalize(input);
        try {
            if(JSON.writeValueAsBytes(candidate.manifest()).length>MAX_MANIFEST_BYTES)throw invalid();
            return JSON.writeValueAsString(candidate);
        } catch(JsonProcessingException failure) { throw invalid(); }
    }
    /** 数据库候选损坏表示依赖失败，不变成空清单或任意调用方内容。 */
    public static Candidate candidate(String json) {
        try { return normalize(JSON.readValue(json,Candidate.class)); }
        catch(JsonProcessingException|IllegalArgumentException|GovernanceException failure) { throw new GovernanceException(DEPENDENCY_UNAVAILABLE); }
    }
    /** 分区影响只接受明确COMPLETE摘要，ID本身不是诊断授权。 */
    public static Impact impact(String json) {
        if(json==null)return null;
        try { var value=JSON.readValue(json,Impact.class); validateImpact(value); return value; }
        catch(JsonProcessingException|IllegalArgumentException|GovernanceException failure) { throw new GovernanceException(DEPENDENCY_UNAVAILABLE); }
    }
    /** 当前分区和64位依据不能为空或使用通配。 */
    public static void validateImpact(Impact value) {
        if(value==null)throw invalid();
        AccessValues.partition(new com.lrj.authz.governance.domain.AccessModels.Partition(value.tenantId(),value.applicationId(),value.environment()));
        if(value.basisHash()==null||!value.basisHash().matches("[a-f0-9]{64}"))throw invalid();
    }
    /** 仅编码已经验证的分区，不在存储中拼入任意SQL或地址。 */
    public static String impactJson(Impact value) {
        if(value==null)return null; validateImpact(value);
        try { return JSON.writeValueAsString(value); } catch(JsonProcessingException failure) { throw invalid(); }
    }
    /** 入口扩大或权限映射变化需显式业务原因与授权处理决定。 */
    public static boolean needsDecision(com.lrj.authz.governance.domain.CatalogModels.Preview preview,java.util.Set<String> existingCapabilities) {
        return preview.menuChanges().stream().anyMatch(change -> switch(change.kind()) {
            case CHANGED -> change.fields().contains("route")||change.fields().contains("any_of");
            case REMOVED -> change.before().route()!=null||!change.before().anyOf().isEmpty();
            case ADDED -> change.after().anyOf().stream().anyMatch(existingCapabilities::contains);
        });
    }
    /** 发布路径和CLI使用同一来源／原因边界，不能只靠页面验证。 */
    public static Candidate normalize(Candidate c) {
        if(c==null)throw invalid(); var manifest=CatalogManifest.normalize(c.manifest());
        if(c.source()!=null && (c.source().commit()==null||!c.source().commit().matches("(?:[a-f0-9]{40}|[a-f0-9]{64})")
                ||c.source().artifactHash()==null||!c.source().artifactHash().matches("[a-f0-9]{64}")))throw invalid();
        if(c.reason()!=null)BootstrapCommand.bounded(c.reason(),500);
        if(c.decision()!=null&&c.reason()==null)throw invalid();
        return new Candidate(manifest,c.source(),c.reason(),c.decision());
    }
    /** 所有命令语义均绑定，输入列表顺序归一化，显示变化不被旧权限摘要漏掉。 */
    public static String hash(Candidate c) {
        return AccessValues.hash(CatalogManifest.hash(c.manifest()),CatalogManifest.presentationHash(c.manifest()),
                c.source()==null?null:c.source().commit(),c.source()==null?null:c.source().artifactHash(),c.reason(),c.decision()==null?null:c.decision().code());
    }
    /** 原预览写入同事务历史，读取不重新计算当前目录差异。 */
    public static String previewJson(Preview preview) {
        try { return JSON.writeValueAsString(preview); } catch(JsonProcessingException failure) { throw new GovernanceException(DEPENDENCY_UNAVAILABLE); }
    }
    /** 损坏的原回执不能返回伪成功。 */
    public static Preview preview(String json) {
        try { var value=JSON.readValue(json,Preview.class); if(value==null)throw new IllegalArgumentException(); return value; }
        catch(JsonProcessingException|IllegalArgumentException failure) { throw new GovernanceException(DEPENDENCY_UNAVAILABLE); }
    }
    private static GovernanceException invalid() { return new GovernanceException(INVALID_ARGUMENT); }
}
