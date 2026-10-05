package com.lrj.authz.sdk;

/** 中央鉴权认证/依赖/协议故障；不能捕获后改走旧ALLOW路径。 */
public final class CentralAccessException extends RuntimeException {
    private final int status;

    /** 只存稳定状态，不把用户Token、响应原文或后端地址写入异常。 */
    public CentralAccessException(int status) {
        super(
                status == org.springframework.http.HttpStatus.UNAUTHORIZED.value()
                        ? "CENTRAL_INVALID_CREDENTIAL"
                        : "CENTRAL_ACCESS_UNAVAILABLE");
        this.status = status;
    }

    /** 消费方可将认证失败映射401，其余依赖/协议故障映射503。 */
    public int status() {
        return status;
    }
}
