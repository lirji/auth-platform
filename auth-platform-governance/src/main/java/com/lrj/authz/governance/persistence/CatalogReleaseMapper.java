package com.lrj.authz.governance.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 发布元数据只追加；应用锁下核对命令，与目录事务共享连接。 */
public interface CatalogReleaseMapper {
    /** 历史旧版本通过快照LEFT JOIN返回，来源保持未知。 */
    List<Row> history(@Param("app") String app,@Param("before") Long before,@Param("limit") int limit);
    /** 固定版本历史包含原始发布主体和时点。 */
    Row version(@Param("app") String app,@Param("version") long version);
    /** 当前Owner认证后读取原命令，不能凭命令ID跨应用查历史。 */
    Row command(@Param("app") String app,@Param("command") String command);
    /** 同事务保存原回执，不提供UPDATE／DELETE入口。 */
    int insert(@Param("row") Row row);
    /** 存储flat来源与公开Source隔开，避免把数据库列直接当请求体。 */
    record Row(String application, long version, String contentHash, String presentationHash, String publishedBy,
               String publishedAt, String commandId, String commandHash, String sourceCommit, String artifactHash,
               String reason, String decision, Long baseVersion, String baseContentHash, String basePresentationHash, String previewJson) {}
}
