package com.lrj.authz.governance.persistence;

import org.apache.ibatis.annotations.Param;

/** 迁移检查点和授权效果共用治理事务；导入工具不直接写业务Grant表。 */
public interface MigrationMapper {
    record Row(
            String unitId,
            String sourceId,
            long sequence,
            long sourceVersion,
            String fingerprint,
            String snapshotHash,
            String mappingHash,
            String tenantId,
            String applicationId,
            String environment,
            String disposition,
            String grantId,
            boolean tombstone) {}

    /** 固定单元内串行，保证增量与撤销不乱序提交。 */
    void lock(@Param("unit") String unit);

    /** 单元第一条记录固定目标分区，其他来源不能换租户。 */
    Row unit(@Param("unit") String unit);

    /** 当前记录需在单元锁后读取。 */
    Row find(@Param("unit") String unit, @Param("source") String source);

    /** 主键更新只在应用完成版本及墓碑检查后执行。 */
    int save(@Param("r") Row row);
}
