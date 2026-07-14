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
    public enum Mode { MINIMIZE_LATENCY, AT_LEAST_AS_FRESH, FULLY_CONSISTENT }

    public static Consistency minimizeLatency() {
        return new Consistency(Mode.MINIMIZE_LATENCY, null);
    }

    public static Consistency fullyConsistent() {
        return new Consistency(Mode.FULLY_CONSISTENT, null);
    }

    public static Consistency atLeastAsFresh(String zedToken) {
        return new Consistency(Mode.AT_LEAST_AS_FRESH, zedToken);
    }
}
