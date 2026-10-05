package com.lrj.authz.governance.persistence;

import com.lrj.authz.governance.domain.AccessModels.*;
import com.lrj.authz.governance.domain.CatalogModels.CapabilityState;
import com.lrj.authz.governance.domain.ProjectionModels.Kind;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 组资格、能力紧急开关和收敛回执的固定SQL边界。 */
public interface SafetyMapper {
    /** 完整分区内查来源组，不接受浏览器提供成员集合。 */
    Group group(@Param("p") Partition p, @Param("id") String id);

    /** 有界分页列出来源组。 */
    List<Group> groups(@Param("p") Partition p, @Param("after") String after);

    /** 防止管理者通过当前组织组绕过禁止自授予。 */
    boolean groupMember(
            @Param("group") String group,
            @Param("member") String member,
            @Param("generation") long generation);

    /** 每组当前有效Grant数限制。 */
    int groupCount(@Param("p") Partition p, @Param("group") String group);

    /** 实际图回执和两份当前栅栏一起决定完成状态。 */
    RevocationReceipt receipt(@Param("p") Partition p, @Param("id") String id);

    /** 审计重试只重置执行次数，不篡改operation或图marker。 */
    int retryStream(@Param("p") Partition p, @Param("kind") Kind kind);

    /** 依赖修复后重新收敛，不能直接标READY。 */
    int retryFence(@Param("p") Partition p, @Param("kind") Kind kind);

    /** 缺少开关记录表示初始版本0且未停用。 */
    CapabilityState capability(@Param("app") String app, @Param("cap") String cap);

    /** 应用锁内推进独立能力版本。 */
    int changeCapability(
            @Param("app") String app,
            @Param("cap") String cap,
            @Param("disabled") boolean disabled,
            @Param("version") long version);

    /** 不可变命令摘要负责冲突重放判断。 */
    String commandHash(@Param("app") String app, @Param("command") String command);

    /** 命令保存精确的历史结果，不把未来状态当原结果。 */
    CapabilityState commandResult(@Param("app") String app, @Param("command") String command);

    /** 审计包含原因、操作人和前后版本，与开关原子提交。 */
    int capabilityAudit(
            @Param("app") String app,
            @Param("cap") String cap,
            @Param("command") String command,
            @Param("hash") String hash,
            @Param("actor") String actor,
            @Param("reason") String reason,
            @Param("before") long before,
            @Param("disabled") boolean disabled);
}
