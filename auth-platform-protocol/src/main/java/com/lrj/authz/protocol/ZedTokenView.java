package com.lrj.authz.protocol;

/** 写操作返回的一致性水位 (SpiceDB ZedToken)。可回传给后续判权做 AT_LEAST_AS_FRESH。 */
public record ZedTokenView(String token) {
}
