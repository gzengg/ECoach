package com.rd.englishcoach;

import android.content.Context;
import android.content.SharedPreferences;
/**
 * 所有配置项集中管理，读写 SharedPreferences。
 * 默认值集中在这里定义，其他模块不要各自再写一份。
 */
public final class Prefs {
    // ── 服务端 API（三套独立：问答 / 识别 / 朗读，可分别指向不同服务商） ──
    /** 问答接口（OpenAI 兼容）。 */
    public static final String DEF_BASE_URL     = "https://tokendance.space/gateway/v1";
    /** 识别接口：qwen-audio 系列走阿里云 DashScope 协议，端点是完整 URL（非 OpenAI 的 /v1 前缀）。 */
    public static final String DEF_ASR_BASE_URL =
              "https://tokendance.space/gateway/alibaba"
            + "/api/v1/services/aigc/multimodal-generation/generation";
    /** 朗读接口（mimo TTS 走 OpenAI 兼容的 chat/completions）。 */
    public static final String DEF_TTS_BASE_URL = "https://tokendance.space/gateway/v1";
    public static final String DEF_API_KEY      = "";
    public static final String DEF_CHAT_MODEL   = "deepseek-v4.1-flash";
    public static final String DEF_ASR_MODEL    = "qwen-audio-3.0-asr-flash";

    /** 三套接口各自的协议（默认值取自各协议类的常量，避免两处各写一份）。 */
    public static final String DEF_CHAT_PROTOCOL = ChatProtocols.OPENAI_CHAT;
    public static final String DEF_ASR_PROTOCOL  = AsrProtocols.DASHSCOPE;
    public static final String DEF_TTS_PROTOCOL  = TtsProtocols.CHAT_TTS;

    /** v4.2 迁移用：识别旧版（mimo.ezlook.top）的默认值，命中才清掉，用户自定义的值不动。 */
    private static final String LEGACY_BASE_URL   = "https://mimo.ezlook.top/v1";
    private static final String LEGACY_ASR_MODEL  = "mimo-v2.5-asr";
    private static final String LEGACY_CHAT_MODEL = "deepseek-flash";

    public static final String DEF_SYS_PROMPT =
            "You are helping a Chinese high school student prepare for the "
          + "Guangdong English Listening and Speaking Test (广东高考英语听说考试).\n\n"
          + "Exam format:\n"
          + "1. A standard recording plays (a conversation or monologue). "
          + "The student will first capture and provide its transcript.\n"
          + "2. Then the exam asks 5 questions one by one about the recording. "
          + "Each question is asked separately; the student pauses after each to get your help.\n\n"
          + "How to help:\n"
          + "- When you receive the recording transcript first, acknowledge it briefly "
          + "(e.g. 'Got it. Ready for the questions.') and wait.\n"
          + "- When you receive a question transcript, answer that specific question "
          + "in natural, accurate English. One clear answer per question.\n"
          + "- Keep track of question numbers (1-5) from the conversation history.\n"
          + "- If the student asks a custom question, answer based on all context so far.\n\n"
          + "Output: just the English answer, no numbering, no explanation, no Chinese.";

    // ── 朗读 TTS ──
    public static final String DEF_TTS_MODEL        = "mimo-v2.5-tts";
    public static final String DEF_TTS_VOICE_EN     = "Mia";
    public static final String DEF_TTS_VOICE_ZH     = "冰糖";

    /**
     * mimo TTS 支持的音色（接口实测只有这几个，传其它值报 Unknown voice）。
     * 前 5 个中文声，后 4 个英文声。
     */
    public static final String[] TTS_VOICES_ZH = {"mimo_default", "冰糖", "茉莉", "苏打", "白桦"};
    public static final String[] TTS_VOICES_EN = {"Mia", "Chloe", "Milo", "Dean"};

    // ── 悬浮窗外观 ──
    public static final int DEF_FONT_SP   = 15;
    public static final int MIN_FONT_SP   = 10;
    public static final int MAX_FONT_SP   = 28;

    public static final int DEF_WIDTH_DP  = 300;
    public static final int MIN_WIDTH_DP  = 200;
    public static final int MAX_WIDTH_DP  = 500;

