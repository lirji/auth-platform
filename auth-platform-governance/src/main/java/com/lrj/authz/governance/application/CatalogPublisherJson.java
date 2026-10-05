package com.lrj.authz.governance.application;

import static com.lrj.authz.governance.application.GovernanceException.Code.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.lrj.authz.governance.domain.CatalogPublisherModels.Delegation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 发布边界复用既有严格JSON规则；小命令另有限额，不接受伪造操作主体。 */
public final class CatalogPublisherJson {
    private static final int SMALL_REQUEST_BYTES = 4096;
    private static final ObjectMapper JSON =
            new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private CatalogPublisherJson() {}

    /** 小命令先裁定4KiB预算，再用已冻结的重复字段／标量／未知字段规则解析。 */
    public static <T> T small(InputStream input, Class<T> type) {
        try {
            byte[] bytes = input.readNBytes(SMALL_REQUEST_BYTES + 1);
            if (bytes.length > SMALL_REQUEST_BYTES) throw new GovernanceException(INVALID_ARGUMENT);
            return CatalogPublication.read(new ByteArrayInputStream(bytes), type);
        } catch (IOException failure) {
            throw new GovernanceException(INVALID_ARGUMENT);
        }
    }

    /** 仅序列化无凭据委派回执；事件失败必须随业务事务回滚。 */
    static String delegation(Delegation value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException failure) {
            throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
        }
    }

    /** 原事件损坏显式失败，不能推断成当前ACTIVE或空回执。 */
    static Delegation delegation(String value) {
        try {
            return CatalogPublication.read(
                    new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)),
                    Delegation.class);
        } catch (RuntimeException failure) {
            throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
        }
    }
}
