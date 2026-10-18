package com.trading.api.mapper;

import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UuidTypeHandlerTest {

    private final UuidTypeHandler handler = new UuidTypeHandler();

    @Test
    void setsParameterAsObjectOnWrite() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        UUID id = UUID.randomUUID();

        handler.setNonNullParameter(ps, 1, id, JdbcType.OTHER);

        verify(ps).setObject(1, id);
    }

    @Test
    void readsUuidDirectlyFromResultSetByColumnName() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        UUID id = UUID.randomUUID();
        when(rs.getObject("id")).thenReturn(id);

        assertThat(handler.getNullableResult(rs, "id")).isEqualTo(id);
    }

    @Test
    void parsesUuidFromStringWhenDriverReturnsString() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        UUID id = UUID.randomUUID();
        when(rs.getObject("id")).thenReturn(id.toString());

        assertThat(handler.getNullableResult(rs, "id")).isEqualTo(id);
    }

    @Test
    void returnsNullWhenColumnIsNull() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id")).thenReturn(null);

        assertThat(handler.getNullableResult(rs, "id")).isNull();
    }

    @Test
    void readsUuidByColumnIndex() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        UUID id = UUID.randomUUID();
        when(rs.getObject(1)).thenReturn(id);

        assertThat(handler.getNullableResult(rs, 1)).isEqualTo(id);
    }

    @Test
    void readsUuidFromCallableStatement() throws Exception {
        CallableStatement cs = mock(CallableStatement.class);
        UUID id = UUID.randomUUID();
        when(cs.getObject(1)).thenReturn(id);

        assertThat(handler.getNullableResult(cs, 1)).isEqualTo(id);
    }
}