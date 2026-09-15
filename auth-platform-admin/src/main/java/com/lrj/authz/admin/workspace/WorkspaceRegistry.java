package com.lrj.authz.admin.workspace;

import com.lrj.authz.admin.AdminSpiceDbProperties;
import com.lrj.authz.core.SpiceDbAuthzEngine;
import com.lrj.authz.protocol.AuthzEngine;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 工作区目录 + 请求内当前引擎。Casdoor 同步始终用 {@link #defaultEngine()}，不跟请求头切换。 */
@Component
public class WorkspaceRegistry {

    public static final String HEADER = "X-Authz-Workspace";
    public static final String DEFAULT_ID = "knowledge";

    private static final List<String> DEFAULT_FEATURES = List.of(
            "grants", "playground", "schema", "spaces", "sync", "audit");

    private final Map<String, BoundWorkspace> workspaces = new LinkedHashMap<>();
    private final AuthzEngine defaultEngine;
    private final ThreadLocal<BoundWorkspace> current = new ThreadLocal<>();

    public WorkspaceRegistry(WorkspaceProperties properties, AdminSpiceDbProperties spicedb) {
        this.defaultEngine = new SpiceDbAuthzEngine(
                spicedb.getEndpoint(), spicedb.getToken(),
                spicedb.getConnectTimeout(), spicedb.getReadTimeout());
        List<WorkspaceProperties.Item> items = properties.getWorkspaces();
        if (items == null || items.isEmpty()) {
            WorkspaceProperties.Item fallback = new WorkspaceProperties.Item();
            fallback.setId(DEFAULT_ID);
            fallback.setName("知识库与 HIS");
            fallback.setOrganization("built-in");
            fallback.setOrganizations(List.of("acme", "globex"));
            fallback.setHome("spaces");
            fallback.setFeatures(DEFAULT_FEATURES);
            fallback.setEndpoint(spicedb.getEndpoint());
            workspaces.put(DEFAULT_ID, new BoundWorkspace(fallback, defaultEngine));
            return;
        }
        for (WorkspaceProperties.Item item : items) {
            String id = WorkspaceAccess.normalizeId(item.getId());
            if (id.isEmpty()) {
                throw new IllegalStateException("authz.workspaces 项缺少 id");
            }
            AuthzEngine engine = defaultEngine;
            if (StringUtils.hasText(item.getEndpoint())
                    && !item.getEndpoint().equals(spicedb.getEndpoint())) {
                String token = StringUtils.hasText(item.getToken()) ? item.getToken() : spicedb.getToken();
                engine = new SpiceDbAuthzEngine(
                        item.getEndpoint(), token, spicedb.getConnectTimeout(), spicedb.getReadTimeout());
            }
            item.setId(id);
            if (!StringUtils.hasText(item.getEndpoint())) {
                item.setEndpoint(spicedb.getEndpoint());
            }
            if (item.getFeatures() == null || item.getFeatures().isEmpty()) {
                item.setFeatures(new ArrayList<>(DEFAULT_FEATURES));
            }
            workspaces.put(id, new BoundWorkspace(item, engine));
        }
    }

    public AuthzEngine defaultEngine() {
        return defaultEngine;
    }

    public List<BoundWorkspace> all() {
        return List.copyOf(workspaces.values());
    }

    public BoundWorkspace require(String rawId, Jwt jwt) {
        String id = WorkspaceAccess.normalizeId(rawId);
        if (id.isEmpty()) {
            id = DEFAULT_ID;
            if (!workspaces.containsKey(id)) {
                id = workspaces.keySet().iterator().next();
            }
        }
        BoundWorkspace bound = workspaces.get(id);
        if (bound == null) {
            throw new IllegalArgumentException("未知授权工作区: " + rawId);
        }
        if (!WorkspaceAccess.allowed(jwt, bound.item())) {
            throw new AccessDeniedException("无权进入工作区 " + bound.item().getId());
        }
        return bound;
    }

    public void bind(String rawId, Authentication authentication) {
        Jwt jwt = jwtOf(authentication);
        current.set(require(rawId, jwt));
    }

    public void clear() {
        current.remove();
    }

    public AuthzEngine currentEngine() {
        BoundWorkspace bound = current.get();
        return bound == null ? defaultEngine : bound.engine();
    }

    public String currentId() {
        BoundWorkspace bound = current.get();
        return bound == null ? DEFAULT_ID : bound.item().getId();
    }

    public static Jwt jwtOf(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt;
        }
        return null;
    }

    public record BoundWorkspace(WorkspaceProperties.Item item, AuthzEngine engine) {
    }
}
