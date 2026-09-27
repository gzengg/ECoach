package com.rd.englishcoach;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * TtsProtocols 纯函数单测：请求体构建、响应解析、默认音色。
 */
public class TtsProtocolsTest {

    // ── OpenAI /audio/speech ─────────────────

    @Test
    public void speechBody_hasInputVoiceWavFormat() throws Exception {
        JSONObject o = new JSONObject(TtsProtocols.buildSpeechBody("gpt-4o-mini-tts", "alloy", "Hi"));
        assertEquals("gpt-4o-mini-tts", o.getString("model"));
        assertEquals("Hi", o.getString("input"));
        assertEquals("alloy", o.getString("voice"));
        assertEquals("wav", o.getString("response_format"));
    }

    @Test
    public void speechBody_emptyVoice_fallsBackToAlloy() throws Exception {
        JSONObject o = new JSONObject(TtsProtocols.buildSpeechBody("m", "", "Hi"));
        assertEquals("alloy", o.getString("voice"));
    }

    // ── MiniMax T2A ───────────────────────────

    @Test
    public void minimaxBody_hasVoiceAndAudioSettings() throws Exception {
        JSONObject o = new JSONObject(TtsProtocols.buildMinimaxBody("minimax-speech-2.8-turbo",
                "male-qn-qingse", "你好"));
        assertEquals("male-qn-qingse", o.getJSONObject("voice_setting").getString("voice_id"));
        assertEquals("wav", o.getJSONObject("audio_setting").getString("format"));
        assertEquals("你好", o.getString("text"));
    }

    @Test
    public void parseMinimax_decodesHexAudio() throws Exception {
        String json = "{\"data\":{\"audio\":\"0a0b0c\"},\"extra_info\":{\"usage_characters\":2}}";
        byte[] b = TtsProtocols.parseMinimax(json);
        assertArrayEquals(new byte[]{10, 11, 12}, b);
    }

    @Test
    public void parseMinimax_surfacesBaseRespError() {
        // 音色无效时 MiniMax 返回 base_resp 错误、没有音频；不能只报「no audio」把真因盖掉
        String json = "{\"base_resp\":{\"status_code\":1004,\"status_msg\":\"voice id not exist\"}}";
        try {
            TtsProtocols.parseMinimax(json);
            fail("should throw");
        } catch (java.io.IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("voice id not exist"));
        }
    }

    // ── 豆包 Ark TTS ──────────────────────────

    @Test
    public void arkBody_requestsPcm_notWav() throws Exception {
        // SSE 分帧的 wav 拼接不是合法 wav，必须请求 pcm 再自己封头
        JSONObject o = new JSONObject(TtsProtocols.buildArkBody("你好", "zh_female_vv_uranus_bigtts", 24000));
        JSONObject ap = o.getJSONObject("req_params").getJSONObject("audio_params");
        assertEquals("pcm", ap.getString("format"));
        assertEquals(24000, ap.getInt("sample_rate"));
        assertEquals("zh_female_vv_uranus_bigtts", o.getJSONObject("req_params").getString("speaker"));
    }

    @Test
    public void hexToBytes_roundTrip() {
        assertArrayEquals(new byte[]{0, 15, -1}, TtsProtocols.hexToBytes("000fff"));
    }

    // ── 默认音色 ─────────────────────────────

    @Test
    public void defaultVoices_perProtocol() {
        assertNull("chat-tts 用 Prefs 里的 mimo 列表", TtsProtocols.defaultVoices(TtsProtocols.CHAT_TTS));
        assertTrue(TtsProtocols.defaultVoices(TtsProtocols.SPEECH).length > 0);
        assertTrue(TtsProtocols.defaultVoices(TtsProtocols.MINIMAX_T2A).length > 0);
        assertTrue(TtsProtocols.defaultVoices(TtsProtocols.ARK_TTS).length > 0);
    }

    @Test
    public void synthesize_emptyKey_throwsBeforeNetwork() {
        try {
            TtsProtocols.synthesize(TtsProtocols.SPEECH, "http://127.0.0.1:1", "", "m", "alloy", "hi");
            fail("should throw");
        } catch (ApiClient.ApiException e) {
            assertEquals(ApiClient.MSG_NO_API_KEY, e.getMessage());
        } catch (Exception e) {
            fail("应抛 ApiException 而不是走网络: " + e);
        }
    }

    @Test
    public void all_containsFourProtocols() {
        assertEquals(4, TtsProtocols.ALL.length);
    }
}
