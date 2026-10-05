package com.lrj.authz.governance.persistence;

import com.lrj.authz.governance.domain.AccessModels.Partition;
import com.lrj.authz.protocol.CapabilityRetirementDtos.*;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 引用和证明写入集中SQL，不能由页面当前页推导完整计数。 */
public interface CapabilityRetirementMapper {
    /** 非敏感摘要来自同次快照，哈希最多10000行，超出不得提交。 */
    record Basis(long total, String referenceHash, List<Count> counts) {}

    /** JSON聚合由应用边界转为固定DTO。 */
    record Row(long total, String referenceHash, String countsJson, String checkedAt) {}

    /** Owner目录事实单次查询，避免逐能力读取产生N+1。 */
    record OwnerCapability(
            String code,
            String resourceType,
            String riskLevel,
            boolean disabled,
            String lifecycleState,
            long lifecycleVersion,
            String lifecycleReason) {}

    /** 单构造内部行避免协议兼容重载影响MyBatis映射。 */
    List<OwnerCapability> ownerCapabilities(@Param("app") String application);

    /** 全应用Owner摘要不返回明细。 */
    Row basis(@Param("app") String application, @Param("cap") String capability);

    /** 固定kind/id游标，每页100加1探测后续。 */
    List<Reference> references(
            @Param("p") Partition partition,
            @Param("cap") String capability,
            @Param("kind") String kind,
            @Param("after") String after);

    /** 受控运维配置可检查策略或管理委派，普通HTTP不暴露此写入口。 */
    String exitBasis(
            @Param("p") Partition partition,
            @Param("kind") String kind,
            @Param("id") String target);

    /** 只能从enabled=true单向退出，不改变能力、代际或期限。 */
    int disableReference(
            @Param("p") Partition partition,
            @Param("kind") String kind,
            @Param("id") String target);

    /** 旧表没有行版本，只保存真实前后摘要和原命令，不借用人员版本。 */
    int exitAudit(
            @Param("p") Partition partition,
            @Param("kind") String kind,
            @Param("id") String target,
            @Param("operator") String operator,
            @Param("command") String command,
            @Param("reason") String reason,
            @Param("before") String beforeHash,
            @Param("after") String afterHash);

    /** 验签原字节与生命周期命令在同事务提交，历史不可更新。 */
    int evidence(
            @Param("app") String application,
            @Param("command") String command,
            @Param("cap") String capability,
            @Param("version") long lifecycleVersion,
            @Param("manifest") long manifestVersion,
            @Param("content") String contentHash,
            @Param("presentation") String presentationHash,
            @Param("basis") String basisHash,
            @Param("proof") String proofHash,
            @Param("json") String proofJson,
            @Param("until") String validUntil);
}
