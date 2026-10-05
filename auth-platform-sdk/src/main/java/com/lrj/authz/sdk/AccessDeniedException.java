package com.lrj.authz.sdk;

/** 判权不通过时抛出。消费方可映射为 HTTP 403。 */
public class AccessDeniedException extends RuntimeException {
    /** 保留原异常状态与错误语义，边界处理不能把拒绝和依赖故障混为成功。 */
    public AccessDeniedException(String message) {
        super(message);
    }
}
