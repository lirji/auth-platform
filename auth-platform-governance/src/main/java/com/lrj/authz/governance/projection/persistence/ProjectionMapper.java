package com.lrj.authz.governance.projection.persistence;

import com.lrj.authz.governance.access.domain.AccessModels.*;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 投影回执与查询资格都从权威治理库读取，图不是第二份业务权威。 */
public interface ProjectionMapper {
    /** 每轮限定分区、条数和失败次数，终止状态意图不会无限重试。 */
    List<Projection> due(@Param("p") Partition p, @Param("limit") int limit);

    /** 任意未完成意图使本分区保持非就绪。 */
    int pending(@Param("p") Partition p);

    /** 只有当前PENDING版本可以在持久化图水位后成为ACTIVE。 */
    int activate(
            @Param("id") String id, @Param("version") long version, @Param("token") String token);

    /** 完成意图；与当前状态确认同事务，不将部分SQL结果写成成功。 */
    int complete(@Param("id") String id, @Param("version") long version);

    /** 故障只记录稳定类别，使用有界指数退避保留恢复证据。 */
    int failed(@Param("id") String id, @Param("version") long version);

    /** 当前有效候选完整匹配成员、代际、租户/应用/环境、准入和时间窗。 */
    List<Grant> eligible(
            @Param("p") Partition p,
            @Param("member") String member,
            @Param("generation") long generation);
}
