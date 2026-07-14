package com.lrj.authz.protocol;

/** 关系元组写操作。TOUCH = 幂等 create/update; CREATE = 必须不存在; DELETE = 删一条。 */
public record RelationshipUpdate(Operation operation, ResourceRef resource, String relation, SubjectRef subject) {
    public enum Operation { CREATE, TOUCH, DELETE }

    public static RelationshipUpdate touch(ResourceRef resource, String relation, SubjectRef subject) {
        return new RelationshipUpdate(Operation.TOUCH, resource, relation, subject);
    }

    public static RelationshipUpdate create(ResourceRef resource, String relation, SubjectRef subject) {
        return new RelationshipUpdate(Operation.CREATE, resource, relation, subject);
    }

    public static RelationshipUpdate delete(ResourceRef resource, String relation, SubjectRef subject) {
        return new RelationshipUpdate(Operation.DELETE, resource, relation, subject);
    }
}
