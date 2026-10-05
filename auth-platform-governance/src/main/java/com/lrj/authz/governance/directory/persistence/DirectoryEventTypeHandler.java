package com.lrj.authz.governance.directory.persistence;

import com.lrj.authz.protocol.DirectoryEvents.DirectoryAggregateType;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.*;

/** 目录类型只持久化公开稳定代码，协议包不反向依赖治理枚举。 */
public final class DirectoryEventTypeHandler extends BaseTypeHandler<DirectoryAggregateType> {
    /** 不使用枚举序号，避免增添类型改变历史数据含义。 */
    @Override
    public void setNonNullParameter(
            PreparedStatement statement, int index, DirectoryAggregateType value, JdbcType jdbcType)
            throws SQLException {
        statement.setString(index, value.code());
    }

    /** 读取未知代码会失败，不回退到员工类型。 */
    @Override
    public DirectoryAggregateType getNullableResult(ResultSet result, String column)
            throws SQLException {
        return parse(result.getString(column));
    }

    /** 索引读取保持相同严格规则。 */
    @Override
    public DirectoryAggregateType getNullableResult(ResultSet result, int column)
            throws SQLException {
        return parse(result.getString(column));
    }

    /** 存储过程读取保持相同严格规则。 */
    @Override
    public DirectoryAggregateType getNullableResult(CallableStatement result, int column)
            throws SQLException {
        return parse(result.getString(column));
    }

    private static DirectoryAggregateType parse(String code) {
        return code == null ? null : DirectoryAggregateType.fromCode(code);
    }
}
