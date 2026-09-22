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

    // ── 问题1：服务端错误文案可读化 ────────────

    @Test
    public void friendlyError_invalidToken_mapsToActionableMessage() {
        // 真机截图里的 401 响应体
        String body = "{\"error\":{\"code\":\"\",\"message\":\"Invalid token "
                + "(request id: 202609221448040256686208268d9d6f3L79nvO)\","
                + "\"type\":\"new_api_error\"}}";
        String msg = ApiClient.friendlyError(401, body);
        assertEquals(ApiClient.MSG_API_KEY_INVALID, msg);
        assertFalse("不应把 request id 噪音展示给用户", msg.contains("request id"));
        assertTrue("要给可执行提示", msg.contains("API Key"));
    }

    @Test
    public void friendlyError_403_alsoMapsToActionable() {
        assertEquals(ApiClient.MSG_API_KEY_INVALID,
                ApiClient.friendlyError(403, "{\"error\":{\"message\":\"Forbidden\"}}"));
    }

    @Test
    public void friendlyError_genericError_stripsRequestId() {
        String msg = ApiClient.friendlyError(200,
                "{\"error\":{\"message\":\"No available channel (request id: abc123)\"}}");
        assertEquals("No available channel", msg);
    }

    @Test
    public void friendlyError_plainStringError() {
        assertEquals("boom", ApiClient.friendlyError(500, "{\"error\":\"boom\"}"));
    }

    @Test
    public void friendlyError_htmlBody_truncated() {
        StringBuilder html = new StringBuilder("<html>502 Bad Gateway");
        for (int i = 0; i < 50; i++) html.append(" padding");
        html.append("</html>");
        String msg = ApiClient.friendlyError(502, html.toString());
        assertTrue(msg.contains("502 Bad Gateway"));
        assertTrue("超长 HTML 必须截断", msg.length() <= 201);
    }

    @Test
    public void friendlyError_emptyBody_fallsBackToHttpCode() {
        assertEquals("HTTP 500", ApiClient.friendlyError(500, ""));
    }

    @Test
    public void extractContent_topLevelInvalidToken_mapsToActionable() {
        String json = "{\"error\":{\"code\":\"\",\"message\":\"Invalid token "
                + "(request id: xyz)\"}}";
        try {
            ApiClient.extractContent(json);
            fail("should throw");
        } catch (ApiClient.ApiException e) {
            assertEquals(ApiClient.MSG_API_KEY_INVALID, e.getMessage());
        }
    }

    // ── 问题1：未填 Key 不发请求（AGENTS §9） ──

    @Test
    public void transcribe_emptyKey_throwsBeforeNetwork() {
        try {
            ApiClient.transcribe(new byte[]{1, 2, 3}, "http://127.0.0.1:1", "", "mimo-v2.5-asr");
            fail("should throw");
        } catch (ApiClient.ApiException e) {
            assertEquals(ApiClient.MSG_NO_API_KEY, e.getMessage());
        } catch (Exception e) {
            fail("应抛 ApiException 而不是走网络: " + e);
        }
    }

    @Test
    public void transcribe_blankKey_throwsBeforeNetwork() throws Exception {
        try {
            ApiClient.transcribe(new byte[]{1}, "http://127.0.0.1:1", "   ", "m");
            fail("should throw");
        } catch (ApiClient.ApiException e) {
            assertEquals(ApiClient.MSG_NO_API_KEY, e.getMessage());
        }
    }

    @Test
    public void answerWithHistory_emptyKey_throwsBeforeNetwork() throws Exception {
        try {
            ApiClient.answerWithHistory(new String[][]{{"user", "hi"}},
                    "http://127.0.0.1:1", "", "deepseek-flash");
            fail("should throw");
        } catch (ApiClient.ApiException e) {
            assertEquals(ApiClient.MSG_NO_API_KEY, e.getMessage());
        }
    }

    // ── 问题1：错误文案与 strings.xml 保持一致 ──

    @Test
    public void messageConstants_matchStringsXml() throws Exception {
        String xml = readFile("src/main/res/values/strings.xml");
        assertTrue("strings.xml 必须有 msg_no_api_key 且与 ApiClient 一致",
                xml.contains("<string name=\"msg_no_api_key\">" + ApiClient.MSG_NO_API_KEY
                        + "</string>"));
        assertTrue("strings.xml 必须有 msg_api_key_invalid 且与 ApiClient 一致",
                xml.contains("<string name=\"msg_api_key_invalid\">" + ApiClient.MSG_API_KEY_INVALID
                        + "</string>"));
    }

    private static String readFile(String path) throws Exception {
        java.io.File f = new java.io.File(path);
        assertTrue("文件不存在: " + f.getAbsolutePath(), f.exists());
        try (java.util.Scanner s = new java.util.Scanner(f, "UTF-8")) {
            s.useDelimiter("\\A");
            return s.hasNext() ? s.next() : "";
        }
    }
}
