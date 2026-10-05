package com.lrj.authz.protocol;

/**
 * 主体引用: 类型 + id (+ 可选关系, 用于 userset 主体如 group:eng#member)。
 * relation 为 null 表示直接主体 (如 user:u_123)。
 */
public record SubjectRef(String type, String id, String relation) {
    /** 构造既有用户类型的主体引用，资源关系不能自行改换主体类型。 */
    public static SubjectRef user(String id) {
        return new SubjectRef("user", id, null);
    }

    /** 用明确类型、身份及原过滤字段建立 SubjectRef，不能仅从标识推断访问资格。 */
    public static SubjectRef of(String type, String id) {
        return new SubjectRef(type, id, null);
    }

    /** userset 主体, 如 group:eng#member -> ofRelation("group","eng","member")。 */
    public static SubjectRef ofRelation(String type, String id, String relation) {
        return new SubjectRef(type, id, relation);
    }
}
