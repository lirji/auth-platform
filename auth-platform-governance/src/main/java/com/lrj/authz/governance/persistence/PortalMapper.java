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
}
