package edu.zsc.ai.plugin.dm.value;

import org.junit.jupiter.api.Test;

import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DmDataTypeEnumTest {

    @Test
    void resolvesSimpleTypeNames() {
        assertEquals(DmDataTypeEnum.NUMBER, DmDataTypeEnum.fromTypeName("NUMBER"));
        assertEquals(DmDataTypeEnum.VARCHAR2, DmDataTypeEnum.fromTypeName("VARCHAR2"));
        assertEquals(DmDataTypeEnum.CLOB, DmDataTypeEnum.fromTypeName("CLOB"));
        assertEquals(DmDataTypeEnum.BIT, DmDataTypeEnum.fromTypeName("BIT"));
    }

    @Test
    void resolvesCaseInsensitivelyAndTrims() {
        assertEquals(DmDataTypeEnum.VARCHAR2, DmDataTypeEnum.fromTypeName("varchar2"));
        assertEquals(DmDataTypeEnum.NUMBER, DmDataTypeEnum.fromTypeName("  number  "));
    }

    @Test
    void normalizesSpacesToUnderscores() {
        assertEquals(DmDataTypeEnum.TIMESTAMP_WITH_TIME_ZONE,
                DmDataTypeEnum.fromTypeName("TIMESTAMP WITH TIME ZONE"));
        assertEquals(DmDataTypeEnum.TIMESTAMP_WITH_LOCAL_TIME_ZONE,
                DmDataTypeEnum.fromTypeName("timestamp with local time zone"));
        assertEquals(DmDataTypeEnum.DOUBLE_PRECISION,
                DmDataTypeEnum.fromTypeName("DOUBLE PRECISION"));
    }

    @Test
    void stripsPrecisionAndScaleSuffix() {
        assertEquals(DmDataTypeEnum.DECIMAL, DmDataTypeEnum.fromTypeName("DECIMAL(10,2)"));
        assertEquals(DmDataTypeEnum.NUMBER, DmDataTypeEnum.fromTypeName("NUMBER(38)"));
        assertEquals(DmDataTypeEnum.VARCHAR2, DmDataTypeEnum.fromTypeName("VARCHAR2(255)"));
        assertEquals(DmDataTypeEnum.TIMESTAMP_WITH_TIME_ZONE,
                DmDataTypeEnum.fromTypeName("TIMESTAMP(6) WITH TIME ZONE"));
    }

    @Test
    void returnsNullForUnknownOrEmptyNames() {
        assertNull(DmDataTypeEnum.fromTypeName("NOT_A_TYPE"));
        assertNull(DmDataTypeEnum.fromTypeName(null));
        assertNull(DmDataTypeEnum.fromTypeName(""));
    }

    @Test
    void mapsToExpectedJdbcSqlTypes() {
        assertEquals(Types.DECIMAL, DmDataTypeEnum.NUMBER.getSqlType());
        assertEquals(Types.VARCHAR, DmDataTypeEnum.VARCHAR2.getSqlType());
        assertEquals(Types.TIMESTAMP, DmDataTypeEnum.DATE.getSqlType());
        assertEquals(Types.TIMESTAMP_WITH_TIMEZONE,
                DmDataTypeEnum.TIMESTAMP_WITH_TIME_ZONE.getSqlType());
        assertEquals(Types.BIT, DmDataTypeEnum.BOOLEAN.getSqlType());
    }

    @Test
    void toSqlTypeFallsBackToOther() {
        assertEquals(Types.DECIMAL, DmDataTypeEnum.toSqlType("NUMBER"));
        assertEquals(Types.VARCHAR, DmDataTypeEnum.toSqlType("VARCHAR2(100)"));
        assertEquals(Types.OTHER, DmDataTypeEnum.toSqlType("SOMETHING_ELSE"));
        assertEquals(Types.OTHER, DmDataTypeEnum.toSqlType(null));
    }
}
