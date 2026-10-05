package com.lrj.authz.admin.workspace.application;

import com.lrj.authz.protocol.AuthzEngine;
import com.lrj.authz.protocol.Consistency;
import com.lrj.authz.protocol.Relationship;
import com.lrj.authz.protocol.RelationshipFilter;
import com.lrj.authz.protocol.RelationshipUpdate;
import com.lrj.authz.protocol.ResourceRef;
import com.lrj.authz.protocol.SubjectRef;
import com.lrj.authz.protocol.ZedTokenView;

import java.util.List;
import java.util.Map;

/** 按请求绑定的工作区把九个端口操作转发给对应 SpiceDB 引擎。 */
public final class WorkspaceRoutingEngine implements AuthzEngine {

    private final WorkspaceRegistry registry;

    /** 显式绑定 WorkspaceRoutingEngine 的协作对象与配置，后续实例操作必须沿用同一组依赖与生命周期。 */
    public WorkspaceRoutingEngine(WorkspaceRegistry registry) {
        this.registry = registry;
    }

    private AuthzEngine engine() {
        return registry.currentEngine();
    }

    /** 将主体、资源和一致性要求交给已绑定的判权目标，未知失败不能解释为允许。 */
    @Override
    public boolean check(
            SubjectRef subject, String permission, ResourceRef resource, Consistency consistency) {
        return engine().check(subject, permission, resource, consistency);
    }

    /** 复用既有批量判权协议与一致性要求，调用方仍须按关联位置消费各项结果。 */
    @Override
    public Map<ResourceRef, Boolean> checkBulk(
            SubjectRef subject,
            String permission,
            List<ResourceRef> resources,
            Consistency consistency) {
        return engine().checkBulk(subject, permission, resources, consistency);
    }

    /** 在当前主体与权限条件下查询资源，不能把缺失结果扩张成无范围访问。 */
    @Override
    public List<String> lookupResources(
            SubjectRef subject, String permission, String resourceType, Consistency consistency) {
        return engine().lookupResources(subject, permission, resourceType, consistency);
    }

    /** 按给定资源与权限查询主体，查询结果不替代后续实际操作的授权检查。 */
    @Override
    public List<SubjectRef> lookupSubjects(
            ResourceRef resource, String permission, String subjectType, Consistency consistency) {
        return engine().lookupSubjects(resource, permission, subjectType, consistency);
    }

    /** 通过当前引擎的关系写入入口提交变更，返回令牌供后续一致性读取使用。 */
    @Override
    public ZedTokenView writeRelationships(List<RelationshipUpdate> updates) {
        return engine().writeRelationships(updates);
    }

    /** 按原关系过滤器提交删除，不能在失败时推断授权来源已经撤销完成。 */
    @Override
    public ZedTokenView deleteRelationships(RelationshipFilter filter) {
        return engine().deleteRelationships(filter);
    }

    /** 读取当前已绑定图目标的模式，不能从另一个工作区推断本目标的权限结构。 */
    @Override
    public String readSchema() {
        return engine().readSchema();
    }

    /** 沿用引擎的资源关系展开契约，诊断结果不能被当作执行授权回执。 */
    @Override
    public String expand(ResourceRef resource, String permission, Consistency consistency) {
        return engine().expand(resource, permission, consistency);
    }

    /** 保留原关系过滤条件与一致性模式读取事实，不把查询作为额外写入权威。 */
    @Override
    public List<Relationship> readRelationships(RelationshipFilter filter) {
        return engine().readRelationships(filter);
    }
}
