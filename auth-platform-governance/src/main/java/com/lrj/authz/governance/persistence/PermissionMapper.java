package com.lrj.authz.governance.persistence;

import com.lrj.authz.governance.domain.AccessModels.Partition;
import com.lrj.authz.protocol.PermissionDtos.Audit;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 解释和审计的只读投影始终保留完整分区，普通用户额外限定本人/当前组关系。 */
public interface PermissionMapper {
    /** 历史授权仍可解释，但生效状态不由历史ACTIVE直接推出。 */
    List<Row> mine(
            @Param("p") Partition p,
            @Param("member") String member,
            @Param("generation") long generation,
            @Param("after") String after);

    /** 人员核对保留全部个人代际和历史组关系，由独立诊断资格入口调用。 */
    List<Row> memberHistory(
            @Param("p") Partition p, @Param("member") String member, @Param("after") String after);

    /** 管理诊断按ID和分区共同定位，不查询全局Grant。 */
    Row explanation(@Param("p") Partition p, @Param("id") String id);

    /** 相关原始事件和诊断事件合并后有界分页，无其他应用的全局目录。 */
    List<Audit> audit(@Param("p") Partition p, @Param("after") String after);

    /** 在独立短事务中追加访问记录，不吞失败或删除历史。 */
    int record(
            @Param("id") String id,
            @Param("p") Partition p,
            @Param("actor") String actor,
            @Param("operation") String operation,
            @Param("target") String target,
            @Param("outcome") String outcome);

    /** 持久化字符串只在应用层转换为公开有类型的范围/能力。 */
    record Row(
            String grantId,
            String memberId,
            Long generation,
            String groupId,
            String roleId,
            String roleCode,
            long roleVersion,
            String capabilitiesJson,
            String scope,
            String ruleJson,
            String sourceType,
            String sourceId,
            String validFrom,
            String validTo,
            String grantState,
            String effectiveState,
            long grantVersion,
            String operationId,
            String policyState,
            String directoryState) {}
}
