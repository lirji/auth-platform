package com.lrj.authz.governance.persistence;

import com.lrj.authz.governance.application.PortalDirectory.Candidate;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 本人关联应用的有界目录，SQL中始终保留租户和当前成员代际。 */
public interface PortalMapper {
    /** 只扫描存在本人授权来源、组关联或管理委派的已开通分区，不公开企业应用全目录。 */
    List<Candidate> applications(@Param("tenant") String tenant, @Param("member") String member,
                                 @Param("generation") long generation, @Param("application") String application,
                                 @Param("environment") String environment);
    /** 当前租户有效成员的有界选项，不联接登录凭据。 */
    List<com.lrj.authz.protocol.PortalDtos.Member> members(@Param("tenant") String tenant, @Param("after") String after);
    /** 批量读取紧急停用集合，避免逐能力回源。 */
    List<String> disabledCapabilities(@Param("application") String application);
    /** 严格投影状态直接来自权威水位，不由页面猜测。 */
    com.lrj.authz.protocol.PortalDtos.Progress progress(@Param("p") com.lrj.authz.governance.domain.AccessModels.Partition p);
    /** 比较同编码前一个不可变版本，不修改历史快照。 */
    com.lrj.authz.governance.domain.AccessModels.RoleVersion previousRole(@Param("p") com.lrj.authz.governance.domain.AccessModels.Partition p, @Param("code") String code, @Param("version") long version);
    /** 引用数始终带完整分区谓词。 */
    long referencingGrants(@Param("p") com.lrj.authz.governance.domain.AccessModels.Partition p, @Param("role") String role);

}
