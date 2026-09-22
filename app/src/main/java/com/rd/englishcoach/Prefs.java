package com.rd.englishcoach;

import android.content.Context;
import android.content.SharedPreferences;
/**
 * 所有配置项集中管理，读写 SharedPreferences。
 * 默认值集中在这里定义，其他模块不要各自再写一份。
 */
public final class Prefs {
    // ── 服务端 API ──
    public static final String DEF_BASE_URL   = "https://mimo.ezlook.top/v1";
    public static final String DEF_API_KEY    = "";
    public static final String DEF_ASR_MODEL  = "mimo-v2.5-asr";
    public static final String DEF_CHAT_MODEL = "deepseek-flash";

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

    // ── 悬浮窗外观 ──
    public static final int DEF_FONT_SP   = 15;
    public static final int MIN_FONT_SP   = 10;
    public static final int MAX_FONT_SP   = 28;

    public static final int DEF_WIDTH_DP  = 300;
    public static final int MIN_WIDTH_DP  = 200;
    public static final int MAX_WIDTH_DP  = 500;

    private static final String NAME = "cfg";

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    // ── 读 ──────────────────────────────────
    public String baseUrl()   { return sp.getString("base_url",  DEF_BASE_URL); }
    public String apiKey()    { return sp.getString("api_key",    DEF_API_KEY); }
    public String asrModel()  { return sp.getString("asr_model",  DEF_ASR_MODEL); }
    public String chatModel() { return sp.getString("chat_model", DEF_CHAT_MODEL); }
    public String sysPrompt() { return sp.getString("sys_prompt", DEF_SYS_PROMPT); }
    public String ttsModel()        { return sp.getString("tts_model",      DEF_TTS_MODEL); }
    public String ttsVoiceEnglish() { return sp.getString("tts_voice_en",   DEF_TTS_VOICE_EN); }
    public String ttsVoiceChinese() { return sp.getString("tts_voice_zh",   DEF_TTS_VOICE_ZH); }

    public int fontSp() {
        return clamp(sp.getInt("font_sp", DEF_FONT_SP), MIN_FONT_SP, MAX_FONT_SP);
    }

    public int widthDp() {
        return clamp(sp.getInt("width_dp", DEF_WIDTH_DP), MIN_WIDTH_DP, MAX_WIDTH_DP);
    }

    // ── 写 ──────────────────────────────────
    public void putBaseUrl(String v)     { sp.edit().putString("base_url",  nullSafe(v, DEF_BASE_URL)).apply(); }
    public void putApiKey(String v)      { sp.edit().putString("api_key",    nullSafe(v, DEF_API_KEY)).apply(); }
    public void putAsrModel(String v)    { sp.edit().putString("asr_model",  nullSafe(v, DEF_ASR_MODEL)).apply(); }
    public void putChatModel(String v)   { sp.edit().putString("chat_model", nullSafe(v, DEF_CHAT_MODEL)).apply(); }
    public void putSysPrompt(String v)   { sp.edit().putString("sys_prompt", nullSafe(v, DEF_SYS_PROMPT)).apply(); }
    public void putTtsModel(String v)        { sp.edit().putString("tts_model",    nullSafe(v, DEF_TTS_MODEL)).apply(); }
    public void putTtsVoiceEnglish(String v) { sp.edit().putString("tts_voice_en", nullSafe(v, DEF_TTS_VOICE_EN)).apply(); }

    public void putFontSp(int v)         { sp.edit().putInt("font_sp",     clamp(v, MIN_FONT_SP, MAX_FONT_SP)).apply(); }
    public void putWidthDp(int v)        { sp.edit().putInt("width_dp",    clamp(v, MIN_WIDTH_DP, MAX_WIDTH_DP)).apply(); }

    public void resetAll() { sp.edit().clear().apply(); }

    // ── 工具 ──────────────────────────────────
    static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    static String nullSafe(String v, String def) {
        return (v == null || v.isEmpty()) ? def : v;
    }
}
