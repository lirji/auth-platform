package com.lrj.authz.admin;

import java.util.List;

/** 管控台 REST 契约 (console ⇄ admin)。字段扁平, 便于前端表单/调试器直接映射。 */
public final class AdminDtos {

    private AdminDtos() {
    }

    /** 授予/撤销一条关系。subjectRelation 非空表示 userset 主体 (如 group:eng#member)。 */
    public record GrantRequest(String resourceType, String resourceId, String relation,
                               String subjectType, String subjectId, String subjectRelation) {
    }

    public record CheckRequest(String subjectType, String subjectId,
                               String permission, String resourceType, String resourceId) {
    }

    public record CheckResponse(boolean allowed) {
    }

    public record TokenResponse(String token) {
    }

    public record SubjectView(String type, String id) {
    }

    public record SubjectsResponse(List<SubjectView> subjects) {
    }

    public record ResourcesResponse(List<String> resourceIds) {
    }
}
