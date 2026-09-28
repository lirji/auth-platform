package com.lrj.authz.governance.application;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import static com.lrj.authz.governance.application.GovernanceException.Code.INVALID_ARGUMENT;

/** 已认证操作者与命令的不可变组合；HTTP/文件命令不能覆盖受控配置中的身份和范围。 */
public record LifecycleCommand(String commandId, String operatorRef, String scope, Operation operation,
                               String targetId, long expectedVersion, String reason) {
    public static final String GLOBAL_SCOPE = "GLOBAL";

    /** 收紧与受控外部退出分开；员工离职归目录源，恢复不能伪装成重新加入。 */
    public enum Operation {
        SUSPEND_MEMBER("SUSPEND_MEMBERSHIP", "suspend-member"),
        SUSPEND_PRINCIPAL("SUSPEND_PRINCIPAL", "suspend-principal"),
        LEAVE_EXTERNAL_MEMBER("LEAVE_EXTERNAL_MEMBER", "leave-external-member");
        private final String code;
        private final String cli;
        Operation(String code, String cli) { this.code = code; this.cli = cli; }
        /** 审计/幂等协议使用稳定编码。 */
        public String code() { return code; }
        /** 命令名称只接受闭合集合。 */
        public static Operation fromCli(String value) {
            for (Operation candidate : values()) { if (candidate.cli.equals(value)) { return candidate; } }
            throw new GovernanceException(INVALID_ARGUMENT);
        }
    }

    /** 范围在进入事务之前校验；全局能力不能隐式转换为任意企业写入。 */
    public LifecycleCommand {
        BootstrapCommand.uuid(commandId); BootstrapCommand.uuid(targetId);
        BootstrapCommand.bounded(operatorRef, 160); BootstrapCommand.bounded(reason, 1000);
        if (operation == null || expectedVersion < 1 || expectedVersion == Long.MAX_VALUE) { throw new GovernanceException(INVALID_ARGUMENT); }
        if (operation == Operation.SUSPEND_PRINCIPAL) {
            if (!GLOBAL_SCOPE.equals(scope)) { throw new GovernanceException(INVALID_ARGUMENT); }
        } else { BootstrapCommand.uuid(scope); }
    }

    /** 长度前缀编码防止输入字段边界歧义，原因与预期版本同样参与防重放摘要。 */
    public String payloadHash() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                for (String value : new String[]{operatorRef, scope, operation.code(), targetId, Long.toString(expectedVersion), reason}) {
                    output.writeUTF(value);
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException failure) { throw new IllegalStateException("生命周期命令摘要不可用", failure); }
    }
}
