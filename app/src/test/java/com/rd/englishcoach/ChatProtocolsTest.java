package com.rd.englishcoach;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * ChatProtocols 纯函数单测：四种协议各自的请求体构建与响应解析。
 * 网络调用在真机上验证，不在 JVM 测试里打网络。
 */
public class ChatProtocolsTest {

    // ── OpenAI Responses ─────────────────────

    @Test
    public void responsesBody_systemGoesToInstructions() throws Exception {
        String body = ChatProtocols.buildResponsesBody("gpt-5.6-terra", new String[][]{
                {"system", "be terse"}, {"user", "hi"}, {"assistant", "hello"}, {"user", "bye"}});
        JSONObject o = new JSONObject(body);
        assertEquals("gpt-5.6-terra", o.getString("model"));
        assertEquals("be terse", o.getString("instructions"));
        assertEquals(3, o.getJSONArray("input").length());
        assertEquals("user", o.getJSONArray("input").getJSONObject(0).getString("role"));
    }

    @Test
    public void parseResponses_skipsReasoning_picksOutputText() throws Exception {
        String json = "{\"output\":["
                + "{\"type\":\"reasoning\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"thinking\"}]},"
                + "{\"type\":\"message\",\"role\":\"assistant\",\"content\":["
                + "{\"type\":\"output_text\",\"text\":\"OK\"}]}]}";
        assertEquals("OK", ChatProtocols.parseResponses(json));
    }

    @Test
    public void parseResponses_prefersOutputTextField() throws Exception {
        assertEquals("hello", ChatProtocols.parseResponses("{\"output_text\":\"  hello  \"}"));
    }

    // ── Anthropic Messages ───────────────────

    @Test
    public void anthropicBody_systemIsTopLevel_maxTokensSet() throws Exception {
        String body = ChatProtocols.buildAnthropicBody("claude-opus-5", new String[][]{
                {"system", "be terse"}, {"user", "hi"}, {"assistant", "ok"}});
        JSONObject o = new JSONObject(body);
        assertEquals("be terse", o.getString("system"));
        assertTrue("Anthropic 必须给 max_tokens", o.getInt("max_tokens") > 0);
        assertEquals(2, o.getJSONArray("messages").length());
    }

    @Test
    public void parseAnthropic_skipsThinking_picksText() throws Exception {
        String json = "{\"content\":["
                + "{\"type\":\"thinking\",\"thinking\":\"hmm\"},"
                + "{\"type\":\"text\",\"text\":\"OK\"}]}";
        assertEquals("OK", ChatProtocols.parseAnthropic(json));
    }

    // ── Gemini ───────────────────────────────

    @Test
    public void geminiUrl_appendsModelAndAction() {
        assertEquals("https://x/v1beta/models/gemini-2.5-pro:generateContent",
                ChatProtocols.geminiUrl("https://x/v1beta", "gemini-2.5-pro"));
        assertEquals("https://x/v1beta/models/gemini-2.5-pro:generateContent",
                ChatProtocols.geminiUrl("https://x/v1beta/", "gemini-2.5-pro"));
    }

    @Test
    public void geminiBody_assistantBecomesModel_systemIsInstruction() throws Exception {
        String body = ChatProtocols.buildGeminiBody(new String[][]{
                {"system", "be terse"}, {"user", "hi"}, {"assistant", "ok"}});
        JSONObject o = new JSONObject(body);
        assertEquals("be terse", o.getJSONObject("systemInstruction")
                .getJSONArray("parts").getJSONObject(0).getString("text"));
        assertEquals("user", o.getJSONArray("contents").getJSONObject(0).getString("role"));
        assertEquals("model", o.getJSONArray("contents").getJSONObject(1).getString("role"));
    }

    @Test
    public void parseGemini_skipsThoughtParts() throws Exception {
        String json = "{\"candidates\":[{\"content\":{\"parts\":["
                + "{\"text\":\"hidden\",\"thought\":true},"
                + "{\"text\":\"OK\"}]}}]}";
        assertEquals("OK", ChatProtocols.parseGemini(json));
    }

    // ── 错误与守卫 ────────────────────────────

    @Test
    public void parse_anyProtocol_surfacesTopLevelError() {
        try {
            ChatProtocols.parseAnthropic("{\"error\":{\"message\":\"Invalid token (request id: x)\"}}");
            fail("should throw");
        } catch (ApiClient.ApiException e) {
            assertEquals(ApiClient.MSG_API_KEY_INVALID, e.getMessage());
        }
    }

    @Test
    public void complete_emptyKey_throwsBeforeNetwork() {
        try {
            ChatProtocols.complete(ChatProtocols.ANTHROPIC, "http://127.0.0.1:1", "", "m",
                    new String[][]{{"user", "hi"}});
            fail("should throw");
        } catch (ApiClient.ApiException e) {
            assertEquals(ApiClient.MSG_NO_API_KEY, e.getMessage());
        } catch (Exception e) {
            fail("应抛 ApiException 而不是走网络: " + e);
        }
    }

    @Test
    public void all_containsFourProtocols() {
        assertEquals(4, ChatProtocols.ALL.length);
    }
}
