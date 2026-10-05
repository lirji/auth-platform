package com.lrj.authz.governance.persistence;

import com.lrj.authz.governance.domain.IdentityModels.*;
import com.lrj.authz.governance.domain.InvitationModels.Invitation;

import org.apache.ibatis.annotations.Param;

import java.time.Instant;

/** 邀请事务专用 SQL；与 IdentityMapper 使用同一连接和事务，无跨库读取。 */
public interface InvitationMapper {
    /** 当前真实数据库时钟，用于锁等待后的期限复核。 */
    Instant now();

    /** 邀请行串行化同邀请的接受/撤销。 */
    Invitation lock(@Param("id") String id);

    /** 创建时唯一 ID/令牌冲突由应用层转为绑定冲突。 */
    int insert(Invitation invitation);

    /** 接受时保留首次成员和代际，期限在条件更新时再检查。 */
    int accept(
            @Param("id") String id,
            @Param("version") long version,
            @Param("memberId") String memberId,
            @Param("generation") long generation);

    /** 已接受邀请不能通过撤销改变成员。 */
    int revoke(@Param("id") String id, @Param("version") long version);

    /** 锁住负责人及其主体/企业状态，避免接受事务中发生并发停用穿透。 */
    Membership lockSponsor(@Param("id") String id, @Param("tenantId") String tenantId);

    /** 只允许当前有效 HUMAN，锁防止当前接受事务跨越主体停用。 */
    Principal lockActiveHuman(@Param("id") String id);

    /** 同主体同企业现有成员决定创建、冲突或重新加入，不按邮箱合并。 */
    Membership lockMember(
            @Param("principalId") String principalId, @Param("tenantId") String tenantId);

    /** 仅 LEFT 同类型外部成员可重新加入，保留 ID 并递增代际/版本。 */
    int rejoin(@Param("member") Membership member);

    /** 邀请领域审计明细与通用审计在同一事务内保存。 */
    int auditDetail(
            @Param("auditId") String auditId,
            @Param("invitationId") String invitationId,
            @Param("reason") String reason,
            @Param("before") String before,
            @Param("after") String after,
            @Param("previousVersion") long previousVersion);
}
