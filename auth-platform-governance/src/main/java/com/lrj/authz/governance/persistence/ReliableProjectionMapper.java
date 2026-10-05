package com.lrj.authz.governance.persistence;

import com.lrj.authz.governance.domain.FenceModels.Fence;
import com.lrj.authz.governance.domain.ProjectionModels.*;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 所有领取、规划和回执SQL集中在所属治理数据库，不跨服务写表。 */
public interface ReliableProjectionMapper {
    /** 目录边同样51项探测、50项执行，包括退出的历史边。 */
    java.util.List<GroupChange> groups(@Param("t") Target target,@Param("after") String after);
    /** 真实回执细化到Grant版本，供撤权完成查询。 */
    int grantReceipt(@Param("g") GrantChange grant,@Param("operation") String operation);
    /** 按真实栅栏外键登记幂等执行流。 */
    int register(@Param("t") Target target);
    /** 数据库时间抢占到期租约并增加代际，返回当前固定领取快照。 */
    Stream claim(@Param("id") String id, @Param("worker") String worker);
    /** 当前租约必须尚未过期且代际相同，FOR UPDATE串行确认/规划。 */
    Stream owned(@Param("lease") Stream lease);
    /** 当前分区权威版本，不使用调用方缓存的desired。 */
    Fence fence(@Param("t") Target target);
    /** 当前唯一待执行固定操作。 */
    Operation pending(@Param("id") String fenceId);
    /** 只接受当前分区已持久化的marker，不能认领其他分区操作。 */
    Operation operation(@Param("fence") String fenceId, @Param("id") String id);
    /** 每次51条探测完整性，实际写图批次不超过50条。 */
    List<GrantChange> grants(@Param("t") Target target, @Param("after") String after);
    /** 批号跨目标单调递增，禁止重用旧操作标识。 */
    int advanceBatch(@Param("id") String id, @Param("before") long before);
    /** 不可变payload与图前置marker在同一规划事务持久化。 */
    int insert(@Param("o") Operation operation);
    /** 新目标取代未完成旧规划，但记录不能删除。 */
    int supersede(@Param("id") String id);
    /** 真实远端确认之后才写receipt；重复确认不创建第二回执。 */
    int receipt(@Param("o") Operation operation, @Param("token") String token, @Param("worker") String worker);
    /** 只推进确认序列，不允许回退已确认marker。 */
    int confirmStream(@Param("o") Operation operation, @Param("token") String token);
    /** 操作终态只在receipt同事务完成。 */
    int applied(@Param("id") String id);
    /** 仍为规划版本PENDING的Grant才可以激活，不覆盖撤销。 */
    int activate(@Param("g") GrantChange grant, @Param("token") String token);
    /** P2可靠意图同步结清，被本批当前版本覆盖的旧意图也可结清。 */
    int completeLegacy(@Param("g") GrantChange grant);
    /** 末批且desired仍匹配时CAS到READY；并发新目标保留UPDATING。 */
    int ready(@Param("t") Target target, @Param("o") Operation operation, @Param("token") String token);
    /** 当前固定操作记一次失败尝试，不改写执行内容。 */
    int attempted(@Param("id") String fenceId);
    /** 仅当前租约且远端marker已确认一致的READY轮次结束连续失败预算，不改变栅栏或操作回执。 */
    int successfulRead(@Param("lease") Stream lease);
    /** 有界退避并记录失败次数，不将未知结果当作未写入。 */
    int failed(@Param("lease") Stream lease);
    /** 永久冲突或耗尽重试隔离分区，普通管理更新不会清除。 */
    int block(@Param("t") Target target);
    /** 归还当前租约不能释放另一个进程的新代际。 */
    int release(@Param("lease") Stream lease);
}
