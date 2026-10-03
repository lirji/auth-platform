package com.lrj.authz.governance.domain;

import com.lrj.authz.governance.domain.CatalogModels.Preview;
import com.lrj.authz.governance.domain.CatalogReleaseModels.*;

/** 声明一致与运行未知显式分开，漂移诊断不产生任何授权副作用。 */
public final class CatalogDriftModels {
    private CatalogDriftModels() {}
    /** 固定协议值仅描述核对层次，MATCHED_DECLARATION不是实际运行证明。 */
    public enum State {
        NOT_PUBLISHED("NOT_PUBLISHED"), SOURCE_MISMATCH("SOURCE_MISMATCH"), DISPLAY_MISMATCH("DISPLAY_MISMATCH"), MATCHED_DECLARATION("MATCHED_DECLARATION"), UNKNOWN("UNKNOWN");
        private final String code; State(String code){this.code=code;}
        /** 对外code不依赖Java序号。 */
        @com.fasterxml.jackson.annotation.JsonValue public String code(){return code;}
    }
    /** 仅服务配置可登记部署声明，证据引用没有网络抓取能力。 */
    public record DeploymentDeclaration(String applicationId,long manifestVersion,String contentHash,String presentationHash,Source source,String declaredAt,String evidenceRef) {}
    /** 未登记时state为UNKNOWN，不能伪造已部署版本。 */
    public record Deployment(State state,DeploymentDeclaration declaration) {}
    /** 本次核验时点和当前固定发布可追溯，无后台最后核验历史的假象。 */
    public record Report(String application,String observedAt,long expectedVersion,String contentHash,String presentationHash,Source declaredSource,
                         Release published,State state,Preview diff,Deployment deployment,State runtimeState) {}
}
