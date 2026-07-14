package com.lrj.authz.protocol;

/**
 * 主体引用: 类型 + id (+ 可选关系, 用于 userset 主体如 group:eng#member)。
 * relation 为 null 表示直接主体 (如 user:u_123)。
 */
public record SubjectRef(String type, String id, String relation) {
    public static SubjectRef user(String id) {
        return new SubjectRef("user", id, null);
    }

    public static SubjectRef of(String type, String id) {
        return new SubjectRef(type, id, null);
    }

    /** userset 主体, 如 group:eng#member -> ofRelation("group","eng","member")。 */
    public static SubjectRef ofRelation(String type, String id, String relation) {
        return new SubjectRef(type, id, relation);
    }
}
