package com.lrj.authz.governance.persistence;

import com.lrj.authz.governance.domain.AccessModels.Partition;
import com.lrj.authz.governance.domain.AccessReviewModels.*;
import com.lrj.authz.protocol.AccessReviewDtos.Responsible;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 人工复核仅写自身表，Grant副作用仍走原严格撤权用例；SQL全部带完整分区。 */
public interface AccessReviewMapper {
    /** 主体先于成员加共享锁，阻止写入期间生命周期撤销资格。 */
    String lockPrincipal(@Param("p") Partition p, @Param("member") String member);

    /** 锁成员事实，可为退出目标保留历史诊断，但负责人另需当前资格。 */
    String lockMember(@Param("p") Partition p, @Param("member") String member);

    /** 独立诊断配置以外还必须当前成员／主体／企业有效且管理委派开启。 */
    Responsible qualified(
            @Param("p") Partition p,
            @Param("member") String member,
            @Param("generation") long generation);

    /** 一次查询受控候选集合，避免最多100位负责人逐行回源。 */
    List<Responsible> qualifiedBatch(
            @Param("p") Partition p,
            @Param("authorities")
                    List<com.lrj.authz.governance.application.PortalDiagnosticAuthority>
                            authorities);

    /** 匹配原严格撤权命令的实际提交审计，不把其他撤销当本复核效果。 */
    boolean revokeCommitted(
            @Param("p") Partition p,
            @Param("id") String id,
            @Param("command") String command,
            @Param("version") long version);

    /** 同分区任务不存在／跨分区返回相同null，不泄漏其他任务。 */
    Task task(@Param("p") Partition p, @Param("id") String id, @Param("lock") boolean lock);

    /** 固定最多100条完整快照，稳定ID排序。 */
    List<Entry> entries(@Param("p") Partition p, @Param("id") String id);

    /** 21行仅用于判定20条列表下一页。 */
    List<Task> tasks(
            @Param("p") Partition p, @Param("member") String member, @Param("after") String after);

    /** 创建任务和每项固定输入与命令、审计同事务。 */
    int insertTask(@Param("t") Task task);

    int insertEntry(@Param("e") Entry entry);

    /** 条目CAS不能覆盖并发决定，原快照由触发器禁止修改。 */
    int decide(
            @Param("p") Partition p,
            @Param("task") String task,
            @Param("id") String id,
            @Param("version") long version,
            @Param("state") String state,
            @Param("reason") String reason,
            @Param("operation") String operation);

    /** 取消只停止未处理与需调查项，已发撤权保留检查点。 */
    int cancelEntries(
            @Param("p") Partition p, @Param("id") String id, @Param("reason") String reason);

    /** 任务CAS派生真实条目状态，取消终态不会因确认原撤权重新打开。 */
    int refresh(
            @Param("p") Partition p,
            @Param("id") String id,
            @Param("version") long version,
            @Param("cancel") boolean cancel);

    /** 显式负责人改派只改可变责任事实，旧责任记录在审计中保存。 */
    int reassign(
            @Param("p") Partition p,
            @Param("id") String id,
            @Param("version") long version,
            @Param("member") String member,
            @Param("generation") long generation);

    /** 锁Grant固定版本，正式撤权通过AccessManagement，不直接改Grant。 */
    String lockGrant(@Param("p") Partition p, @Param("id") String id);

    /** 复核专用append-only审计保存前后状态、理由和负责人，不覆盖旧意见。 */
    int audit(
            @Param("id") String id,
            @Param("p") Partition p,
            @Param("task") String task,
            @Param("item") String item,
            @Param("actor") String actor,
            @Param("command") String command,
            @Param("operation") String operation,
            @Param("before") String before,
            @Param("after") String after,
            @Param("reason") String reason);
}
