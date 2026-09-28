package com.lrj.authz.governance.application;

/** 治理边界稳定错误；消息仅为代码，不向调用方暴露 SQL、身份凭据或内部堆栈。 */
public final class GovernanceException extends RuntimeException {
    /** 当前治理契约错误，与旧 SpiceDB 协议异常保持各自语义。 */
    public enum Code {
        INVALID_ARGUMENT, INVALID_CREDENTIAL, IDENTITY_NOT_BOUND, MEMBERSHIP_UNAVAILABLE,
        GENERATION_MISMATCH, BINDING_CONFLICT, COMMAND_CONFLICT, VERSION_CONFLICT,
        DEPENDENCY_UNAVAILABLE
    }

    private final Code code;

    /** 创建不会回显原始输入的业务错误。 */
    public GovernanceException(Code code) {
        super(code.name());
        this.code = code;
    }

    /** 边界仅序列化代码，内部异常由受控诊断渠道处理。 */
    public Code code() { return code; }
}
