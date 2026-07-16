package com.lrj.authz.admin.casdoor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 读 Casdoor 组织的用户与组 (Basic auth: clientId:clientSecret)。
 * 支持多 org（{@code authz.casdoor.organizations} 列表；空则回退单 {@code organization}）：
 * 逐 org 拉取后合并——组/部门 id 经 {@link CasdoorGroupIds} 天然带 org 前缀，合并无碰撞。
 */
public class CasdoorClient {

    private final RestClient rest;
    private final CasdoorProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public CasdoorClient(CasdoorProperties props) {
        this.props = props;
        this.rest = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .defaultHeaders(h -> h.setBasicAuth(props.getClientId(), props.getClientSecret()))
                .build();
    }

    /**
     * 期望的组成员: <strong>租户化组 id</strong> {@code <org>_<group>} -> subject id 集合 (从各用户 groups 聚合)。
     * 用 {@link CasdoorGroupIds} 把租户前缀固化进 group id, 避免不同租户同名组在 SpiceDB 合并 (跨租户串权)。
     */
    public Map<String, Set<String>> groupMembers() {
        Map<String, Set<String>> map = new TreeMap<>();
        for (String org : props.effectiveOrganizations()) {
            collectGroupMembers(org, map, null);
        }
        return map;
    }

    /** Casdoor 里存在的全部组 (<strong>租户化 id</strong>), 用于对账时处理"成员被清空"的组。 */
    public Set<String> groupNames() {
        Set<String> names = new LinkedHashSet<>();
        for (String org : props.effectiveOrganizations()) {
            for (JsonNode g : get("/api/get-groups?owner=" + org).path("data")) {
                String name = g.path("name").asText();
                if (name != null && !name.isBlank()) {
                    String owner = g.path("owner").asText(org);
                    names.add(CasdoorGroupIds.encode(owner, name));
                }
            }
        }
        return names;
    }

    /** 拉取一个 org 的用户,聚合组成员;可选顺带收集 username -> subject 映射(部门管理员解析用)。 */
    private void collectGroupMembers(String org, Map<String, Set<String>> members, Map<String, String> nameToSubject) {
        for (JsonNode u : get("/api/get-users?owner=" + org).path("data")) {
            String name = u.path("name").asText();
            String subject = "name".equals(props.getSubjectField()) ? name : u.path("id").asText();
            if (subject == null || subject.isBlank()) {
                continue;
            }
            if (nameToSubject != null && name != null && !name.isBlank()) {
                nameToSubject.put(name, subject);
            }
            for (JsonNode g : u.path("groups")) {
                String gid = scopedGroupId(g.asText(), org);
                if (gid != null) {
                    members.computeIfAbsent(gid, k -> new LinkedHashSet<>()).add(subject);
                }
            }
        }
    }

    /**
     * 把 Casdoor 的组引用 (完整 {@code <org>/<group>} 或短名) 编码成租户化 SpiceDB group id。
     * 完整路径取其 org 段; 短名回退到当前遍历的 org。非法字符由 {@link CasdoorGroupIds#encode} fail-closed 抛出。
     */
    private String scopedGroupId(String groupRef, String defaultOrg) {
        if (groupRef == null || groupRef.isBlank()) {
            return null;
        }
        int i = groupRef.lastIndexOf('/');
        String org = i >= 0 ? groupRef.substring(0, i) : defaultOrg;
        String group = i >= 0 ? groupRef.substring(i + 1) : groupRef;
        return CasdoorGroupIds.encode(org, group);
    }

    /**
     * 部门树期望态快照（部门层级模型）：逐 org 拉全 users/groups/roles 并合并，产出 SpiceDB {@code department}
     * 需要的三类关系。
     * <ul>
     *   <li>{@code members}: 租户化 department id -> 成员 subject 集合（同 {@link #groupMembers}，但用于 department）。</li>
     *   <li>{@code parents}: 子 department id -> 父 department id（来自 group {@code parentId}，形如 {@code <org>/<parent>}）。</li>
     *   <li>{@code admins}: department id -> 管理员 subject 集合（V-03：约定 Casdoor role 名 {@code <group>-admin} 的
     *       users 即该部门管理员；用户名经<strong>同 org</strong> 用户表解析为 subject）。</li>
     * </ul>
     * 字段/父子形状为实测所得，异常安全跳过。deleteThreshold 熔断为全局（跨 org 累计），多 org 迁移时注意调大。
     */
    public DepartmentSnapshot departmentSnapshot() {
        Set<String> deptIds = new LinkedHashSet<>();
        Map<String, Set<String>> members = new TreeMap<>();
        Map<String, String> parents = new TreeMap<>();
        Map<String, Set<String>> admins = new TreeMap<>();
        for (String org : props.effectiveOrganizations()) {
            collectDepartmentSnapshot(org, deptIds, members, parents, admins);
        }
        return new DepartmentSnapshot(deptIds, members, parents, admins);
    }

    private void collectDepartmentSnapshot(String org, Set<String> deptIds, Map<String, Set<String>> members,
                                           Map<String, String> parents, Map<String, Set<String>> admins) {
        Map<String, String> nameToSubject = new HashMap<>();
        collectGroupMembers(org, members, nameToSubject);
        for (JsonNode g : get("/api/get-groups?owner=" + org).path("data")) {
            String name = g.path("name").asText();
            if (name == null || name.isBlank()) {
                continue;
            }
            String gowner = g.path("owner").asText(org);
            String id = CasdoorGroupIds.encode(gowner, name);
            deptIds.add(id);
            String parentRef = g.path("parentId").asText("");
            if (parentRef != null && !parentRef.isBlank()) {
                parents.put(id, scopedGroupId(parentRef, org));   // <org>/<parent> -> <org>_<parent>
            }
        }
        String suffix = "-admin";
        for (JsonNode role : get("/api/get-roles?owner=" + org).path("data")) {
            String rname = role.path("name").asText("");
            if (rname.length() <= suffix.length() || !rname.endsWith(suffix)) {
                continue;
            }
            String dept = rname.substring(0, rname.length() - suffix.length());
            String rowner = role.path("owner").asText(org);
            String deptId = CasdoorGroupIds.encode(rowner, dept);
            for (JsonNode uref : role.path("users")) {
                String ref = uref.asText("");
                int i = ref.lastIndexOf('/');
                String uname = i >= 0 ? ref.substring(i + 1) : ref;   // <org>/<username> -> <username>
                String sub = nameToSubject.get(uname);
                if (sub != null) {
                    admins.computeIfAbsent(deptId, k -> new LinkedHashSet<>()).add(sub);
                }
            }
        }
    }

    /** 部门树期望态：全部 department id + 成员/父/管理员映射。 */
    public record DepartmentSnapshot(Set<String> deptIds,
                                     Map<String, Set<String>> members,
                                     Map<String, String> parents,
                                     Map<String, Set<String>> admins) {
    }

    private JsonNode get(String path) {
        String body = rest.get().uri(path).retrieve().body(String.class);
        try {
            return mapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new IllegalStateException("Casdoor 响应解析失败: " + e.getMessage(), e);
        }
    }
}
