package com.lrj.authz.governance.application;

/** 治理边界稳定错误；消息仅为代码，不向调用方暴露 SQL、身份凭据或内部堆栈。 */
public final class GovernanceException extends RuntimeException {
    /** 当前治理契约错误，与旧 SpiceDB 协议异常保持各自语义。 */
    public enum Code {
        INVALID_ARGUMENT("INVALID_ARGUMENT"),
        NOT_FOUND("NOT_FOUND"),
        SCOPE_UNSUPPORTED("SCOPE_UNSUPPORTED"),
        AUTHZ_STATE_NOT_READY("AUTHZ_STATE_NOT_READY"),
        AUTHZ_PROTOCOL_INVALID("AUTHZ_PROTOCOL_INVALID"),
        ACCESS_DENIED("ACCESS_DENIED"),
        INVALID_CREDENTIAL("INVALID_CREDENTIAL"),
        IDENTITY_NOT_BOUND("IDENTITY_NOT_BOUND"),
        MEMBERSHIP_UNAVAILABLE("MEMBERSHIP_UNAVAILABLE"),
        GENERATION_MISMATCH("GENERATION_MISMATCH"),
        BINDING_CONFLICT("BINDING_CONFLICT"),
        COMMAND_CONFLICT("COMMAND_CONFLICT"),
        VERSION_CONFLICT("VERSION_CONFLICT"),
        CAPABILITY_DEPRECATED("CAPABILITY_DEPRECATED"),
        RETIREMENT_BLOCKED("RETIREMENT_BLOCKED"),
        DEPENDENCY_UNAVAILABLE("DEPENDENCY_UNAVAILABLE");
        private final String value;
        Code(String value) { this.value = value; }
        /** 对外稳定编码，不依赖 Java 枚举名称。 */
        public String value() { return value; }
    }

    private final Code code;

    /** 创建不会回显原始输入的业务错误。 */
    public GovernanceException(Code code) {
        super(code.value());
        this.code = code;
    }

    /** 边界仅序列化代码，内部异常由受控诊断渠道处理。 */
    public Code code() { return code; }
}
