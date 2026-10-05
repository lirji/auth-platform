package com.lrj.authz.governance.catalog.persistence;

import org.apache.ibatis.annotations.Param;

/** 票据／目标门禁只由同一目录写用例消费，SQL时点和有效期来自PG。 */
public interface CatalogGuardMapper {
    /** HUMAN与SERVICE票据不得交叉消费，来源关系不可变。 */
    boolean machineSource(@Param("id") String id);

    /** 当前Owner在应用锁下读取模式；没有记录表示LEGACY。 */
    PolicyRow policy(@Param("app") String app);

    /** 单向启用同时保存原原因／命令体和发布人，不提供UPDATE。 */
    int enable(
            @Param("app") String app,
            @Param("owner") String owner,
            @Param("reason") String reason,
            @Param("command") String command,
            @Param("hash") String hash);

    /** 固定预览保存整个候选，客户端不能提供期限或生成票据ID。 */
    int preview(@Param("row") PreviewRow row);

    /** 有效性使用新的PG语句时点，不能用浏览器时间作发布授权。 */
    PreviewRow ticket(@Param("id") String id);

    /** 启用记录不包含角色或Grant权限。 */
    record PolicyRow(
            String applicationId,
            String enabledBy,
            String enabledAt,
            String reason,
            String commandId,
            String commandHash) {}

    /** JSON保存已严格规范化的值，与原v1内容分开。 */
    record PreviewRow(
            String id,
            String applicationId,
            String ownerPrincipal,
            String candidateJson,
            String candidateHash,
            String previewJson,
            long baseVersion,
            String baseContentHash,
            String basePresentationHash,
            String impactJson,
            String expiresAt,
            boolean unexpired) {}
}
