package com.lrj.authz.governance.identity.authentication;

import static com.lrj.authz.governance.shared.application.GovernanceException.Code.*;

import com.lrj.authz.governance.shared.application.GovernanceException;
import com.nimbusds.jwt.SignedJWT;

import java.text.ParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 有界受控客户端集合；未验签声明仅用于查固定配置，永远不提供认证或新网络目标。 */
public final class CasdoorMachineTokens {
    private static final int MAX_AUTHORITIES = 16;
    private static final int MAX_TOKEN_BYTES = 32_768;
    private final Map<Key, Entry> entries;

    /** 机器认证缓存键绑定原可信槽位与令牌身份，不允许不同发行方或机器主体复用认证结果。 */
    private record Key(String issuer, String clientId) {}

    /** 保存已验证机器认证的有界缓存条目；有效期与来源变化按原规则核对，不缓存执行授权。 */
    private record Entry(MachineTokenAuthority authority, CasdoorAccessTokenVerifier verifier) {}

    /** 开启机器入口时核对固定发行方契约，不自动登记SERVICE或发布目录。 */
    public CasdoorMachineTokens(List<MachineTokenAuthority> authorities) {
        if (authorities == null || authorities.isEmpty() || authorities.size() > MAX_AUTHORITIES) {
            throw new GovernanceException(INVALID_ARGUMENT);
        }
        Map<Key, MachineTokenAuthority> unique = new HashMap<>();
        var publisherIds = new java.util.HashSet<String>();
        for (var authority : authorities) {
            if (authority == null
                    || !publisherIds.add(authority.publisherId())
                    || unique.putIfAbsent(
                                    new Key(
                                            authority.tokenAuthority().issuer(),
                                            authority.tokenAuthority().clientId()),
                                    authority)
                            != null) {
                throw new GovernanceException(INVALID_ARGUMENT);
            }
        }
        Map<Key, Entry> configured = new HashMap<>();
        unique.forEach(
                (key, authority) ->
                        configured.put(
                                key,
                                new Entry(
                                        authority,
                                        new CasdoorAccessTokenVerifier(
                                                authority.tokenAuthority()))));
        entries = Map.copyOf(configured);
    }

    /** 单一issuer/audience提示只能命中已装配客户端；最终身份必须再次验签和实时核验。 */
    public VerifiedMachine verify(String token) {
        if (token == null
                || token.isBlank()
                || token.length() > MAX_TOKEN_BYTES
                || token.chars().anyMatch(Character::isWhitespace)) {
            throw new GovernanceException(INVALID_CREDENTIAL);
        }
        try {
            var hint = SignedJWT.parse(token).getJWTClaimsSet();
            if (hint.getAudience() == null || hint.getAudience().size() != 1) {
                throw new GovernanceException(INVALID_CREDENTIAL);
            }
            Entry entry = entries.get(new Key(hint.getIssuer(), hint.getAudience().getFirst()));
            if (entry == null) {
                throw new GovernanceException(INVALID_CREDENTIAL);
            }
            return entry.verifier()
                    .verifyMachine(
                            token, entry.authority().publisherId(), entry.authority().subject());
        } catch (ParseException | IllegalArgumentException failure) {
            throw new GovernanceException(INVALID_CREDENTIAL);
        }
    }
}
