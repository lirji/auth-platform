package com.lrj.authz.protocol;

/**
 * 一致性策略, 直译 SpiceDB 的一致性模型。
 * <ul>
 *   <li>MINIMIZE_LATENCY — 最快, 允许轻微陈旧 (默认判权)。</li>
 *   <li>AT_LEAST_AS_FRESH — 读到不早于给定 ZedToken 的快照 (read-after-write, 撤权立即生效)。</li>
 *   <li>FULLY_CONSISTENT — 最新但最慢 (强一致校验)。</li>
 * </ul>
 */
public record Consistency(Mode mode, String zedToken) {
    /** 读取一致性的固定协议模式；调用方按业务新鲜度选择，不依赖枚举序号。 */
    public enum Mode {
        MINIMIZE_LATENCY,
        AT_LEAST_AS_FRESH,
        FULLY_CONSISTENT
    }

    /** 显式选择原最小延迟模式，调用方应按契约承担其读取新鲜度边界。 */
    public static Consistency minimizeLatency() {
        return new Consistency(Mode.MINIMIZE_LATENCY, null);
    }

    /** 显式选择完全一致读取，撤权等关键观察不能依赖较旧快照。 */
    public static Consistency fullyConsistent() {
        return new Consistency(Mode.FULLY_CONSISTENT, null);
    }

    /** 绑定原写入令牌的最小新鲜度，后续读取不能落后于已观察的变更。 */
    public static Consistency atLeastAsFresh(String zedToken) {
        return new Consistency(Mode.AT_LEAST_AS_FRESH, zedToken);
    }
}
