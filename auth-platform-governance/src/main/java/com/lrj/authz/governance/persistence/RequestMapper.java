package com.lrj.authz.governance.persistence;

import com.lrj.authz.governance.domain.AccessModels.Partition;
import com.lrj.authz.governance.domain.RequestModels.*;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 申请聚合唯一SQL边界，所有用户查询绑定完整分区和成员代际。 */
public interface RequestMapper {
    /** 策略内容只插入，数据库拒绝改写。 */
    int insertPolicy(@Param("v") Policy policy, @Param("actor") String actor);
    /** 策略不能跨分区引用。 */
    Policy policy(@Param("p") Partition partition, @Param("id") String id);
    /** 有界策略分页，排除自批和已失效审批人。 */
    List<Policy> policies(@Param("p") Partition partition, @Param("member") String member, @Param("after") String after);
    /** 申请及快照一次写入，不产生授权。 */
    int insertRequest(@Param("v") Request request);
    /** 本人详情SQL中强制当前成员代际。 */
    Request owned(@Param("p") Partition partition, @Param("id") String id,
                  @Param("member") String member, @Param("generation") long generation);
    /** 投递器与生命周期使用的内部查询，仍绑定分区。 */
    Request request(@Param("p") Partition partition, @Param("id") String id);
    /** 每个申请唯一启动意图，必须与申请及审计同事务。 */
    int enqueueStart(@Param("id") String id);
    /** 同一代成员待处理申请数量有界。 */
    int pendingCount(@Param("p") Partition partition, @Param("member") String member, @Param("generation") long generation);
}
