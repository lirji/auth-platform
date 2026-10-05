package com.lrj.authz.admin.workspace.web;

import com.lrj.authz.admin.web.AdminDtos.WorkspaceView;
import com.lrj.authz.admin.web.AdminDtos.WorkspacesResponse;
import com.lrj.authz.admin.workspace.application.WorkspaceAccess;
import com.lrj.authz.admin.workspace.application.WorkspaceRegistry;

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

    /** 显式绑定 WorkspaceController 的协作对象与配置，后续实例操作必须沿用同一组依赖与生命周期。 */
    public WorkspaceController(WorkspaceRegistry registry) {
        this.registry = registry;
    }

    /** 沿用当前可信身份过滤已登记工作区，不能把配置存在直接当作访问允许。 */
    @GetMapping("/workspaces")
    public WorkspacesResponse list(@AuthenticationPrincipal Jwt jwt) {
        List<WorkspaceView> items =
                WorkspaceAccess.visible(
                                jwt,
                                registry.all().stream()
                                        .map(WorkspaceRegistry.BoundWorkspace::item)
                                        .toList())
                        .stream()
                        .map(
                                item ->
                                        new WorkspaceView(
                                                item.getId(),
                                                item.getName(),
                                                item.getOrganization(),
                                                item.getHome() == null || item.getHome().isBlank()
                                                        ? "grants"
                                                        : item.getHome(),
                                                item.getFeatures(),
                                                item.getEndpoint()))
                        .toList();
        return new WorkspacesResponse(items);
    }
}
