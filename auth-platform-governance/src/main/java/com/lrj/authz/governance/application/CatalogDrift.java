package com.lrj.authz.governance.application;

import com.lrj.authz.governance.domain.CatalogDriftModels.*;
import com.lrj.authz.governance.domain.CatalogReleaseModels.*;
import java.time.Instant;
import java.util.*;

/** 有界纯比较与受信配置解析；没有HTTP抓取、发布或授权入口。 */
public final class CatalogDrift {
    private static final int MAX_DECLARATIONS=100;
    private CatalogDrift() {}
    /** 内容先于展示和来源判断；旧来源未知不能成为声明一致证明。 */
    public static State compare(long version,String content,String presentation,Source source,Release published) {
        if(published==null)return State.NOT_PUBLISHED;
        if(version!=published.version()||!Objects.equals(content,published.contentHash()))return State.SOURCE_MISMATCH;
        if(published.presentationHash()==null)return State.UNKNOWN;
        if(!Objects.equals(presentation,published.presentationHash()))return State.DISPLAY_MISMATCH;
        if(source==null||published.source()==null)return State.UNKNOWN;
        return source.equals(published.source())?State.MATCHED_DECLARATION:State.SOURCE_MISMATCH;
    }
    /** 只接受受控服务文件中的完整目标声明；不从请求中注册证据。 */
    public static List<DeploymentDeclaration> from(Properties properties) {
        try {
            int count=Integer.parseInt(properties.getProperty("catalog.deployment.count","0"));
            if(count<0||count>MAX_DECLARATIONS)throw new IllegalArgumentException();
            var result=new ArrayList<DeploymentDeclaration>();var applications=new HashSet<String>();
            for(int i=1;i<=count;i++) {
                String prefix="catalog.deployment."+i+".";
                var declaration=new DeploymentDeclaration(properties.getProperty(prefix+"application-id"),Long.parseLong(properties.getProperty(prefix+"manifest-version")),
                        properties.getProperty(prefix+"content-hash"),properties.getProperty(prefix+"presentation-hash"),
                        new Source(properties.getProperty(prefix+"commit"),properties.getProperty(prefix+"artifact-hash")),properties.getProperty(prefix+"declared-at"),properties.getProperty(prefix+"evidence-ref"));
                validate(declaration);if(!applications.add(declaration.applicationId()))throw new IllegalArgumentException();result.add(declaration);
            }
            // 配置漏写count不能默默忽略已登记的条目，避免把误配置冒充没有部署声明。
            if(properties.stringPropertyNames().stream().filter(key->key.startsWith("catalog.deployment.")&&!key.equals("catalog.deployment.count"))
                    .anyMatch(key->{var parts=key.split("\\.");try{return parts.length!=4||Integer.parseInt(parts[2])<1||Integer.parseInt(parts[2])>count||!Set.of("application-id","manifest-version","content-hash","presentation-hash","commit","artifact-hash","declared-at","evidence-ref").contains(parts[3]);}catch(NumberFormatException invalid){return true;}}))throw new IllegalArgumentException();
            return List.copyOf(result);
        } catch(RuntimeException invalid) { throw new IllegalArgumentException("部署声明配置无效"); }
    }
    /** 服务配置与调用方都不能通过任意地址扩展核验能力。 */
    public static void validate(DeploymentDeclaration d) {
        if(d==null)throw new IllegalArgumentException();CatalogManifest.code(d.applicationId());
        if(d.manifestVersion()<1||d.contentHash()==null||!d.contentHash().matches("[a-f0-9]{64}")||d.presentationHash()==null||!d.presentationHash().matches("[a-f0-9]{64}")
                ||d.source()==null||d.source().commit()==null||!d.source().commit().matches("(?:[a-f0-9]{40}|[a-f0-9]{64})")||d.source().artifactHash()==null||!d.source().artifactHash().matches("[a-f0-9]{64}"))throw new IllegalArgumentException();
        Instant.parse(d.declaredAt());BootstrapCommand.bounded(d.evidenceRef(),200);
    }
}
