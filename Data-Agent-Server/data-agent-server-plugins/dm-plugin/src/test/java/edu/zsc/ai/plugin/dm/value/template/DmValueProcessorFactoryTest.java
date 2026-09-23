package edu.zsc.ai.plugin.dm.value.template;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class DmValueProcessorFactoryTest {

    @Test
    void routesTimestampPrecisionWithTimeZoneToTimeZoneProcessor() {
        assertInstanceOf(DmTimestampWithTimeZoneProcessor.class,
                DmValueProcessorFactory.getValueProcessor("TIMESTAMP(6) WITH TIME ZONE"));
    }
}
