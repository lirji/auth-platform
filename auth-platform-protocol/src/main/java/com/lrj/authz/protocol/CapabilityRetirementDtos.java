package com.lrj.authz.protocol;

import java.util.List;

/** 正常退役只接受当前引用依据，浏览器不能提交或认证运行证明。 */
public final class CapabilityRetirementDtos {
    private CapabilityRetirementDtos() {}

    /** 运行证明是封闭协议集合，未知状态不能解释为退出成功。 */
    public enum ProofState {
        PROVEN("PROVEN"),
        UNPROVEN("UNPROVEN");
        private final String code;

        ProofState(String code) {
            this.code = code;
        }

        /** 对外稳定值，不依赖ordinal。 */
        public String code() {
            return code;
        }
    }

    /** Owner仅取得自身应用目录，不包含成员或委派读取资格。 */
    public record OwnerCatalog(
            String applicationId,
            long manifestVersion,
            List<PortalDtos.PublishedCapability> capabilities) {
        public OwnerCatalog {
            capabilities = List.copyOf(capabilities);
        }
    }

    /** 原命令固定当前全应用引用，不因重试重新选择能力。 */
    public record Retire(
            String applicationId,
            String capability,
            Long expectedVersion,
            String basisHash,
            String reason,
            String commandId) {}

    /** Owner摘要不包含成员标识、审批人或原范围。 */
    public record Count(String kind, long blocking, long historical) {}

    /** 未接入受信运行核验时UNPROVEN，不能把null当成零引用。 */
    public record Report(
            String applicationId,
            String capability,
            CapabilityLifecycleDtos.State lifecycleState,
            long lifecycleVersion,
            long manifestVersion,
            String contentHash,
            String presentationHash,
            boolean complete,
            List<Count> counts,
            String proofState,
            String proofReason,
            String proofHash,
            String proofValidUntil,
            boolean eligible,
            String basisHash,
            String checkedAt) {
        public Report {
            counts = List.copyOf(counts);
        }
    }

    /** 仅独立诊断授权可见的有界引用，不传播人员敏感范围或Token。 */
    public record Reference(
            String kind,
            String id,
            boolean blocking,
            String state,
            String roleId,
            String sourceType,
            String validTo) {}

    /** 游标同时绑定完整依据；其他企业数据不进入本页。 */
    public record References(List<Reference> items, String nextCursor, String basisHash) {
        public References {
            items = List.copyOf(items);
        }
    }
}
