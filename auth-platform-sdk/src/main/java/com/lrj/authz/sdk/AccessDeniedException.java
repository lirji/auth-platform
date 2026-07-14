package com.lrj.authz.sdk;

/** 判权不通过时抛出。消费方可映射为 HTTP 403。 */
public class AccessDeniedException extends RuntimeException {
    public AccessDeniedException(String message) {
        super(message);
    }
}
