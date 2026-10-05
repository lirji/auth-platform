package com.lrj.authz.protocol;

import java.util.List;

/** 业务本人导航提示；既不是管理目录，也不是可重放的业务执行凭据。 */
public final class NavigationDtos {
    public static final int MAX_MENUS = 100;
    public static final int MAX_CAPABILITIES = 200;
    public static final int MAX_RESPONSE_BYTES = 131072;

    private NavigationDtos() {}

    /** 固定线协议编码，未知状态拒绝；不使用枚举ordinal承载协议。 */
    public enum State {
        AVAILABLE("AVAILABLE"),
        NO_ACCESS("NO_ACCESS");
        private final String code;

        State(String code) {
            this.code = code;
        }

        /** DTO序列化只输出显式code，保留纯Java协议模块零运行依赖。 */
        public String code() {
            return code;
        }

        /** 新未知状态不会被旧调用方误认为可用。 */
        public static State from(String value) {
            for (State state : values()) if (state.code.equals(value)) return state;
            throw new IllegalArgumentException("invalid navigation state");
        }
    }

    /** 主体、应用和环境由双身份解析，正文只能选择本人组织及预期代际。 */
    public record Request(String tenantId, Long expectedMembershipGeneration, String requestId) {}

    /** 祖先可以只有组织作用，route为空不会开放其页面。 */
    public record Menu(String code, String parent, String route, String label, Integer position) {}

    /** 每次请求的固定目录依据；不输出其他人的角色、来源或授权范围。 */
    public record View(
            String schemaVersion,
            String requestId,
            GovernanceDtos.AccessContext context,
            long manifestVersion,
            String contentHash,
            String presentationHash,
            String observedAt,
            String state,
            List<Menu> menus,
            List<String> capabilityHints) {
        /** 在不可变契约构造边界沿用既有字段校验、集合快照或默认值，保持各创建入口语义一致。 */
        public View {
            menus = menus == null ? null : List.copyOf(menus);
            capabilityHints = capabilityHints == null ? null : List.copyOf(capabilityHints);
        }
    }
}
