package com.lrj.authz.governance.catalog.domain;

import com.lrj.authz.governance.catalog.domain.CatalogGuardModels.Ticket;
import com.lrj.authz.governance.catalog.domain.CatalogModels.*;
import com.lrj.authz.governance.catalog.domain.CatalogReleaseModels.*;

import java.util.List;

/** 机器发布只描述目录和明确的SERVICE委派，不承载人员或业务授权。 */
public final class CatalogPublisherModels {
    private CatalogPublisherModels() {}

    /** 实例目标由受控初始化绑定；请求只能确认，不能重绑数据库。 */
    public record Target(String targetInstanceId, String environment) {}

    /** 委派只允许单向禁用，轮换建立新客户端及委派。 */
    public enum Status {
        ACTIVE("ACTIVE"),
        DISABLED("DISABLED");
        private final String code;

        Status(String code) {
            this.code = code;
        }

        @com.fasterxml.jackson.annotation.JsonValue
        public String code() {
            return code;
        }

        /** 稳定code支持存储与接口，不采用ordinal。 */
        @com.fasterxml.jackson.annotation.JsonCreator
        public static Status from(String code) {
            for (var status : values()) if (status.code.equals(code)) return status;
            throw new IllegalArgumentException("未知发布委派状态");
        }
    }

    /** 风险报告不等于允许执行；只有非语义变化可取得机器票据。 */
    public enum Eligibility {
        AUTOMATION_ALLOWED("AUTOMATION_ALLOWED"),
        REQUIRES_OWNER_REVIEW("REQUIRES_OWNER_REVIEW");
        private final String code;

        Eligibility(String code) {
            this.code = code;
        }

        @com.fasterxml.jackson.annotation.JsonValue
        public String code() {
            return code;
        }
    }

    /** 当前Owner只能选择受控slot，不接收调用者提交的发行方或服务密钥。 */
    public record Create(
            String applicationId,
            String publisherId,
            String commandId,
            String validUntil,
            String reason) {}

    /** expectedVersion防止旧操作误禁用新状态；原命令事件保持不变。 */
    public record Disable(
            String applicationId,
            String delegationId,
            long expectedVersion,
            String commandId,
            String reason) {}

    /** 委派元数据不含凭据，原命令回执是历史事实，不代表当前资格。 */
    public record Delegation(
            String delegationId,
            String publisherId,
            String applicationId,
            String targetInstanceId,
            String environment,
            String servicePrincipal,
            String ownerPrincipal,
            Status status,
            long version,
            String validUntil,
            String commandId,
            String reason,
            String createdAt,
            String disabledAt) {}

    /** 列表有界；不能把第一页当作完整委派清单。 */
    public record Delegations(List<Delegation> items) {
        public Delegations {
            items = List.copyOf(items);
        }
    }

    /** 机器不能提交人类授权处理决定或诊断报告。 */
    public record PreviewInput(
            String targetInstanceId,
            String environment,
            Manifest manifest,
            Source source,
            String reason) {
        /** 来源是固定声明，风险处理决定始终为空。 */
        public Candidate candidate() {
            return new Candidate(manifest, source, reason, null);
        }
    }

    /** 候选已固定在服务器，发布不能替换来源或内容。 */
    public record Publish(
            String targetInstanceId, String environment, String commandId, String previewId) {}

    /** 未知结果只查询原命令，不能读取Owner或别的机器的发布。 */
    public record ReceiptQuery(String targetInstanceId, String environment, String commandId) {}

    /** ticket为空必须由真实Owner审查；不提供虚假的全人员影响结果。 */
    public record Report(
            Target target,
            String publisherId,
            String delegationId,
            String candidateHash,
            Eligibility eligibility,
            Preview preview,
            Ticket ticket) {}

    /** 实际机器主体与人类Owner明确分开，历史不可伪装成Owner亲自发布。 */
    public record Actor(
            String kind,
            String servicePrincipal,
            String ownerPrincipal,
            String delegationId,
            String publisherId,
            String targetInstanceId,
            String environment) {}

    /** 原发布回执及actor同事务固定，不包含当前授权推断。 */
    public record Receipt(Release release, Actor actor) {}
}
