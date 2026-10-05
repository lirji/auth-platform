package com.lrj.authz.protocol;

import java.util.List;
import java.util.Optional;

/** 可靠投影专用端口；不改变已有AuthzEngine九项调用契约。 */
public interface ProjectionGraph {
    String PARTITION_TYPE = "gov_partition";
    String MARKER_TYPE = "gov_epoch_marker";
    String HEAD_RELATION = "head";
    String GRANT_TYPE = "gov_access_grant";
    String GROUP_TYPE = "gov_group";
    String MEMBER_TYPE = "gov_membership";

    /** 完全一致读取最多两条marker，多个/损坏结果隔离，不尝试自动删除。 */
    Optional<Marker> readMarker(String partitionId);

    /** expected=null仅允许空分区初始化；业务关系和marker切换必须原子提交。 */
    String compareAndWrite(
            String partitionId,
            String expectedMarker,
            String newMarker,
            List<RelationshipUpdate> updates);

    /** 读取水位来自远端readAt，可恢复图已提交但SQL遗漏的回执。 */
    record Marker(String operationId, String readToken) {}

    /** 稳定分类区分CAS失败、协议损坏与依赖未知结果，日志不包含原始响应。 */
    final class Failure extends RuntimeException {
        /** 端口错误类型，协议名为显式固定编码。 */
        public enum Code {
            PRECONDITION_FAILED,
            PROTOCOL_INVALID,
            DEPENDENCY_UNAVAILABLE,
            MARKER_CONFLICT
        }

        private final Code code;

        /** 错误仅包含稳定分类，不附带敏感HTTP响应。 */
        public Failure(Code code) {
            super(code.name());
            this.code = code;
        }

        /** 调用方根据明确类别决定重新规划或隔离，不能统一当作未提交。 */
        public Code code() {
            return code;
        }
    }
}
