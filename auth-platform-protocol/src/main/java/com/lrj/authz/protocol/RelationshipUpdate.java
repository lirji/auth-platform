package com.lrj.authz.protocol;

/** 关系元组写操作。TOUCH = 幂等 create/update; CREATE = 必须不存在; DELETE = 删一条。 */
public record RelationshipUpdate(
        Operation operation, ResourceRef resource, String relation, SubjectRef subject) {
    /** 关系变更操作的固定协议集合，触达、新增与删除语义不能相互替换。 */
    public enum Operation {
        CREATE,
        TOUCH,
        DELETE
    }

    /** 表达既有幂等关系触达操作，调用方不能把它与仅新增语义混淆。 */
    public static RelationshipUpdate touch(
            ResourceRef resource, String relation, SubjectRef subject) {
        return new RelationshipUpdate(Operation.TOUCH, resource, relation, subject);
    }

    /** 表达仅新增关系操作，既有关系冲突不能被当作新的写入成功。 */
    public static RelationshipUpdate create(
            ResourceRef resource, String relation, SubjectRef subject) {
        return new RelationshipUpdate(Operation.CREATE, resource, relation, subject);
    }

    /** 表达按原精确关系删除操作，删除范围不能由适配器自行扩大。 */
    public static RelationshipUpdate delete(
            ResourceRef resource, String relation, SubjectRef subject) {
        return new RelationshipUpdate(Operation.DELETE, resource, relation, subject);
    }
}
