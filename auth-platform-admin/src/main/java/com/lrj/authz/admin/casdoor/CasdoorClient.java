package com.lrj.authz.admin.casdoor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 读 Casdoor 组织的用户与组 (Basic auth: clientId:clientSecret)。 */
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

    /** 期望的组成员: 短组名 -> subject id 集合 (从各用户的 groups 字段聚合)。 */
    public Map<String, Set<String>> groupMembers() {
        Map<String, Set<String>> map = new TreeMap<>();
        JsonNode data = get("/api/get-users?owner=" + props.getOrganization()).path("data");
        for (JsonNode u : data) {
            String subject = "name".equals(props.getSubjectField()) ? u.path("name").asText() : u.path("id").asText();
            if (subject == null || subject.isBlank()) {
                continue;
            }
            for (JsonNode g : u.path("groups")) {
                String gn = shortName(g.asText());
                if (!gn.isBlank()) {
                    map.computeIfAbsent(gn, k -> new LinkedHashSet<>()).add(subject);
                }
            }
        }
        return map;
    }

    /** Casdoor 里存在的全部组 (短名), 用于对账时处理"成员被清空"的组。 */
    public Set<String> groupNames() {
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode g : get("/api/get-groups?owner=" + props.getOrganization()).path("data")) {
            String n = g.path("name").asText();
            if (n != null && !n.isBlank()) {
                names.add(n);
            }
        }
        return names;
    }

    private JsonNode get(String path) {
        String body = rest.get().uri(path).retrieve().body(String.class);
        try {
            return mapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new IllegalStateException("Casdoor 响应解析失败: " + e.getMessage(), e);
        }
    }

    /** "built-in/engineers" -> "engineers"。 */
    private static String shortName(String full) {
        int i = full.lastIndexOf('/');
        return i >= 0 ? full.substring(i + 1) : full;
    }
}
