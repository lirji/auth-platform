package com.lrj.authz.admin.web;

import java.util.List;

/** 管控台 REST 契约 (console ⇄ admin)。字段扁平, 便于前端表单/调试器直接映射。 */
public final class AdminDtos {

    private AdminDtos() {}

    /** 授予/撤销一条关系。subjectRelation 非空表示 userset 主体 (如 group:eng#member)。 */
    public record GrantRequest(
            String resourceType,
            String resourceId,
            String relation,
            String subjectType,
            String subjectId,
            String subjectRelation) {}

    /** 旧判权接口的 CheckRequest 请求，保留原字段命名与默认值，新治理协议不能暗中替换此消费契约。 */
    public record CheckRequest(
            String subjectType,
            String subjectId,
            String permission,
            String resourceType,
            String resourceId) {}

    /** 旧判权接口的 CheckResponse 响应，保留原字段命名与默认值，新治理协议不能暗中替换此消费契约。 */
    public record CheckResponse(boolean allowed) {}

    /** 旧判权接口的 TokenResponse 响应，保留原字段命名与默认值，新治理协议不能暗中替换此消费契约。 */
    public record TokenResponse(String token) {}

    /** 旧判权接口的 SubjectView 资源或主体快照，保留原字段命名与默认值，新治理协议不能暗中替换此消费契约。 */
    public record SubjectView(String type, String id) {}

    /** 旧判权接口的 SubjectsResponse 响应，保留原字段命名与默认值，新治理协议不能暗中替换此消费契约。 */
    public record SubjectsResponse(List<SubjectView> subjects) {}

    /** 旧判权接口的 ResourcesResponse 响应，保留原字段命名与默认值，新治理协议不能暗中替换此消费契约。 */
    public record ResourcesResponse(List<String> resourceIds) {}

    /** 旧判权接口的 WorkspaceView 资源或主体快照，保留原字段命名与默认值，新治理协议不能暗中替换此消费契约。 */
    public record WorkspaceView(
            String id,
            String name,
            String organization,
            String home,
            List<String> features,
            String endpoint) {}

    /** 旧判权接口的 WorkspacesResponse 响应，保留原字段命名与默认值，新治理协议不能暗中替换此消费契约。 */
    public record WorkspacesResponse(List<WorkspaceView> workspaces) {}
}
