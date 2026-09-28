package com.lrj.authz.governance.persistence;

import com.lrj.authz.governance.domain.IdentityModels.DbCode;
import java.sql.*;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** 身份枚举按显式稳定 code 持久化；未知数据库值拒绝，不能静默降级为有效状态。 */
public final class IdentityCodeTypeHandler<E extends Enum<E> & DbCode> extends BaseTypeHandler<E> {
    private final Class<E> type;

    /** MyBatis 为每个枚举注入具体类型，避免全局改变旧模块的枚举映射。 */
    public IdentityCodeTypeHandler(Class<E> type) { this.type = type; }

    /** 值参数绑定稳定代码。 */
    @Override public void setNonNullParameter(PreparedStatement statement, int index, E value, JdbcType jdbcType) throws SQLException {
        statement.setString(index, value.code());
    }
    /** 名称读取使用相同未知值规则。 */
    @Override public E getNullableResult(ResultSet result, String column) throws SQLException { return decode(result.getString(column)); }
    /** 位置读取使用相同未知值规则。 */
    @Override public E getNullableResult(ResultSet result, int column) throws SQLException { return decode(result.getString(column)); }
    /** 存储过程返回值同样不能放宽枚举约束。 */
    @Override public E getNullableResult(CallableStatement statement, int column) throws SQLException { return decode(statement.getString(column)); }

    private E decode(String value) throws SQLException {
        if (value == null) { return null; }
        for (E candidate : type.getEnumConstants()) { if (candidate.code().equals(value)) { return candidate; } }
        throw new SQLException("治理数据库包含未知枚举代码");
    }
}
