package com.lrj.authz.governance.authentication;

/** 认证边界产出的精确登录键，不携带 Token、Casdoor isAdmin 或用户自填主体。 */
public record VerifiedLogin(String issuer, String subject) {}
