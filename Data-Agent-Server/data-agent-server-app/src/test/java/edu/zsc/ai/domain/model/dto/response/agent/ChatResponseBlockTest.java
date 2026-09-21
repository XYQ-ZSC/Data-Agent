package edu.zsc.ai.domain.model.dto.response.agent;

import edu.zsc.ai.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatResponseBlockTest {

    @Test
    void toolCallIncludesDescriptionAndTimingMetadata() {
        ChatResponseBlock block = ChatResponseBlock.toolCall(
                "call-1",
                "getDatabases",
                "{\"connectionId\":8,\"description\":\"查询可用数据库\"}",
                false,
                "查询可用数据库",
                1_000L
        );

        Map<?, ?> data = JsonUtil.json2Object(block.getData(), Map.class);
        assertEquals("查询可用数据库", data.get("description"));
        assertEquals(1_000, data.get("startedAt"));
        assertEquals(false, data.get("streaming"));
    }

    @Test
    void toolResultIncludesDescriptionAndTimingMetadata() {
        ChatResponseBlock block = ChatResponseBlock.toolResult(
                "call-1",
                "getDatabases",
                "{}",
                false,
                "查询可用数据库",
                1_000L,
                4_200L
        );

        Map<?, ?> data = JsonUtil.json2Object(block.getData(), Map.class);
        assertEquals("查询可用数据库", data.get("description"));
        assertEquals(1_000, data.get("startedAt"));
        assertEquals(4_200, data.get("finishedAt"));
        assertEquals(false, data.get("error"));
    }

    @Test
    void extractToolDescriptionReadsPlainAndDoubleEncodedArguments() {
        assertEquals("检查订单表结构", ChatResponseBlock.extractToolDescription(
                "{\"description\":\"检查订单表结构\"}"
        ));

        assertEquals("检查订单表结构", ChatResponseBlock.extractToolDescription(
                "\"{\\\"description\\\":\\\"检查订单表结构\\\"}\""
        ));
    }

    @Test
    void extractToolDescriptionIgnoresBlankOrInvalidArguments() {
        assertNull(ChatResponseBlock.extractToolDescription("{}"));
        assertNull(ChatResponseBlock.extractToolDescription("not-json"));
    }
}
