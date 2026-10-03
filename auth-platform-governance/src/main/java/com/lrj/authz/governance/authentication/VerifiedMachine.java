package com.lrj.authz.governance.authentication;

import java.time.Instant;

/** 已验签且实时核验的机器事实；不携带原Token，也不能传入HUMAN治理接口。 */
public record VerifiedMachine(String publisherId, String issuer, String subject, String clientId,
                              Instant issuedAt, Instant expiresAt) {}
