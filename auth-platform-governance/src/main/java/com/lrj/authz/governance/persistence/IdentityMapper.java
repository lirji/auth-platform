package com.lrj.authz.governance.persistence;

import com.lrj.authz.governance.domain.IdentityModels.*;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 治理持久化入口，所有 SQL 位于 XML；业务服务不得拼接 SQL。 */
public interface IdentityMapper {
    /** 当前身份、租户、主体与成员状态必须在同一查询内成立，不接受调用方 principal。 */
    CurrentContext currentContext(@Param("issuer") String issuer, @Param("subject") String subject,
                                  @Param("tenantId") String tenantId);
    /** 创建时保留冲突事实，由应用层核验映射而非覆盖。 */
    int insertPrincipal(Principal principal);
    /** 读取当前主体状态，不走正向授权缓存。 */
    Principal principal(@Param("id") String id);
    /** 登录键唯一，冲突后不重新绑定主体。 */
    int insertLoginIdentity(LoginIdentity identity);
    /** 精确按发行方和 subject 解析。 */
    LoginIdentity loginIdentity(@Param("issuer") String issuer, @Param("subject") String subject);
    /** 企业编码与 ID 冲突必须由应用层处理。 */
    int insertTenant(Tenant tenant);
    /** 当前企业状态用于每次成员校验。 */
    Tenant tenant(@Param("id") String id);
    /** 唯一企业/主体成员记录，不能制造平行成员。 */
    int insertMembership(Membership membership);
    /** 读取对象，无有效性推断。 */
    Membership membership(@Param("id") String id);
    /** 当前有效成员同时校验主体和企业状态，时间来自权威数据库。 */
    List<Membership> activeMemberships(@Param("principalId") String principalId);
    /** 同一来源引用不得重新指向其他主体。 */
    int insertLegacyBinding(LegacyBinding binding);
    /** 受控旧映射读取，不修改旧库主键。 */
    LegacyBinding legacyBinding(@Param("sourceSystem") String sourceSystem,
                                 @Param("sourceTenantRef") String sourceTenantRef,
                                 @Param("sourceSubjectRef") String sourceSubjectRef);
    /** 并发相同幂等键由数据库唯一键串行，后续读锁核对摘要。 */
    int reserveCommand(@Param("operatorRef") String operatorRef, @Param("tenantId") String tenantId,
                       @Param("operation") String operation, @Param("commandId") String commandId,
                       @Param("payloadHash") String payloadHash);
    /** 必须在应用事务内读取，阻止同一命令的并行副作用。 */
    CommandRow lockCommand(@Param("operatorRef") String operatorRef, @Param("tenantId") String tenantId,
                            @Param("operation") String operation, @Param("commandId") String commandId);
    /** 完成占位，影响行数必须为一。 */
    int completeCommand(@Param("operatorRef") String operatorRef, @Param("tenantId") String tenantId,
                        @Param("operation") String operation, @Param("commandId") String commandId,
                        @Param("resultRef") String resultRef);
    /** 审计持久化失败必须让当前事务回滚。 */
    int appendAudit(@Param("id") String id, @Param("operatorRef") String operatorRef,
                    @Param("tenantId") String tenantId, @Param("operation") String operation,
                    @Param("targetId") String targetId, @Param("targetVersion") long targetVersion,
                    @Param("commandId") String commandId);
    /** 生命周期底层 CAS，不接受忽略版本的状态覆盖；由受控应用命令调用。 */
    int compareAndSetMemberStatus(@Param("id") String id, @Param("expectedVersion") long expectedVersion,
                                   @Param("oldStatus") MemberStatus oldStatus,
                                   @Param("newStatus") MemberStatus newStatus);

    /** 企业和版本一起进入 CAS 条件，防止跨企业停用或旧版本覆盖。 */
    int suspendMember(@Param("id") String id, @Param("tenantId") String tenantId, @Param("expectedVersion") long expectedVersion);
    /** 全局主体停用不改各企业成员记录；入口必须先具备全局范围。 */
    int suspendPrincipal(@Param("id") String id, @Param("expectedVersion") long expectedVersion);
    /** 生命周期审计必须完整记录原因与前后状态/版本，不能复用缺少原因的旧写入。 */
    int appendLifecycleAudit(@Param("id") String id, @Param("operatorRef") String operatorRef,
                             @Param("tenantId") String tenantId, @Param("operation") String operation,
                             @Param("targetId") String targetId, @Param("targetVersion") long targetVersion,
                             @Param("commandId") String commandId, @Param("reason") String reason,
                             @Param("previousStatus") String previousStatus, @Param("resultingStatus") String resultingStatus,
                             @Param("previousVersion") long previousVersion);

    /** 命令结果是引用，重放读取当前事实而非过期授权快照。 */
    record CommandRow(String payloadHash, String resultRef, boolean completed) {}
}
