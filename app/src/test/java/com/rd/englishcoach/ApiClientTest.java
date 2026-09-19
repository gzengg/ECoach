package com.rd.englishcoach;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * ApiClient 的单元测试：只测 JSON 解析逻辑（extractContent），
 * 不涉及网络。网络调用在真机上验证。
 */
public class ApiClientTest {

    // ── 正常解析 ──────────────────────────────

    @Test
    public void extractContent_normalResponse() throws Exception {
        String json = "{"
            + "\"choices\":[{"
            + "  \"index\":0,"
            + "  \"message\":{\"role\":\"assistant\","
            + "    \"content\":\"I prefer coffee in the morning.\"}"
            + "}]"
            + "}";
        assertEquals("I prefer coffee in the morning.", ApiClient.extractContent(json));
    }

    @Test
    public void extractContent_withReasoningContent() throws Exception {
        // deepseek-flash 会吐 reasoning_content，应该被忽略，只取 content
        String json = "{"
            + "\"choices\":[{"
            + "  \"index\":0,"
            + "  \"message\":{\"role\":\"assistant\","
            + "    \"reasoning_content\":\"Let me think about this...\","
            + "    \"content\":\"I'd like a cup of tea, please.\"}"
            + "}]"
            + "}";
        assertEquals("I'd like a cup of tea, please.", ApiClient.extractContent(json));
    }

    @Test
    public void extractContent_withWhitespace() throws Exception {
        String json = "{"
            + "\"choices\":[{"
            + "  \"message\":{\"content\":\"  Sure, I can help with that.  \"}"
            + "}]"
            + "}";
        assertEquals("Sure, I can help with that.", ApiClient.extractContent(json));
    }

    @Test
    public void extractContent_multilineContent() throws Exception {
        String json = "{"
            + "\"choices\":[{"
            + "  \"message\":{\"content\":\"Sure!\\nJust go straight for two blocks.\\nThen turn left.\"}"
            + "}]"
            + "}";
        assertEquals("Sure!\nJust go straight for two blocks.\nThen turn left.",
                ApiClient.extractContent(json));
    }

    // ── 错误情况 ──────────────────────────────

    @Test
    public void extractContent_topLevelError() {
        String json = "{\"error\":{\"code\":\"model_not_found\",\"message\":\"No available channel\"}}";
        try {
            ApiClient.extractContent(json);
            fail("should throw");
        } catch (ApiClient.ApiException e) {
            assertEquals(0, e.httpCode);
            assertTrue(e.getMessage().contains("No available channel"));
        }
    }

    @Test
    public void extractContent_emptyChoices() {
        String json = "{\"choices\":[]}";
        try {
            ApiClient.extractContent(json);
            fail("should throw");
        } catch (ApiClient.ApiException e) {
            assertTrue(e.getMessage().contains("empty choices"));
        }
    }

    @Test
    public void extractContent_noMessage() {
        String json = "{\"choices\":[{\"index\":0}]}";
        try {
            ApiClient.extractContent(json);
            fail("should throw");
        } catch (ApiClient.ApiException e) {
            assertTrue(e.getMessage().contains("no message"));
        }
    }

    @Test
    public void extractContent_nullContent() {
        String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\"}}]}";
        try {
            ApiClient.extractContent(json);
            fail("should throw");
        } catch (ApiClient.ApiException e) {
            assertTrue(e.getMessage().contains("empty content"));
        }
    }

    @Test
    public void extractContent_emptyContent() {
        String json = "{\"choices\":[{\"message\":{\"content\":\"\"}}]}";
        try {
            ApiClient.extractContent(json);
            fail("should throw");
        } catch (ApiClient.ApiException e) {
            assertTrue(e.getMessage().contains("empty content"));
        }
    }

    @Test
    public void extractContent_malformedJson() {
        try {
            ApiClient.extractContent("not json at all {{{");
            fail("should throw");
        } catch (ApiClient.ApiException e) {
            assertTrue(e.getMessage().contains("JSON parse error"));
        }
    }
}
