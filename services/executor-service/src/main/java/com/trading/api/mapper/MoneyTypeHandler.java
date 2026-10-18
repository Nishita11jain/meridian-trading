package com.trading.api.mapper;

import com.trading.domain.Money;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * The domain's {@link Money} is not a raw JDBC type -- this is the one
 * place that translates between it and a plain NUMERIC(18,2) column, so
 * every mapper method can read and write real Money objects directly
 * instead of every caller converting BigDecimal to Money by hand.
 */
@MappedTypes(Money.class)
public class MoneyTypeHandler extends BaseTypeHandler<Money> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int columnIndex, Money parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setBigDecimal(columnIndex, parameter.asBigDecimal());
    }

    @Override
    public Money getNullableResult(ResultSet rs, String columnName) throws SQLException {
        BigDecimal value = rs.getBigDecimal(columnName);
        return value == null ? null : Money.of(value);
    }

    @Override
    public Money getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        BigDecimal value = rs.getBigDecimal(columnIndex);
        return value == null ? null : Money.of(value);
    }

    @Override
    public Money getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        BigDecimal value = cs.getBigDecimal(columnIndex);
        return value == null ? null : Money.of(value);
    }
}
