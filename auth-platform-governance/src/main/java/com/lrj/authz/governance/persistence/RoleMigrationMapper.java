package com.lrj.authz.governance.persistence;

import com.lrj.authz.governance.domain.AccessModels.*;
import com.lrj.authz.governance.domain.RoleMigrationModels.Snapshot;

import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/** 角色迁移只读持久化入口，SQL固定完整分区，不提供Grant更新捷径。 */
public interface RoleMigrationMapper {
    /** 旧角色引用按UUID稳定分页，21条用于判断真实下一页。 */
    List<Grant> candidates(
            @Param("p") Partition p, @Param("role") String role, @Param("after") String after);

    /** 明确UUID集合最多50个，单语句读取全部源事实和当前代际。 */
    List<Snapshot> selected(@Param("p") Partition p, @Param("ids") List<String> ids);

    /** 剩余期限采用数据库最后时间，不能靠浏览器时钟延长。 */
    Instant now();
}
