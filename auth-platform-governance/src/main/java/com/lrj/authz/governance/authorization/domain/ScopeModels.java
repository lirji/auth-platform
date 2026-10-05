package com.lrj.authz.governance.authorization.domain;

import com.lrj.authz.governance.projection.domain.FenceModels;
import com.lrj.authz.protocol.ScopeDtos.Alternative;

import java.time.Instant;
import java.util.List;

/** 一条SQL保留Grant与角色、范围的关联，避免逐角色/范围N+1。 */
public final class ScopeModels {
    private ScopeModels() {}

    /** 仅当前有效Grant，固定角色与范围来自同一租户应用环境连接。 */
    public record GrantPath(
            String id,
            long version,
            String capabilitiesJson,
            String scope,
            String ruleJson,
            Instant validTo) {}

    /** 只在本次后端请求中消费，不是浏览器可复用的授权凭据。 */
    public record Evaluation(
            String decisionId,
            FenceModels.Stamp stamp,
            List<Alternative> alternatives,
            Instant validUntil) {
        /** 在不可变契约构造边界沿用既有字段校验、集合快照或默认值，保持各创建入口语义一致。 */
        public Evaluation {
            alternatives = List.copyOf(alternatives);
        }
    }
}
