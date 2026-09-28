package com.lrj.authz.governance.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.authz.governance.domain.AccessModels.Partition;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.lrj.authz.governance.application.GovernanceException.Code.INVALID_ARGUMENT;

/** 授权值边界；能力集合只允许稳定编码，不执行表达式。 */
public final class AccessValues {
    private static final ObjectMapper JSON=new ObjectMapper();
    private AccessValues() {}
    /** 隔离分区字段严格校验，后续仍须匹配可信上下文。 */
    public static void partition(Partition p){
        if(p==null)throw new GovernanceException(INVALID_ARGUMENT);
        BootstrapCommand.uuid(p.tenantId());CatalogManifest.code(p.applicationId());
        if(p.environment()==null||!p.environment().matches("[a-z0-9][a-z0-9-]{0,39}"))throw new GovernanceException(INVALID_ARGUMENT);
    }
    /** 能力集合去歧义并排序，重复值不是隐式去重。 */
    public static List<String> capabilities(List<String> values){
        if(values==null||values.isEmpty()||values.size()>200||new HashSet<>(values).size()!=values.size())throw new GovernanceException(INVALID_ARGUMENT);
        values.forEach(CatalogManifest::code);return values.stream().sorted().toList();
    }
    /** 数据库存储为确定性JSON数组，不拼接SQL。 */
    public static String json(List<String> values){try{return JSON.writeValueAsString(capabilities(values));}catch(Exception e){throw new GovernanceException(INVALID_ARGUMENT);}}
    /** 损坏的数据库能力集合不被默认为全权限。 */
    public static List<String> read(String json){try{return capabilities(Arrays.asList(JSON.readValue(json,String[].class)));}catch(Exception e){throw new GovernanceException(INVALID_ARGUMENT);}}
    /** 长度前缀摘要避免分隔符碰撞，并绑定所有命令语义。 */
    public static String hash(Object... fields){
        try{var digest=MessageDigest.getInstance("SHA-256");for(Object field:fields){String s=String.valueOf(field);digest.update((s.length()+":"+s).getBytes(StandardCharsets.UTF_8));}return HexFormat.of().formatHex(digest.digest());}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException("JVM缺少SHA-256");}
    }
}
