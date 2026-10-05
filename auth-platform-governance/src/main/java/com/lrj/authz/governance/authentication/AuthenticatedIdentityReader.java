package com.lrj.authz.governance.authentication;

import com.lrj.authz.governance.application.IdentityGovernance;
import com.lrj.authz.governance.domain.IdentityModels.Membership;
import com.lrj.authz.governance.domain.IdentityModels.Principal;

import java.util.List;

/** 把发行方身份接到显式绑定，认证成功不会隐式开户、赋角色或绕过当前治理状态。 */
public final class AuthenticatedIdentityReader {
    private final CasdoorAccessTokenVerifier tokens;
    private final IdentityGovernance identity;

    /** 两种权威分别来自固定发行方与治理库，由所属服务显式装配。 */
    public AuthenticatedIdentityReader(
            CasdoorAccessTokenVerifier tokens, IdentityGovernance identity) {
        this.tokens = tokens;
        this.identity = identity;
    }

    /** 本人列表每次重新认证、重新查库；过期或停用成员不因旧 JWT 继续出现。 */
    public List<Membership> memberships(String accessToken) {
        VerifiedLogin login = tokens.verify(accessToken);
        return identity.membershipsForLogin(login.issuer(), login.subject());
    }

    /** 只根据已验证 issuer/sub 找 HUMAN，未知绑定显式拒绝并保持数据库不变。 */
    public Principal principal(String accessToken) {
        VerifiedLogin login = tokens.verify(accessToken);
        return identity.principalForLogin(login.issuer(), login.subject());
    }
}
