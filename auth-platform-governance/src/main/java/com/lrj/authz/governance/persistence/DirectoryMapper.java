package com.lrj.authz.governance.persistence;

import com.lrj.authz.governance.application.DirectoryAuthority;
import com.lrj.authz.governance.domain.DirectoryModels.*;
import com.lrj.authz.protocol.DirectoryEvents.Event;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 目录来源、去重、投影及检查点 SQL 的唯一入口，所有语句在 XML。 */
public interface DirectoryMapper {
    /** 来源锁与完整运维权限核对后更新，触发器同事务推进租户目录epoch。 */
    int configureClock(@Param("source") String source, @Param("zone") String zone);
    /** 初次来源必须固定映射，冲突不覆盖。 */
    int insertSource(DirectoryAuthority authority);
    /** 锁定来源串行化其所有消费与检查点，禁止并行跨事件拼接事实。 */
    Source lockSource(@Param("id") String id);
    /** 只锁当前有效企业，停用与导入不会互相越过已提交状态。 */
    String lockTenant(@Param("id") String id);
    /** 已绑定来源员工可继续接收离职事实，但绝不修改全局暂停状态。 */
    com.lrj.authz.governance.domain.IdentityModels.Principal lockHuman(@Param("id") String id);
    /** 按稳定 ID 读取完整事件摘要。 */
    Inbox event(@Param("source") String source, @Param("event") String event);
    /** 分区序号同样不能对应另一个事件。 */
    Inbox sequence(@Param("source") String source, @Param("sequence") long sequence);
    /** 接受与业务、版本和审计同事务；END 尚未完整时保留未完成。 */
    int insertInbox(@Param("source") String source, @Param("event") Event event, @Param("fingerprint") String fingerprint, @Param("processed") boolean processed);
    /** 保留所有聚合版本摘要，旧版本异内容不能被当作普通过期事件。 */
    String versionHash(@Param("source") String source, @Param("type") String type, @Param("id") String id, @Param("version") long version);
    /** 来源锁保证版本唯一键在当前事务内一致。 */
    int insertVersion(@Param("source") String source, @Param("event") Event event);
    /** 最新来源事实不是权限缓存。 */
    Entry entry(@Param("source") String source, @Param("type") String type, @Param("id") String id);
    /** 同一来源成员只能属于一个员工主键。 */
    String employeeForMember(@Param("source") String source, @Param("member") String member);
    /** 更新完整最小事实，直接关系 JSON 与主体映射同事务。 */
    int saveEntry(Entry entry);
    /** Inbox之后追加，与目录、身份、投影及检查点同事务，重放不会再调用。 */
    int appendChange(ChangeEvidence evidence);
    /** 员工状态 CAS；重新加入才增加代际，手工暂停不能被修改。 */
    int changeEmployee(@Param("member") String member, @Param("tenant") String tenant, @Param("version") long version,
                       @Param("before") String before, @Param("after") String after, @Param("rejoin") boolean rejoin);
    /** 检查候选组织父链，深度溢出也拒绝，缺失父事实不推导授权。 */
    boolean invalidParent(@Param("source") String source, @Param("org") String org, @Param("parent") String parent);
    /** 读取已有快照声明以拒绝前后不一致。 */
    Snapshot snapshot(@Param("source") String source, @Param("id") String id);
    /** BEGIN/END 先建立相同声明，再分别登记到达情况。 */
    int insertSnapshot(Snapshot snapshot);
    /** 只更新指定控制记录的到达标记。 */
    int seenSnapshot(@Param("source") String source, @Param("id") String id, @Param("begin") boolean begin);
    /** 控制声明后校验此前乱序到达的同批事件没有落在声明范围之外。 */
    boolean outsideSnapshot(@Param("source") String source, @Param("id") String id, @Param("start") long start, @Param("end") long end);
    /** 有界读取快照内部事件，按分区提交序号验证完整摘要。 */
    List<Inbox> snapshotEvents(@Param("source") String source, @Param("id") String id, @Param("start") long start, @Param("end") long end);
    /** 完整证明成立后才能解除 END 的检查点阻塞。 */
    int completeSnapshot(@Param("source") String source, @Param("id") String id);
    /** 完整快照 END 与快照完成标记同事务。 */
    int completeSnapshotEnd(@Param("source") String source, @Param("id") String id, @Param("end") long end);
    /** 有界检查连续序号，不能用 MAX(sequence) 越过缺口。 */
    List<Inbox> following(@Param("source") String source, @Param("after") long after);
    /** 来源锁内条件推进检查点，保留最后完整事件摘要供源端确认。 */
    int checkpoint(@Param("source") String source, @Param("before") long before, @Param("after") long after, @Param("fingerprint") String fingerprint);
    /** 冲突事实在业务事务回滚后独立落盘，重试只增加次数。 */
    int recordConflict(@Param("source") String source, @Param("event") String event, @Param("fingerprint") String fingerprint, @Param("reason") String reason);
    /** 隔离来源，人工处置前停止继续写入身份。 */
    int quarantine(@Param("source") String source);
}
