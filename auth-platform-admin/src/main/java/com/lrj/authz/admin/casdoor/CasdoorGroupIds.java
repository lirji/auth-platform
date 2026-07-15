package com.lrj.authz.admin.casdoor;

import java.util.regex.Pattern;

/**
 * Casdoor 组 → SpiceDB {@code group} 对象 id 的<strong>租户化编码唯一入口（v1）</strong>。
 *
 * <p>背景：旧 {@code CasdoorClient.shortName} 把 {@code <org>/<group>} 截成短名，导致不同租户
 * (organization) 下的同名组在 SpiceDB 里合并成同一 {@code group:<name>}，跨租户串权。本 codec 产出
 * {@code <organization>_<group>}，把租户前缀固化进 group id。
 *
 * <p><strong>碰撞安全</strong>：为保证 {@code '_'} 分隔无歧义（否则 {@code encode("a_b","c")} 与
 * {@code encode("a","b_c")} 都会得到 {@code "a_b_c"}），v1 要求 organization 与 group 均匹配
 * {@code [A-Za-z0-9][A-Za-z0-9-]*}（首位字母数字，其后字母数字或连字符；<em>不含</em>下划线/斜杠/其它）。
 * 不满足者一律 <strong>fail-closed 抛出</strong>，绝不猜测转义而写出可能碰撞的生产 tuple——转义/哈希的
 * v2 编码属"待验证"项，未定案前不落库。产出同时是合法 SpiceDB object id。
 *
 * <p>本阶段（领域模型）仅提供并单测本 codec；{@code GroupSyncService} 迁移到用它在后续阶段进行。
 */
public final class CasdoorGroupIds {

    /** 编码版本；变更编码规则时递增，迁移 manifest 应记录版本以便对账。 */
    public static final String VERSION = "v1";

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]*");

    private CasdoorGroupIds() {
    }

    /**
     * 租户化 group 对象 id：{@code <organization>_<group>}。
     *
     * @param organization Casdoor 组织(租户) 名
     * @param group        Casdoor 组短名（不含 {@code <org>/} 前缀）
     * @return {@code <organization>_<group>}
     * @throws IllegalArgumentException 任一参数为空或含歧义/非法字符
     */
    public static String encode(String organization, String group) {
        String org = requireSafe(organization, "organization");
        String grp = requireSafe(group, "group");
        return org + "_" + grp;
    }

    private static String requireSafe(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("casdoor group id: " + field + " 不能为空");
        }
        if (!SAFE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "casdoor group id: " + field + "=\"" + value + "\" 含非法字符（v1 仅允许 [A-Za-z0-9-]、"
                            + "首位字母数字）；含 '_'/'/'/空白等会造成租户前缀歧义，需 v2 转义编码，当前 fail-closed 拒绝");
        }
        return value;
    }
}
