package com.lrj.authz.admin.workspace.application;

import com.lrj.authz.admin.configuration.AdminSpiceDbProperties;
import com.lrj.authz.admin.workspace.configuration.WorkspaceProperties;
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

    private static final List<String> DEFAULT_FEATURES =
            List.of("grants", "playground", "schema", "spaces", "sync", "audit");

    private final Map<String, BoundWorkspace> workspaces = new LinkedHashMap<>();
    private final AuthzEngine defaultEngine;
    private final ThreadLocal<BoundWorkspace> current = new ThreadLocal<>();

    /** 显式绑定 WorkspaceRegistry 的协作对象与配置，后续实例操作必须沿用同一组依赖与生命周期。 */
    public WorkspaceRegistry(WorkspaceProperties properties, AdminSpiceDbProperties spicedb) {
        this.defaultEngine =
                new SpiceDbAuthzEngine(
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
                String token =
                        StringUtils.hasText(item.getToken()) ? item.getToken() : spicedb.getToken();
                engine =
                        new SpiceDbAuthzEngine(
                                item.getEndpoint(),
                                token,
                                spicedb.getConnectTimeout(),
                                spicedb.getReadTimeout());
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

    /** 返回当前注册表绑定的默认引擎，不能按用户输入重新创建信任目标。 */
    public AuthzEngine defaultEngine() {
        return defaultEngine;
    }

    /** 返回既有配置登记的工作区；是否可见仍由请求身份过滤。 */
    public List<BoundWorkspace> all() {
        return List.copyOf(workspaces.values());
    }

    /** 只定位既有登记的工作区，未知标识不能回退默认目标。 */
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

    /** 将已验证工作区绑定到当前线程，业务入口不能混用不同目标引擎。 */
    public void bind(String rawId, Authentication authentication) {
        Jwt jwt = jwtOf(authentication);
        current.set(require(rawId, jwt));
    }

    /** 清理当前线程的工作区绑定，后续请求必须重新验证和选择目标。 */
    public void clear() {
        current.remove();
    }

    /** 根据当前请求已绑定工作区路由，避免同一用例误写另一图目标。 */
    public AuthzEngine currentEngine() {
        BoundWorkspace bound = current.get();
        return bound == null ? defaultEngine : bound.engine();
    }

    /** 返回当前请求的工作区身份，用于一致路由与可关联审计。 */
    public String currentId() {
        BoundWorkspace bound = current.get();
        return bound == null ? DEFAULT_ID : bound.item().getId();
    }

    /** 只从既有认证对象取得JWT，未认证上下文不能被解释成合法登录。 */
    public static Jwt jwtOf(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt;
        }
        return null;
    }

    /** 当前线程已验证工作区的绑定快照，请求结束必须清理，不能跨请求继承目标。 */
    public record BoundWorkspace(WorkspaceProperties.Item item, AuthzEngine engine) {}
}
