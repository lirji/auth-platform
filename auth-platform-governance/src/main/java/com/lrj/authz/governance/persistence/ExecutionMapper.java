package com.lrj.authz.governance.persistence;

import java.time.Instant;
import org.apache.ibatis.annotations.Param;

/** 任务执行引用的唯一持久端口；有界容量与签发幂等在同一事务内维护。 */
public interface ExecutionMapper {
    record Row(String id, String caller, String application, String environment, String member,
               String requestId, String fingerprint, String contextJson, String capability,
               String resourceType, String pathsJson, Instant expiresAt) {}
    Instant now();
    void lock(@Param("member") String member);
    long active(@Param("member") String member);
    Row find(@Param("id") String id);
    Row request(@Param("caller") String caller, @Param("request") String request);
    int insert(@Param("r") Row row);
}
