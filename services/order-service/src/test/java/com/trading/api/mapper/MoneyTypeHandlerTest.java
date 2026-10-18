package com.trading.api.mapper;

import com.trading.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MoneyTypeHandlerTest {

    @Mock
    private PreparedStatement preparedStatement;

    @Mock
    private ResultSet resultSet;

    @Mock
    private CallableStatement callableStatement;

    private MoneyTypeHandler handler;

    @BeforeEach
    void setUp() {
        handler = new MoneyTypeHandler();
    }

    @Test
    void setNonNullParameterWritesBigDecimalValue() throws Exception {
        handler.setNonNullParameter(preparedStatement, 1, Money.of("123.45"), null);

        verify(preparedStatement).setBigDecimal(1, new BigDecimal("123.45"));
    }

    @Test
    void getNullableResultByColumnNameReturnsMoneyValue() throws Exception {
        when(resultSet.getBigDecimal("cash_balance")).thenReturn(new BigDecimal("123.45"));

        Money value = handler.getNullableResult(resultSet, "cash_balance");

        assertEquals(Money.of("123.45"), value);
    }

    @Test
    void getNullableResultByIndexReturnsNullWhenColumnIsNull() throws Exception {
        when(resultSet.getBigDecimal(1)).thenReturn(null);

        Money value = handler.getNullableResult(resultSet, 1);

        assertNull(value);
    }

    @Test
    void getNullableResultFromCallableStatementReturnsMoneyValue() throws Exception {
        when(callableStatement.getBigDecimal(2)).thenReturn(new BigDecimal("50.00"));

        Money value = handler.getNullableResult(callableStatement, 2);

        assertEquals(Money.of("50.00"), value);
    }
        @Test
    void getNullableResultByColumnNameCorrectlyScalesAValueWithMorePrecision() throws Exception {
        when(resultSet.getBigDecimal("cash_balance")).thenReturn(new BigDecimal("123.456"));

        Money result = handler.getNullableResult(resultSet, "cash_balance");

        assertEquals(new BigDecimal("123.46"), result.asBigDecimal());
    }
}