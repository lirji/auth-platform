package com.lrj.authz.admin.workspace;

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

    public WorkspaceRoutingEngine(WorkspaceRegistry registry) {
        this.registry = registry;
    }

    private AuthzEngine engine() {
        return registry.currentEngine();
    }

    @Override
    public boolean check(SubjectRef subject, String permission, ResourceRef resource, Consistency consistency) {
        return engine().check(subject, permission, resource, consistency);
    }

    @Override
    public Map<ResourceRef, Boolean> checkBulk(SubjectRef subject, String permission,
                                               List<ResourceRef> resources, Consistency consistency) {
        return engine().checkBulk(subject, permission, resources, consistency);
    }

    @Override
    public List<String> lookupResources(SubjectRef subject, String permission, String resourceType,
                                        Consistency consistency) {
        return engine().lookupResources(subject, permission, resourceType, consistency);
    }

    @Override
    public List<SubjectRef> lookupSubjects(ResourceRef resource, String permission, String subjectType,
                                           Consistency consistency) {
        return engine().lookupSubjects(resource, permission, subjectType, consistency);
    }

    @Override
    public ZedTokenView writeRelationships(List<RelationshipUpdate> updates) {
        return engine().writeRelationships(updates);
    }

    @Override
    public ZedTokenView deleteRelationships(RelationshipFilter filter) {
        return engine().deleteRelationships(filter);
    }

    @Override
    public String readSchema() {
        return engine().readSchema();
    }

    @Override
    public String expand(ResourceRef resource, String permission, Consistency consistency) {
        return engine().expand(resource, permission, consistency);
    }

    @Override
    public List<Relationship> readRelationships(RelationshipFilter filter) {
        return engine().readRelationships(filter);
    }
}
