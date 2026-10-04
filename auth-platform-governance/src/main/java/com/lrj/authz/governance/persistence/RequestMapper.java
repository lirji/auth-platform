package com.lrj.authz.governance.persistence;

import com.lrj.authz.governance.domain.AccessModels.Partition;
import com.lrj.authz.governance.domain.RequestModels.*;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 申请聚合唯一SQL边界，所有用户查询绑定完整分区和成员代际。 */
public interface RequestMapper {
    /** 批量核对固定能力当前紧急开关，避免逐能力回源。 */
    boolean disabledCapabilities(@Param("p") Partition p,@Param("capabilities") List<String> capabilities);
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
    /** 原OA来源必须绑定实际申请，不能凭来源字符串伪造批准。 */
    Request byGrant(@Param("p") Partition p,@Param("grant") String grant);
    /** 同原来源／目标角色唯一的当前新批准，不将旧批准当新版批准。 */
    Request migrationApproved(@Param("p") Partition p,@Param("oldGrant") String oldGrant,@Param("role") String role);
    /** 同分区有界读取尚未授新的关联申请，原申请撤回时一并停止。 */
    List<Request> migrationOpen(@Param("p") Partition p,@Param("oldGrant") String oldGrant);
    /** 新批准与实际迁移Grant同事务绑定，已取消状态不能被恢复。 */
    int bindMigrationGrant(@Param("p") Partition p,@Param("id") String id,@Param("version") long version,@Param("grant") String grant);
    /** 每个申请唯一启动意图，必须与申请及审计同事务。 */
    int enqueueStart(@Param("id") String id);
    /** 同一代成员待处理申请数量有界。 */
    int pendingCount(@Param("p") Partition partition, @Param("member") String member, @Param("generation") long generation);
    /** 申请状态CAS与Grant/Inbox同事务；不会覆盖取消终态。 */
    int decide(@Param("p") Partition p,@Param("id") String id,@Param("version") long version,
               @Param("state") com.lrj.authz.governance.domain.RequestModels.State state,@Param("grant") String grant);
    /** 展示状态必须合并SQL资格、实际投影回执与双READY栅栏。 */
    com.lrj.authz.protocol.RequestDtos.Execution execution(@Param("p") Partition p,@Param("id") String id);
    /** 取消与批准共用分区锁；申请只前进，保留批准事实以展示回收。 */
    int cancel(@Param("p") Partition p,@Param("id") String id,@Param("version") long version,
               @Param("state") com.lrj.authz.governance.domain.RequestModels.State state);
    /** 停止尚未领取的启动意图；在途结果仍允许保存实际远端实例用于追溯。 */
    int stopStart(@Param("id") String id);
    /** 扫描最多100条到期或当前成员失效记录，已回收来源不重复领取。 */
    List<String> expired(@Param("p") Partition p);
    /** 本人所有状态有界分页，数据库过滤当前成员代际。 */
    List<Request> mine(@Param("p") Partition p,@Param("member") String member,@Param("generation") long generation,@Param("after") String after);
    /** 站内通知不返回内部审批人或权限清单。 */
    List<com.lrj.authz.protocol.RequestDtos.Notice> notices(@Param("p") Partition p,@Param("member") String member,@Param("generation") long generation,@Param("after") String after);
    /** 耗尽崩溃租约进入可查死信。 */
    int exhaustNotices(@Param("p") Partition p);
    /** 独立事务领取一条通知，最多五次。 */
    String claimNotice(@Param("p") Partition p,@Param("lease") String lease);
    /** 同通知ID只投递一次站内消息。 */
    int insertNotice(@Param("id") String id,@Param("lease") String lease);
    /** 只有当前租约可提交投递回执。 */
    int finishNotice(@Param("id") String id,@Param("lease") String lease);
    /** 通知失败只更新通知任务，不更新申请或Grant。 */
    int failNotice(@Param("id") String id,@Param("lease") String lease);
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
