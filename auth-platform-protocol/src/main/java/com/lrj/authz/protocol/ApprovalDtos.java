package com.lrj.authz.protocol;

import java.util.List;

/** auth与OA公开申请流程契约；OA不携带可替换Grant内容的回调。 */
public final class ApprovalDtos {
    public static final String DECIDED_EVENT = "ACCESS_REQUEST_DECIDED";

    private ApprovalDtos() {}

    /** 封闭回执编码；CONFLICT为传输隔离结果，不写入原事件状态。 */
    public enum Status {
        RECEIVED("RECEIVED"),
        APPLIED("APPLIED"),
        REJECTED("REJECTED"),
        IGNORED("IGNORED"),
        CONFLICT("CONFLICT");
        private final String code;

        Status(String code) {
            this.code = code;
        }

        /** 稳定协议编码，与枚举ordinal无关。 */
        public String code() {
            return code;
        }
    }

    /** 固定终态决定，不允许自由文本被解释成批准。 */
    public enum Outcome {
        APPROVED("APPROVED"),
        REJECTED("REJECTED");
        private final String code;

        Outcome(String code) {
            this.code = code;
        }

        /** 稳定协议编码。 */
        public String code() {
            return code;
        }
    }

    /** 固定快照只用字符串UTC跨版本传输；成员由OA显式身份桥解析。 */
    public record Start(
            String tenantId,
            String applicationId,
            String environment,
            String requestId,
            long requestVersion,
            String snapshotHash,
            String policyId,
            long policyVersion,
            String policyHash,
            String membershipId,
            long generation,
            String approverMembershipId,
            long approverGeneration,
            String roleId,
            List<String> capabilities,
            ScopeDtos.Rule scopeRule,
            String validFrom,
            String validTo,
            String reason) {
        /** 稳定业务键不包含投递尝试次数。 */
        public String businessKey() {
            return requestId + ":" + requestVersion;
        }
    }

    /** 按业务键查询也校验原快照，避免把错误实例绑定回来。 */
    public record Lookup(
            String tenantId,
            String applicationId,
            String environment,
            String requestId,
            long requestVersion,
            String snapshotHash) {}

    /** 实例已持久化不代表审批完成，更不代表授权ACTIVE。 */
    public record Instance(
            String requestId,
            long requestVersion,
            String snapshotHash,
            String approvalInstanceId) {}

    /** 决策只引用固定申请；实际审批人由OA审计证据映射，不允许携带新权限集合。 */
    public record Decision(
            String eventId,
            String eventType,
            int schemaVersion,
            String producer,
            String tenantId,
            String applicationId,
            String environment,
            String requestId,
            long requestVersion,
            String snapshotHash,
            String approvalInstanceId,
            String policyId,
            long policyVersion,
            long aggregateVersion,
            String outcome,
            String approverMembershipId,
            long approverGeneration,
            String decisionTime,
            String correlationId) {}

    /** RECEIVED仅表示已持久接收；APPLIED也不代表图投影ACTIVE。 */
    public record Receipt(String eventId, String status, String result) {}
}
