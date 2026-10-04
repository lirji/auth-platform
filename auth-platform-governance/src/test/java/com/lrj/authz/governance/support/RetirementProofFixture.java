package com.lrj.authz.governance.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.authz.governance.application.CatalogRetirementProof;
import com.lrj.authz.protocol.CapabilityRetirementDtos.Report;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** 独立核验器签名契约夹具，不冒充生产部署或真实接口扫描。 */
public final class RetirementProofFixture {
    private final KeyPair keys;
    public final Path file;
    private final String application;
    /** 私钥只在测试进程内；不进入请求、日志或正式源码配置。 */
    public RetirementProofFixture(Path directory,String application)throws Exception{
        this.application=application;keys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();file=directory.resolve(UUID.randomUUID()+".json");
    }
    /** 固定两个目标用来验证目标覆盖，不能以一个目标的零引用代替全部。 */
    public CatalogRetirementProof reader(){return new CatalogRetirementProof(List.of(new CatalogRetirementProof.Authority(application,Set.of("api-a","api-b"),file,keys.getPublic())));}
    /** 签名完整当前依据；测试可修改payload再重新签名验证业务拒绝。 */
    public Map<String,Object> payload(Report r){
        var n=new LinkedHashMap<String,Object>();n.put("schema_version",1);n.put("application_id",r.applicationId());n.put("capability",r.capability());n.put("manifest_version",r.manifestVersion());n.put("content_hash",r.contentHash());n.put("presentation_hash",r.presentationHash());n.put("lifecycle_version",r.lifecycleVersion());n.put("checked_at",Instant.now().minusSeconds(1).toString());n.put("valid_until",Instant.now().plusSeconds(60).toString());
        n.put("deployments",List.of(target("api-a"),target("api-b")));return n;
    }
    /** 只是测试制品标识，明确与运行核验结果区分。 */
    private Map<String,Object> target(String id){return new LinkedHashMap<>(Map.of("target_id",id,"commit","a".repeat(40),"artifact_hash","b".repeat(64),"api_mapping_hash","c".repeat(64),"complete",true,"capability_references",0));}
    /** 签名原字节并保持0600，非法签名场景直接修改该夹具文件。 */
    public void write(Map<String,Object> n)throws Exception{
        var json=new ObjectMapper();byte[] payload=json.writeValueAsBytes(n);var s=Signature.getInstance("Ed25519");s.initSign(keys.getPrivate());s.update(payload);
        Files.write(file,json.writeValueAsBytes(Map.of("payload",Base64.getEncoder().encodeToString(payload),"signature",Base64.getEncoder().encodeToString(s.sign()))));Files.setPosixFilePermissions(file,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    }
    /** 测试宿主0600配置，仅公钥与固定文件路径可传给Auth。 */
    public Properties properties(){var p=new Properties();p.setProperty("catalog.retirement.count","1");p.setProperty("catalog.retirement.1.application-id",application);p.setProperty("catalog.retirement.1.deployment-targets","api-a,api-b");p.setProperty("catalog.retirement.1.proof-file",file.toString());p.setProperty("catalog.retirement.1.public-key",Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));return p;}
}
