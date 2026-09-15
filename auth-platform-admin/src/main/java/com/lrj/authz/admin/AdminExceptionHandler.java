package com.lrj.authz.admin;

import com.lrj.authz.admin.workspace.WorkspaceRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

import java.util.Map;

/** 把 SpiceDB 连不上从 500 HTML 收成 503 JSON，避免管控台误报成未登录。 */
@RestControllerAdvice
public class AdminExceptionHandler {

    private final WorkspaceRegistry workspaces;

    public AdminExceptionHandler(WorkspaceRegistry workspaces) {
        this.workspaces = workspaces;
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Map<String, String>> spiceDbUnreachable(ResourceAccessException e) {
        String id = workspaces.currentId();
        String where = e.getMessage() == null ? "" : e.getMessage();
        String endpoint = "";
        int http = where.indexOf("http://");
        int https = where.indexOf("https://");
        int start = http >= 0 ? http : https;
        if (start >= 0) {
            int end = where.indexOf('"', start);
            endpoint = end > start ? where.substring(start, end) : where.substring(start);
        }
        String msg = "工作区 " + id + " 的 SpiceDB 不可达"
                + (endpoint.isBlank() ? "" : "（" + endpoint + "）")
                + "。本机知识库是 :8543；推荐系统 :8544、风控 :8545 需先启动对应实例。";
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", msg));
    }
}
