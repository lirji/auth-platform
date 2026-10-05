package com.lrj.authz.server.legacy.web;

import com.lrj.authz.protocol.Relationship;
import com.lrj.authz.protocol.RelationshipFilter;
import com.lrj.authz.protocol.RelationshipUpdate;
import com.lrj.authz.protocol.ResourceRef;
import com.lrj.authz.protocol.SubjectRef;

import java.util.List;

/** 判权服务 REST 契约 (SDK ⇄ server)。故意比 SpiceDB 原生 API 简化。 */
public final class AuthzDtos {

    private AuthzDtos() {}

    /** 一致性: mode = minimize_latency(默认) | at_least_as_fresh | full。 */
    public record ConsistencyDto(String mode, String zedToken) {}

    /** 旧判权接口的 CheckRequest 请求，保留原字段命名与默认值，新治理协议不能暗中替换此消费契约。 */
    public record CheckRequest(
            SubjectRef subject,
            String permission,
            ResourceRef resource,
            ConsistencyDto consistency) {}

    /** 旧判权接口的 CheckResponse 响应，保留原字段命名与默认值，新治理协议不能暗中替换此消费契约。 */
    public record CheckResponse(boolean allowed) {}

    /** 旧判权接口的 CheckBulkRequest 请求，保留原字段命名与默认值，新治理协议不能暗中替换此消费契约。 */
    public record CheckBulkRequest(
            SubjectRef subject,
            String permission,
            List<ResourceRef> resources,
            ConsistencyDto consistency) {}

    /** 旧判权接口的 ResourceAllowed 资源或主体快照，保留原字段命名与默认值，新治理协议不能暗中替换此消费契约。 */
    public record ResourceAllowed(ResourceRef resource, boolean allowed) {}

    /** 旧判权接口的 CheckBulkResponse 响应，保留原字段命名与默认值，新治理协议不能暗中替换此消费契约。 */
    public record CheckBulkResponse(List<ResourceAllowed> results) {}

    /** 旧判权接口的 LookupResourcesRequest 请求，保留原字段命名与默认值，新治理协议不能暗中替换此消费契约。 */
    public record LookupResourcesRequest(
            SubjectRef subject,
            String permission,
            String resourceType,
            ConsistencyDto consistency) {}

    /** 旧判权接口的 LookupResourcesResponse 响应，保留原字段命名与默认值，新治理协议不能暗中替换此消费契约。 */
    public record LookupResourcesResponse(List<String> resourceIds) {}

    /** 旧判权接口的 LookupSubjectsRequest 请求，保留原字段命名与默认值，新治理协议不能暗中替换此消费契约。 */
    public record LookupSubjectsRequest(
            ResourceRef resource,
            String permission,
            String subjectType,
            ConsistencyDto consistency) {}

    /** 旧判权接口的 LookupSubjectsResponse 响应，保留原字段命名与默认值，新治理协议不能暗中替换此消费契约。 */
    public record LookupSubjectsResponse(List<SubjectRef> subjects) {}

    /** 旧判权接口的 WriteRequest 请求，保留原字段命名与默认值，新治理协议不能暗中替换此消费契约。 */
    public record WriteRequest(List<RelationshipUpdate> updates) {}

    /** 旧判权接口的 DeleteRequest 请求，保留原字段命名与默认值，新治理协议不能暗中替换此消费契约。 */
    public record DeleteRequest(RelationshipFilter filter) {}

    /** 旧判权接口的 TokenResponse 响应，保留原字段命名与默认值，新治理协议不能暗中替换此消费契约。 */
    public record TokenResponse(String token) {}

    /** 旧判权接口的 ExpandRequest 请求，保留原字段命名与默认值，新治理协议不能暗中替换此消费契约。 */
    public record ExpandRequest(
            ResourceRef resource, String permission, ConsistencyDto consistency) {}

    /** 旧判权接口的 ReadRelationshipsResponse 响应，保留原字段命名与默认值，新治理协议不能暗中替换此消费契约。 */
    public record ReadRelationshipsResponse(List<Relationship> relationships) {}
}
