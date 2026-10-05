package com.lrj.authz.governance.catalog.domain;

import com.lrj.authz.governance.catalog.domain.CatalogModels.*;

import java.util.List;

/** 发布来源与旧清单分开，回执是固定历史事实，不表示业务授权已生效。 */
public final class CatalogReleaseModels {
    private CatalogReleaseModels() {}

    /** 原始声明制品的固定Git提交和SHA256；不是部署运行证明。 */
    public record Source(String commit, String artifactHash) {}

    /** 明确授权处理决定；目录发布从不自动改Grant。 */
    public enum Decision {
        KEEP_CURRENT_GRANTS("KEEP_CURRENT_GRANTS"),
        SEPARATE_AUTHORIZATION_REVIEW("SEPARATE_AUTHORIZATION_REVIEW");
        private final String code;

        Decision(String code) {
            this.code = code;
        }

        @com.fasterxml.jackson.annotation.JsonValue
        public String code() {
            return code;
        }

        /** 稳定协议值不依赖枚举序号。 */
        @com.fasterxml.jackson.annotation.JsonCreator
        public static Decision from(String code) {
            for (var value : values()) if (value.code.equals(code)) return value;
            throw new IllegalArgumentException("未知发布处理决定");
        }
    }

    /** 来源信封不能污染已发布v1 JSON或摘要。 */
    public record Candidate(Manifest manifest, Source source, String reason, Decision decision) {}

    /** 基础事实和原预览使更高版本存在后的重试仍能返回原回执。 */
    public record Release(
            String application,
            long version,
            String contentHash,
            String presentationHash,
            String publishedBy,
            String publishedAt,
            Source source,
            String reason,
            Decision decision,
            String commandId,
            Long baseVersion,
            String baseContentHash,
            String basePresentationHash,
            Preview preview) {}

    /** 老版本无来源仍在历史页里显示，不回填伪造的发布时间／提交。 */
    public record History(List<Release> items, Long nextBeforeVersion) {
        public History {
            items = List.copyOf(items);
        }
    }

    /** 固定历史版本的实际菜单同时包含原显示sidecar。 */
    public record Detail(Release release, Manifest manifest) {}
}
