package com.lrj.authz.protocol;

import java.util.List;
import java.util.Map;

/**
 * 授权引擎端口 (六边形架构的 Port)。
 * 现有实现: SpiceDbAuthzEngine (core, 走 SpiceDB HTTP API)、RemoteAuthzEngine (sdk, 走 auth-platform-server)。
 * 换引擎 (如 OpenFGA) 只需新增一个实现类, 上层零改。
 */
public interface AuthzEngine {

    /** subject 是否对 resource 拥有 permission。 */
    boolean check(SubjectRef subject, String permission, ResourceRef resource, Consistency consistency);

    /** 批量判权 (一次 RPC), 返回每个 resource 是否放行 (保持入参顺序)。 */
    Map<ResourceRef, Boolean> checkBulk(SubjectRef subject, String permission, List<ResourceRef> resources, Consistency consistency);

    /** subject 对某类型对象拥有 permission 的全部对象 id (反查: "我能看哪些")。 */
    List<String> lookupResources(SubjectRef subject, String permission, String resourceType, Consistency consistency);

    /** 对 resource 拥有 permission 的全部某类型主体 (反查: "谁能看这个")。 */
    List<SubjectRef> lookupSubjects(ResourceRef resource, String permission, String subjectType, Consistency consistency);

    /** 写关系元组 (TOUCH/CREATE/DELETE), 返回写入后的一致性水位。 */
    ZedTokenView writeRelationships(List<RelationshipUpdate> updates);

    /** 按过滤器批量删关系元组 (如删对象时清其全部关系), 返回删除后的水位。 */
    ZedTokenView deleteRelationships(RelationshipFilter filter);

    /** 读取当前授权模型 (.zed schema 文本), 供管控台可视化。 */
    String readSchema();

    /** 展开某对象某权限的判定树 (SpiceDB ExpandPermissionTree), 返回原始树 JSON 文本; 供调试器解释"为何"。 */
    String expand(ResourceRef resource, String permission, Consistency consistency);

    /** 读取匹配过滤器的原始关系元组 (列某资源现存授予)。 */
    List<Relationship> readRelationships(RelationshipFilter filter);
}
