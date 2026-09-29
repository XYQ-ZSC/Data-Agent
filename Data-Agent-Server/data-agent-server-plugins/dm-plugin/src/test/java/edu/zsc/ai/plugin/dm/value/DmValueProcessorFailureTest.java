package edu.zsc.ai.plugin.dm.value;

import edu.zsc.ai.plugin.value.JdbcValueContext;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DmValueProcessorFailureTest {
    @Test
    void decimalReadFailureIsNotSilentlyConvertedToText() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        SQLException failure = new SQLException("decimal conversion failed");
        when(resultSet.getObject(1)).thenReturn("123");
        when(resultSet.getBigDecimal(1)).thenThrow(failure);
        JdbcValueContext context = JdbcValueContext.builder()
                .resultSet(resultSet).columnIndex(1).columnTypeName("NUMBER").build();

        assertSame(failure, assertThrows(SQLException.class,
                () -> DmValueProcessor.INSTANCE.getJdbcValue(context)));
    }
}
