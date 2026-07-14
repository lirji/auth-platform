package com.lrj.authz.protocol;

/** 关系批量删除过滤器。resourceId / relation 为 null 表示不限定 (通配删除该类型下匹配的关系)。 */
public record RelationshipFilter(String resourceType, String resourceId, String relation) {
    /** 删掉某个具体对象上的全部关系 (如删文档时清其 owner/parent_* 元组)。 */
    public static RelationshipFilter ofResource(ResourceRef resource) {
        return new RelationshipFilter(resource.type(), resource.id(), null);
    }

    public static RelationshipFilter of(String resourceType, String resourceId, String relation) {
        return new RelationshipFilter(resourceType, resourceId, relation);
    }
}
