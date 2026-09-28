package com.lrj.authz.governance.persistence;

import com.lrj.authz.governance.domain.AccessModels.*;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 授权唯一持久化入口，租户/应用/环境进入每个业务查询谓词。 */
public interface AccessMapper {
    /** 受控初始化准入，不修改已有停用事实。 */
    int registerPartition(@Param("p") Partition p,@Param("operator") String operator);
    /** 锁分区串行管理变更，避免授权数量限制和并发命令穿透。 */
    Boolean lockPartition(@Param("p") Partition p);
    /** 逐请求确认应用开通。 */
    Boolean enabled(@Param("p") Partition p);
    /** 创建固定管理上限，冲突由应用层判断。 */
    int registerDelegation(@Param("p") Partition p,@Param("d") Delegation d,@Param("operator") String operator);
    /** 当前成员代际与委派状态一起过滤。 */
    Delegation delegation(@Param("p") Partition p,@Param("member") String member,@Param("generation") long generation);
    /** 创建不可变角色版本。 */
    int insertRole(@Param("r") RoleVersion r,@Param("actor") String actor);
    /** 固定版本按完整分区查询，禁止跨应用复用ID。 */
    RoleVersion role(@Param("p") Partition p,@Param("id") String id);
    /** 同编码同版本唯一。 */
    RoleVersion roleByCode(@Param("p") Partition p,@Param("code") String code,@Param("version") long version);
    /** 创建授权路径，来源唯一。 */
    int insertGrant(@Param("g") Grant g,@Param("actor") String actor);
    /** 管理状态查询限定完整分区。 */
    Grant grant(@Param("p") Partition p,@Param("id") String id);
    /** 撤销立即改变资格；状态/版本条件防并发重写。 */
    int revoke(@Param("p") Partition p,@Param("id") String id,@Param("version") long version);
    /** 意图必须与状态变更在一个本地事务中提交。 */
    int enqueue(@Param("id") String id,@Param("version") long version,@Param("operation") String operation);
    /** 按当前成员代际限制有效授权数，防止无界判权回源。 */
    int liveCount(@Param("p") Partition p,@Param("member") String member,@Param("generation") long generation);
    /** 数据库当前时刻用于时间窗，避免宿主时区或时钟差异。 */
    java.time.Instant now();
    /** 实际有效目标同时核对主体/企业/成员状态与期限。 */
    boolean memberActive(@Param("p") Partition p,@Param("member") String member,@Param("generation") long generation);
    /** 管理列表先分区过滤再稳定游标分页。 */
    List<RoleVersion> roles(@Param("p") Partition p,@Param("after") String after);
    /** 任何状态都保留供审计诊断，不只展示成功记录。 */
    List<Grant> grants(@Param("p") Partition p,@Param("after") String after);
}
