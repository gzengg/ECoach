package com.rd.englishcoach;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * AsrProtocols 纯函数单测：请求体构建与响应解析。
 */
public class AsrProtocolsTest {

    @Test
    public void chatAudioBody_usesRawBase64_notDataUri() throws Exception {
        // OpenAI input_audio 用裸 base64 + format 字段（与 DashScope 的 data URI 不同）
        String body = AsrProtocols.buildChatAudioBody("mimo-v2.5-asr", new byte[]{1, 2, 3, 4});
        JSONObject o = new JSONObject(body);
        assertEquals("mimo-v2.5-asr", o.getString("model"));
        JSONObject audio = o.getJSONArray("messages").getJSONObject(0)
                .getJSONArray("content").getJSONObject(0).getJSONObject("input_audio");
        assertEquals("wav", audio.getString("format"));
        assertFalse("不能带 data URI 前缀", audio.getString("data").startsWith("data:"));
        assertEquals("AQIDBA==", audio.getString("data"));
    }

    @Test
    public void parseTranscription_readsText() throws Exception {
        assertEquals("hello world",
                AsrProtocols.parseTranscription("{\"text\":\"  hello world  \"}"));
    }

    @Test
    public void parseTranscription_emptyTextIsNotError() throws Exception {
        assertEquals("", AsrProtocols.parseTranscription("{\"text\":\"\"}"));
    }

    @Test
    public void parseTranscription_errorIsReadable() {
        try {
            AsrProtocols.parseTranscription("{\"error\":{\"message\":\"Invalid token (request id: x)\"}}");
            fail("should throw");
        } catch (ApiClient.ApiException e) {
            assertEquals(ApiClient.MSG_API_KEY_INVALID, e.getMessage());
        }
    }

    @Test
    public void transcribe_emptyKey_throwsBeforeNetwork() {
        try {
            AsrProtocols.transcribe(AsrProtocols.CHAT_AUDIO, "http://127.0.0.1:1", "", "m", new byte[]{1});
            fail("should throw");
        } catch (ApiClient.ApiException e) {
            assertEquals(ApiClient.MSG_NO_API_KEY, e.getMessage());
        } catch (Exception e) {
            fail("应抛 ApiException 而不是走网络: " + e);
        }
    }

    @Test
    public void all_containsThreeProtocols() {
        assertEquals(3, AsrProtocols.ALL.length);
    }
}
