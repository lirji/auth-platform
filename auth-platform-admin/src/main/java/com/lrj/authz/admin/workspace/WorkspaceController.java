package com.lrj.authz.admin.workspace;

import com.lrj.authz.admin.AdminDtos.WorkspaceView;
import com.lrj.authz.admin.AdminDtos.WorkspacesResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 当前身份可见的授权工作区。门户不预选；由 JWT owner / 平台管理员决定。 */
@RestController
@RequestMapping("/admin")
public class WorkspaceController {

    private final WorkspaceRegistry registry;

    public WorkspaceController(WorkspaceRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/workspaces")
    public WorkspacesResponse list(@AuthenticationPrincipal Jwt jwt) {
        List<WorkspaceView> items = WorkspaceAccess.visible(jwt,
                        registry.all().stream().map(WorkspaceRegistry.BoundWorkspace::item).toList())
                .stream()
                .map(item -> new WorkspaceView(
                        item.getId(),
                        item.getName(),
                        item.getOrganization(),
                        item.getHome() == null || item.getHome().isBlank() ? "grants" : item.getHome(),
                        item.getFeatures(),
                        item.getEndpoint()))
                .toList();
        return new WorkspacesResponse(items);
    }
}
