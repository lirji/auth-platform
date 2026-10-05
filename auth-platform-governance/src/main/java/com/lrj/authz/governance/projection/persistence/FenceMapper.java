package com.lrj.authz.governance.projection.persistence;

import com.lrj.authz.governance.access.domain.AccessModels.Partition;
import com.lrj.authz.governance.projection.domain.FenceModels.*;
import com.lrj.authz.protocol.GovernanceDtos.AccessContext;

import org.apache.ibatis.annotations.Param;

/** 栅栏主库持久化；没有缓存、副本读取或绕过就绪状态的查询。 */
public interface FenceMapper {
    /** 显式登记即接管；旧路径必须检测该行并拒绝执行。 */
    int enable(@Param("p") Partition p, @Param("actor") String actor);

    /** 查询现有严格分区，缺失表示尚未接管。 */
    Fence policy(@Param("p") Partition p);

    /** 目录栅栏覆盖整个租户及其全部应用。 */
    Fence directory(@Param("tenant") String tenant);

    /** 有效身份、准入、版本与双水位在一条SQL中读取。 */
    Stamp stamp(@Param("c") AccessContext context);
}
