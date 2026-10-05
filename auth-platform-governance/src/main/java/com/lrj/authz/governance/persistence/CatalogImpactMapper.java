package com.lrj.authz.governance.persistence;

import com.lrj.authz.governance.domain.AccessModels.Partition;
import com.lrj.authz.governance.domain.CatalogImpactModels.Cursor;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 一次SQL内聚合完整统计与有界页；SQL内当前管理基准也保护人员事实。 */
public interface CatalogImpactMapper {
    /** 返回空行表示当前资格／目录不可读，不能转换成零影响。 */
    Row report(
            @Param("p") Partition partition,
            @Param("issuer") String issuer,
            @Param("subject") String subject,
            @Param("caps") List<String> capabilities,
            @Param("cursor") Cursor cursor,
            @Param("limit") int limit,
            @Param("basisLimit") int basisLimit);

    /** 摘要不含统计时点和当前页位置，允许同一事实下稳定翻页。 */
    record Row(
            long manifestVersion,
            String contentHash,
            String presentationHash,
            String observedAt,
            String basisJson,
            String factsHash,
            long basisSize,
            long roleCount,
            long activeGrantCount,
            long pendingGrantCount,
            long activePeopleCount,
            long pendingPeopleCount,
            long peopleCount,
            long groupGrantCount,
            long requestPolicyCount,
            String fencesJson,
            String rolesJson,
            String sourcesJson,
            String policiesJson) {}
}
