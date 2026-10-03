package com.lrj.authz.governance.persistence;

import com.lrj.authz.governance.domain.AccessModels.Partition;
import com.lrj.authz.governance.domain.RoleMigrationTaskModels.*;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 迁移专用持久入口，所有SQL集中XML，所有对象读取带管理分区。 */
public interface RoleMigrationTaskMapper {
    /** 准入行锁之后再锁任务，避免和正式授权反向锁序。 */
    Task task(@Param("p") Partition p,@Param("id") String id,@Param("lock") boolean lock);
    /** 一个分区一页21行用于20条游标分页。 */
    List<Task> tasks(@Param("p") Partition p,@Param("oldRole") String oldRole,@Param("after") String after);
    /** 固定子项集合最多50条，不无界展开业务来源。 */
    List<Entry> entries(@Param("p") Partition p,@Param("task") String task);
    /** 重复固定谱系拒绝，不覆盖历史失败或已完成项。 */
    boolean lineageExists(@Param("p") Partition p,@Param("oldGrant") String oldGrant,@Param("newRole") String newRole);
    /** 既有来源碰撞在撤旧之前发现。 */
    boolean sourceExists(@Param("p") Partition p,@Param("member") String member,@Param("generation") long generation,@Param("source") String source);
    /** 创建固定任务，唯一和外键由数据库最终保护。 */
    int insertTask(@Param("t") Task task);
    /** 固定计划只追加，检查点通过CAS更新。 */
    int insertEntry(@Param("e") Entry entry);
    /** 状态版本CAS与副作用处于同一短事务。 */
    int checkpoint(@Param("p") Partition p,@Param("e") Entry entry,@Param("stage") Stage stage,
            @Param("reason") String reason,@Param("revokeReceipt") String revokeReceipt,
            @Param("newGrant") String newGrant,@Param("newReceipt") String newReceipt);
    /** 取消未完成子项，不触碰已提交的Grant或投影意图。 */
    int cancelEntries(@Param("p") Partition p,@Param("task") String task);
    /** 每次检查点后聚合任务状态并推进任务版本。 */
    int refresh(@Param("p") Partition p,@Param("t") Task task,@Param("cancel") boolean cancel);
    /** 主库回执核验，不把单独ACTIVE误报为真实生效。 */
    Confirmation confirmation(@Param("p") Partition p,@Param("id") String grant,@Param("version") long version,@Param("revoked") boolean revoked);
}