    // ── 离线识别 / 离线朗读（v4） ──
    /**
     * 运行模式（识别与朗读共用）。
     * 自动（默认）= 装了离线模型就用离线，否则走在线；
     * **不装任何离线模型时行为与旧版完全一致**。
     */
    public static final int MODE_AUTO    = 0;
    public static final int MODE_ONLINE  = 1;
    public static final int MODE_OFFLINE = 2;
    public static final int DEF_MODE     = MODE_AUTO;

    /**
     * 模型下载源（可填多个，逗号/空白分隔，**按顺序尝试**）。
     *
     * <p><b>只用官方源</b>：GitHub 官方 release（sherpa-onnx 的识别/朗读包，上游只发 {@code .tar.bz2}）。
     * 不内置任何第三方镜像。官方源在墙内可能连不上（实测时好时坏）——
     * 拉不动时用「本地导入」：在能上网的机器上下好包再导入。</p>
     */
    public static final String DEF_MODEL_BASE_URL =
              "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
            + ",https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models";

    private static final String NAME = "cfg";

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
        migrateLegacyDefaults();
    }

    /**
     * 一次性迁移：旧默认服务是 mimo.ezlook.top，v4.2 起默认改为 tokendance。
     * 只清掉「仍是旧默认值」的三项，落回新默认；用户改过的值原样保留。
     */
    private void migrateLegacyDefaults() {
        if (sp.getBoolean("migrated_tokendance", false)) return;
        SharedPreferences.Editor e = sp.edit();
        if (LEGACY_BASE_URL.equals(sp.getString("base_url", ""))) e.remove("base_url");
        if (LEGACY_ASR_MODEL.equals(sp.getString("asr_model", ""))) e.remove("asr_model");
        if (LEGACY_CHAT_MODEL.equals(sp.getString("chat_model", ""))) e.remove("chat_model");
        e.putBoolean("migrated_tokendance", true);
        e.apply();
    }

    // ── 读 ──────────────────────────────────
    public String chatBaseUrl() { return sp.getString("base_url",  DEF_BASE_URL); }
    public String apiKey()      { return sp.getString("api_key",   DEF_API_KEY); }
    public String asrModel()    { return sp.getString("asr_model", DEF_ASR_MODEL); }
    public String chatModel()   { return sp.getString("chat_model", DEF_CHAT_MODEL); }
    public String sysPrompt()   { return sp.getString("sys_prompt", DEF_SYS_PROMPT); }
    public String ttsModel()    { return sp.getString("tts_model", DEF_TTS_MODEL); }
    public String asrBaseUrl()  { return sp.getString("asr_base_url", DEF_ASR_BASE_URL); }
    public String ttsBaseUrl()  { return sp.getString("tts_base_url", DEF_TTS_BASE_URL); }
    public String chatProtocol() { return sp.getString("chat_protocol", DEF_CHAT_PROTOCOL); }
    public String asrProtocol()  { return sp.getString("asr_protocol",  DEF_ASR_PROTOCOL); }
    public String ttsProtocol()  { return sp.getString("tts_protocol",  DEF_TTS_PROTOCOL); }

    /**
     * 识别 / 朗读的 Key：留空则回退用问答 Key（tokendance 一个 Key 通吃三个协议，免得填三遍）。
     */
    public String asrApiKey() { return keyFallback("asr_api_key"); }
    public String ttsApiKey() { return keyFallback("tts_api_key"); }

    /**
     * 原始存储值（空 = 未单独设置，跟随问答 Key）。
     * 设置页编辑用：要把「留空即跟随」如实显示为空，不能用回退后的值回填。
     */
    public String asrApiKeyRaw() { return sp.getString("asr_api_key", ""); }
    public String ttsApiKeyRaw() { return sp.getString("tts_api_key", ""); }

    private String keyFallback(String name) {
        String v = sp.getString(name, "");
        return (v == null || v.isEmpty()) ? apiKey() : v;
    }
    public String ttsVoiceEnglish() { return sp.getString("tts_voice_en",   DEF_TTS_VOICE_EN); }
    public String ttsVoiceChinese() { return sp.getString("tts_voice_zh",   DEF_TTS_VOICE_ZH); }

    public String modelBaseUrl() { return sp.getString("model_base_url", DEF_MODEL_BASE_URL); }

    /** 离线识别用哪个模型（id）；未装时引擎会自动回落到其它已装档。 */
    public String offlineAsrModelId() { return sp.getString("offline_asr_model", ""); }

    public int engineMode() {
        return clamp(sp.getInt("engine_mode", DEF_MODE), MODE_AUTO, MODE_OFFLINE);
    }

    public int fontSp() {
        return clamp(sp.getInt("font_sp", DEF_FONT_SP), MIN_FONT_SP, MAX_FONT_SP);
    }

    public int widthDp() {
        return clamp(sp.getInt("width_dp", DEF_WIDTH_DP), MIN_WIDTH_DP, MAX_WIDTH_DP);
    }

    // ── 写 ──────────────────────────────────
    public void putChatBaseUrl(String v) { sp.edit().putString("base_url",  nullSafe(v, DEF_BASE_URL)).apply(); }
    public void putApiKey(String v)      { sp.edit().putString("api_key",    nullSafe(v, DEF_API_KEY)).apply(); }
    public void putAsrModel(String v)    { sp.edit().putString("asr_model",  nullSafe(v, DEF_ASR_MODEL)).apply(); }
    public void putAsrBaseUrl(String v)  { sp.edit().putString("asr_base_url", nullSafe(v, DEF_ASR_BASE_URL)).apply(); }
    public void putTtsBaseUrl(String v)  { sp.edit().putString("tts_base_url", nullSafe(v, DEF_TTS_BASE_URL)).apply(); }
    /** 留空即「跟随问答 Key」：写空串，由 {@link #keyFallback} 回退。 */
    public void putAsrApiKey(String v)   { sp.edit().putString("asr_api_key", v == null ? "" : v.trim()).apply(); }
    public void putTtsApiKey(String v)   { sp.edit().putString("tts_api_key", v == null ? "" : v.trim()).apply(); }
    public void putChatProtocol(String v) { sp.edit().putString("chat_protocol", nullSafe(v, DEF_CHAT_PROTOCOL)).apply(); }
    public void putAsrProtocol(String v)  { sp.edit().putString("asr_protocol",  nullSafe(v, DEF_ASR_PROTOCOL)).apply(); }
    public void putTtsProtocol(String v)  { sp.edit().putString("tts_protocol",  nullSafe(v, DEF_TTS_PROTOCOL)).apply(); }
    public void putChatModel(String v)   { sp.edit().putString("chat_model", nullSafe(v, DEF_CHAT_MODEL)).apply(); }
    public void putSysPrompt(String v)   { sp.edit().putString("sys_prompt", nullSafe(v, DEF_SYS_PROMPT)).apply(); }
    public void putTtsModel(String v)        { sp.edit().putString("tts_model",    nullSafe(v, DEF_TTS_MODEL)).apply(); }
    public void putTtsVoiceEnglish(String v) { sp.edit().putString("tts_voice_en", nullSafe(v, DEF_TTS_VOICE_EN)).apply(); }

    public void putFontSp(int v)         { sp.edit().putInt("font_sp",     clamp(v, MIN_FONT_SP, MAX_FONT_SP)).apply(); }
    public void putWidthDp(int v)        { sp.edit().putInt("width_dp",    clamp(v, MIN_WIDTH_DP, MAX_WIDTH_DP)).apply(); }
    public void putModelBaseUrl(String v){ sp.edit().putString("model_base_url", nullSafe(v, DEF_MODEL_BASE_URL)).apply(); }
    public void putOfflineAsrModelId(String v) { sp.edit().putString("offline_asr_model", v == null ? "" : v).apply(); }
    public void putEngineMode(int v)     { sp.edit().putInt("engine_mode", clamp(v, MODE_AUTO, MODE_OFFLINE)).apply(); }
    public void putTtsVoiceChinese(String v)   { sp.edit().putString("tts_voice_zh", nullSafe(v, DEF_TTS_VOICE_ZH)).apply(); }

    public void resetAll() { sp.edit().clear().apply(); }

    // ── 工具 ──────────────────────────────────
    static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    static String nullSafe(String v, String def) {
        return (v == null || v.isEmpty()) ? def : v;
    }
}
