package com.lrj.authz.governance.persistence;

import com.lrj.authz.governance.domain.CatalogModels.*;

import org.apache.ibatis.annotations.Param;

/** 目录SQL集中在XML，应用锁串行化同一应用的发布。 */
public interface CatalogMapper {
    /** 当前能力紧急停用时不再接受任何来源。 */
    boolean disabled(@Param("id") String id, @Param("cap") String capability);

    /** 应用冲突不覆盖原拥有者。 */
    int register(@Param("app") Application app, @Param("operator") String operator);

    /** 只读当前登记事实。 */
    Application application(@Param("id") String id);

    /** 发布锁定当前Owner身份行，避免校验后并发停用仍提交新版本。 */
    String lockOwner(
            @Param("issuer") String issuer,
            @Param("subject") String subject,
            @Param("owner") String owner);

    /** 发布事务锁，避免同版本并发覆盖。 */
    Application lockApplication(@Param("id") String id);

    /** 读取固定版本内容。 */
    Snapshot snapshot(@Param("id") String id, @Param("version") long version);

    /** 只追加，不提供更新历史清单的入口。 */
    int insertSnapshot(@Param("snapshot") Snapshot snapshot, @Param("publisher") String publisher);

    /** 展示快照与权限清单使用同一固定版本。 */
    PresentationSnapshot presentation(@Param("id") String id, @Param("version") long version);

    /** 只允许追加当前发布事务的展示快照。 */
    int insertPresentation(
            @Param("snapshot") PresentationSnapshot snapshot, @Param("publisher") String publisher);

    /** 当前指针必须匹配旧版本。 */
    int advance(
            @Param("id") String id,
            @Param("previous") long previous,
            @Param("version") long version);

    /** 审计失败时整个登记/发布事务回滚。 */
    int audit(
            @Param("id") String id,
            @Param("application") String application,
            @Param("operator") String operator,
            @Param("operation") String operation,
            @Param("version") long version,
            @Param("command") String command);
}
