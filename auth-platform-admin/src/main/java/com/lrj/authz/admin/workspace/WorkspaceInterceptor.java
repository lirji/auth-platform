package com.lrj.authz.admin.workspace;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * 从 {@code X-Authz-Workspace} 绑定当前 SpiceDB。Casdoor 同步与工作区列表不切换实例。
 */
public class WorkspaceInterceptor implements HandlerInterceptor {

    private final WorkspaceRegistry registry;

    public WorkspaceInterceptor(WorkspaceRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI();
        }
        if (!path.startsWith("/admin")) {
            return true;
        }
        if ("GET".equalsIgnoreCase(request.getMethod())
                && (path.equals("/admin/workspaces") || path.equals("/admin/workspaces/"))) {
            return true;
        }
        if (path.startsWith("/admin/casdoor")) {
            return true;
        }
        try {
            registry.bind(request.getHeader(WorkspaceRegistry.HEADER),
                    SecurityContextHolder.getContext().getAuthentication());
            return true;
        } catch (AccessDeniedException e) {
            response.sendError(HttpStatus.FORBIDDEN.value(), e.getMessage());
            return false;
        } catch (IllegalArgumentException e) {
            response.sendError(HttpStatus.BAD_REQUEST.value(), e.getMessage());
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        registry.clear();
    }
}
