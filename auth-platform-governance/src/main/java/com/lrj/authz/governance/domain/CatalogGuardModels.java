package com.lrj.authz.governance.domain;

import com.lrj.authz.governance.domain.CatalogModels.*;
import com.lrj.authz.governance.domain.CatalogReleaseModels.*;

/** 固定发布候选与分区确认互相独立；预览ID不授予权限。 */
public final class CatalogGuardModels {
    private CatalogGuardModels() {}

    /** 仅确认显式分区，不声称全企业无影响。 */
    public record Impact(
            String tenantId, String applicationId, String environment, String basisHash) {}

    /** 新请求不在旧Manifest内追加发布来源或主体。 */
    public record PreviewInput(
            Manifest manifest, Source source, String reason, Decision decision, Impact impact) {
        public Candidate candidate() {
            return new Candidate(manifest, source, reason, decision);
        }
    }

    /** PG保存原候选与依据；有效期过后只能重读已成功原命令，不能新发布。 */
    public record Ticket(
            String previewId,
            String expiresAt,
            long baseVersion,
            String baseContentHash,
            String basePresentationHash,
            Candidate candidate,
            Preview preview,
            Impact impact) {}

    /** 正式发布只引用固定服务器预览，不接受替换候选或来源。 */
    public record PublishCommand(String commandId, String previewId) {}

    /** 退出旧写节点必须显式声明，生产目标仍需部署授权。 */
    public record Enable(
            String applicationId,
            String commandId,
            long expectedVersion,
            boolean legacyWritersExited,
            String reason) {}

    /** 缺省LEGACY；GUARDED单向启用，没有自动解除或程序回退降级。 */
    public enum Mode {
        LEGACY("LEGACY"),
        GUARDED("GUARDED");
        private final String code;

        Mode(String code) {
            this.code = code;
        }

        /** 对外状态使用固定code，不依赖Java序号。 */
        @com.fasterxml.jackson.annotation.JsonValue
        public String code() {
            return code;
        }
    }

    /** 启用事实自身是不可变审计记录，缺省版本0无发布人。 */
    public record Policy(
            String applicationId,
            Mode mode,
            long version,
            String enabledBy,
            String enabledAt,
            String reason,
            String commandId) {}
}
