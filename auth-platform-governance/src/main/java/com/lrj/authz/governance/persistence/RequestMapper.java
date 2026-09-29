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
    /** 申请状态CAS与Grant/Inbox同事务；不会覆盖取消终态。 */
    int decide(@Param("p") Partition p,@Param("id") String id,@Param("version") long version,
               @Param("state") com.lrj.authz.governance.domain.RequestModels.State state,@Param("grant") String grant);
    /** 展示状态必须合并SQL资格、实际投影回执与双READY栅栏。 */
    com.lrj.authz.protocol.RequestDtos.Execution execution(@Param("p") Partition p,@Param("id") String id);
    /** 最后一次尝试崩溃后也收敛为耗尽，不留永久RUNNING。 */
    int exhaustStarts(@Param("p") Partition p);
    /** 实例绑定与申请状态推进审计原子提交。 */
    int auditStart(@Param("id") String id,@Param("audit") String audit,@Param("lease") String lease);
    /** 原子领取单条到期意图；跳过他人锁且最多重试五次。 */
    String claimStart(@Param("p") Partition p,@Param("lease") String lease);
    /** 只有当前有效租约可提交远端结果。 */
    int finishStart(@Param("id") String id,@Param("lease") String lease);
    /** 失败指数退避并最终隔离，不无限重试。 */
    int failStart(@Param("id") String id,@Param("lease") String lease,@Param("error") String error);
    /** 只绑定一个实例；取消终态不能被启动回执恢复。 */
    int bindInstance(@Param("p") Partition p,@Param("id") String id,@Param("instance") String instance);
}
