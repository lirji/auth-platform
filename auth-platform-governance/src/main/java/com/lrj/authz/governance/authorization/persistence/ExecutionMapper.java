package com.lrj.authz.governance.authorization.persistence;

import org.apache.ibatis.annotations.Param;

import java.time.Instant;

/** 任务执行引用的唯一持久端口；有界容量与签发幂等在同一事务内维护。 */
public interface ExecutionMapper {
    /** 执行引用的持久化快照，主体、分区、资源与签发请求身份必须作为同一记录消费。 */
    record Row(
            String id,
            String caller,
            String application,
            String environment,
            String member,
            String requestId,
            String fingerprint,
            String contextJson,
            String capability,
            String resourceType,
            String pathsJson,
            Instant expiresAt) {}

    /** 读取本Mapper对应表，保持原参数绑定与SQL集中在持久化边界。 */
    Instant now();

    /** 读取本Mapper对应表，保持原参数绑定与SQL集中在持久化边界。 */
    void lock(@Param("member") String member);

    /** 读取{@code auth_governance}，保持原参数绑定与SQL集中在持久化边界。 */
    long active(@Param("member") String member);

    /** 读取{@code auth_governance}，保持原参数绑定与SQL集中在持久化边界。 */
    Row find(@Param("id") String id);

    /** 读取{@code auth_governance}，保持原参数绑定与SQL集中在持久化边界。 */
    Row request(@Param("caller") String caller, @Param("request") String request);

    /** 写入{@code auth_governance}，保持原参数绑定与SQL集中在持久化边界。 */
    int insert(@Param("r") Row row);
}
