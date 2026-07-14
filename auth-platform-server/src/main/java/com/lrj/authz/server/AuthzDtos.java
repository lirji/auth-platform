package com.lrj.authz.server;

import com.lrj.authz.protocol.Relationship;
import com.lrj.authz.protocol.RelationshipFilter;
import com.lrj.authz.protocol.RelationshipUpdate;
import com.lrj.authz.protocol.ResourceRef;
import com.lrj.authz.protocol.SubjectRef;

import java.util.List;

/** 判权服务 REST 契约 (SDK ⇄ server)。故意比 SpiceDB 原生 API 简化。 */
public final class AuthzDtos {

    private AuthzDtos() {
    }

    /** 一致性: mode = minimize_latency(默认) | at_least_as_fresh | full。 */
    public record ConsistencyDto(String mode, String zedToken) {
    }

    public record CheckRequest(SubjectRef subject, String permission, ResourceRef resource, ConsistencyDto consistency) {
    }

    public record CheckResponse(boolean allowed) {
    }

    public record CheckBulkRequest(SubjectRef subject, String permission, List<ResourceRef> resources, ConsistencyDto consistency) {
    }

    public record ResourceAllowed(ResourceRef resource, boolean allowed) {
    }

    public record CheckBulkResponse(List<ResourceAllowed> results) {
    }

    public record LookupResourcesRequest(SubjectRef subject, String permission, String resourceType, ConsistencyDto consistency) {
    }

    public record LookupResourcesResponse(List<String> resourceIds) {
    }

    public record LookupSubjectsRequest(ResourceRef resource, String permission, String subjectType, ConsistencyDto consistency) {
    }

    public record LookupSubjectsResponse(List<SubjectRef> subjects) {
    }

    public record WriteRequest(List<RelationshipUpdate> updates) {
    }

    public record DeleteRequest(RelationshipFilter filter) {
    }

    public record TokenResponse(String token) {
    }

    public record ExpandRequest(ResourceRef resource, String permission, ConsistencyDto consistency) {
    }

    public record ReadRelationshipsResponse(List<Relationship> relationships) {
    }
}
