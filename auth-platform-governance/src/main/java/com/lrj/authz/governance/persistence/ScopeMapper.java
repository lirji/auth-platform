package com.lrj.authz.governance.persistence;

import com.lrj.authz.governance.domain.AccessModels.Partition;
import com.lrj.authz.governance.domain.ScopeModels.GrantPath;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 范围查询保留完整Grant，不将角色权限与其他来源的数据范围做全局并集。 */
public interface ScopeMapper {
    /** 固定角色、固定范围、当前成员及时间在一条有界SQL里连接。 */
    List<GrantPath> eligible(@Param("p") Partition partition, @Param("member") String member, @Param("generation") long generation);
}
