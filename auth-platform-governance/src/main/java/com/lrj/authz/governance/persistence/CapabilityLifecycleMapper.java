package com.lrj.authz.governance.persistence;

import com.lrj.authz.governance.domain.CapabilityLifecycleModels.*;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 弃用读写集中SQL；应用行锁使Owner变更与所有新增使用有明确提交顺序。 */
public interface CapabilityLifecycleMapper {
    /** 新增使用持有共享锁，Owner变更复用目录排他锁。 */
    String lockUsage(@Param("app") String application);

    /** 任一已弃用能力都会阻止新引用，历史读取不使用此门禁。 */
    boolean deprecated(@Param("app") String application, @Param("caps") List<String> capabilities);

    /** 已退役菜单不能通过新的发布重新引用，弃用菜单仍可保留。 */
    boolean retired(@Param("app") String application, @Param("caps") List<String> capabilities);

    /** 缺行由应用层显式解释为ACTIVE/version0。 */
    Value value(@Param("app") String application, @Param("cap") String capability);

    /** 比较当前版本，在同一应用锁内增加元数据版本。 */
    int change(@Param("v") Value value, @Param("before") long before);

    /** 同UUID不同载荷不能覆盖原命令。 */
    Receipt receipt(@Param("app") String application, @Param("command") String command);

    /** 原因、操作者和前后版本与元数据同事务追加。 */
    int audit(@Param("v") Receipt receipt);
}
