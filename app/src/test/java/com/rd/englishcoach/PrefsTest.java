package com.rd.englishcoach;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Prefs 的单元测试：验证静态工具方法（clamp / nullSafe）。
 * SharedPreferences 读写需要 Android Context，无法在纯 JVM 测试，
 * 故只测纯逻辑部分。
 */
public class PrefsTest {

    @Test
    public void clamp_withinRange() {
        assertEquals(5, Prefs.clamp(5, 0, 10));
    }

    @Test
    public void clamp_belowMin() {
        assertEquals(0, Prefs.clamp(-3, 0, 10));
    }

    @Test
    public void clamp_aboveMax() {
        assertEquals(10, Prefs.clamp(99, 0, 10));
    }

    @Test
    public void clamp_atBoundaries() {
        assertEquals(0, Prefs.clamp(0, 0, 10));
        assertEquals(10, Prefs.clamp(10, 0, 10));
    }

    @Test
    public void clamp_equalMinMax() {
        assertEquals(5, Prefs.clamp(100, 5, 5));
    }

    @Test
    public void nullSafe_nonEmpty() {
        assertEquals("abc", Prefs.nullSafe("abc", "def"));
    }

    @Test
    public void nullSafe_null() {
        assertEquals("def", Prefs.nullSafe(null, "def"));
    }

    @Test
    public void nullSafe_empty() {
        assertEquals("def", Prefs.nullSafe("", "def"));
    }

    @Test
    public void nullSafe_blank() {
        // 空字符串也应回退到默认值（TextUtils.isEmpty 认为 "" 是 empty）
        assertEquals("fallback", Prefs.nullSafe("", "fallback"));
    }

    // ── 默认值检查 ──────────────────────────

    @Test
    public void defApiKey_isEmpty() {
        // v3.0：默认 API Key 留空
        assertEquals("", Prefs.DEF_API_KEY);
    }

    // ── v4.2：默认服务迁到 TokenDance ─────────

    @Test
    public void defChatModel_isTokendance() {
        assertEquals("deepseek-v4.1-flash", Prefs.DEF_CHAT_MODEL);
    }

    @Test
    public void defAsrModel_isQwenAudio() {
        assertEquals("qwen-audio-3.0-asr-flash", Prefs.DEF_ASR_MODEL);
    }

    @Test
    public void defBaseUrl_isTokendanceGateway() {
        assertEquals("https://tokendance.space/gateway/v1", Prefs.DEF_BASE_URL);
    }

    @Test
    public void defTtsBaseUrl_isTokendanceGateway() {
        assertEquals("https://tokendance.space/gateway/v1", Prefs.DEF_TTS_BASE_URL);
    }

    @Test
    public void defAsrBaseUrl_isCompleteDashScopeEndpoint() {
        // 识别走 DashScope，端点是完整 URL（不是 OpenAI 那种 /v1 前缀）
        assertTrue(Prefs.DEF_ASR_BASE_URL.startsWith("https://tokendance.space/gateway/alibaba/"));
        assertTrue(Prefs.DEF_ASR_BASE_URL.endsWith("/generation"));
    }

    @Test
    public void defTtsModel_correct() {
        assertEquals("mimo-v2.5-tts", Prefs.DEF_TTS_MODEL);
    }

    @Test
    public void defTtsVoiceEn_correct() {
        assertEquals("Mia", Prefs.DEF_TTS_VOICE_EN);
    }

    @Test
    public void defTtsVoiceZh_correct() {
        assertEquals("冰糖", Prefs.DEF_TTS_VOICE_ZH);
    }

    @Test
    public void nullSafe_emptyApiKey_staysEmpty() {
        // DEF_API_KEY = ""，nullSafe("", "") = ""（空回退到空）
        assertEquals("", Prefs.nullSafe("", Prefs.DEF_API_KEY));
    }
}
