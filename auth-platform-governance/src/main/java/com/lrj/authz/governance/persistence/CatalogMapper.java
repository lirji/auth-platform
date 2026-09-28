package com.lrj.authz.governance.persistence;

import com.lrj.authz.governance.domain.CatalogModels.*;
import org.apache.ibatis.annotations.Param;

/** 目录SQL集中在XML，应用锁串行化同一应用的发布。 */
public interface CatalogMapper {
    /** 当前能力紧急停用时不再接受任何来源。 */
    boolean disabled(@Param("id") String id,@Param("cap") String capability);
    /** 应用冲突不覆盖原拥有者。 */
    int register(@Param("app") Application app, @Param("operator") String operator);
    /** 只读当前登记事实。 */
    Application application(@Param("id") String id);
    /** 发布事务锁，避免同版本并发覆盖。 */
    Application lockApplication(@Param("id") String id);
    /** 读取固定版本内容。 */
    Snapshot snapshot(@Param("id") String id, @Param("version") long version);
    /** 只追加，不提供更新历史清单的入口。 */
    int insertSnapshot(@Param("snapshot") Snapshot snapshot, @Param("publisher") String publisher);
    /** 当前指针必须匹配旧版本。 */
    int advance(@Param("id") String id, @Param("previous") long previous, @Param("version") long version);
    /** 审计失败时整个登记/发布事务回滚。 */
    int audit(@Param("id") String id, @Param("application") String application, @Param("operator") String operator,
              @Param("operation") String operation, @Param("version") long version, @Param("command") String command);
}
