package com.rd.englishcoach;

import android.content.Context;
import android.content.SharedPreferences;
/**
 * 所有配置项集中管理，读写 SharedPreferences。
 * 默认值与 AGENTS.md §5 保持一致，不要单独改这里。
 */
public final class Prefs {
    // ── 服务端 API ──
    public static final String DEF_BASE_URL   = "https://mimo.ezlook.top/v1";
    public static final String DEF_API_KEY    = "sk-REDACTED";
    public static final String DEF_ASR_MODEL  = "mimo-v2.5-asr";
    public static final String DEF_CHAT_MODEL = "deepseek-flash";

    public static final String DEF_SYS_PROMPT =
            "You help a Chinese learner practice English listening and speaking.\n"
          + "You receive the English transcript of what the other speaker just said "
          + "(from speech recognition, so it may contain small errors).\n"
          + "Reply with ONE natural English reference answer that the learner can say out loud.\n"
          + "Rules: output only the English answer; no quotes, no explanation, no Chinese, "
          + "no bullet points; 1-2 sentences; natural spoken style.\n"
          + "If the transcript is not a question, give a natural thing the learner could say next.";

    // ── 采集参数 ──
    public static final int DEF_MAX_SECONDS  = 25;
    public static final int MIN_MAX_SECONDS  = 5;
    public static final int MAX_MAX_SECONDS  = 60;

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

    public int maxSeconds() {
        return clamp(sp.getInt("max_seconds", DEF_MAX_SECONDS), MIN_MAX_SECONDS, MAX_MAX_SECONDS);
    }

    public int fontSp() {
        return clamp(sp.getInt("font_sp", DEF_FONT_SP), MIN_FONT_SP, MAX_FONT_SP);
    }

    public int widthDp() {
        return clamp(sp.getInt("width_dp", DEF_WIDTH_DP), MIN_WIDTH_DP, MAX_WIDTH_DP);
    }

    public boolean showTranscript() {
        return sp.getBoolean("show_transcript", false);
    }

    // ── 写 ──────────────────────────────────
    public void putBaseUrl(String v)     { sp.edit().putString("base_url",  nullSafe(v, DEF_BASE_URL)).apply(); }
    public void putApiKey(String v)      { sp.edit().putString("api_key",    nullSafe(v, DEF_API_KEY)).apply(); }
    public void putAsrModel(String v)    { sp.edit().putString("asr_model",  nullSafe(v, DEF_ASR_MODEL)).apply(); }
    public void putChatModel(String v)   { sp.edit().putString("chat_model", nullSafe(v, DEF_CHAT_MODEL)).apply(); }
    public void putSysPrompt(String v)   { sp.edit().putString("sys_prompt", nullSafe(v, DEF_SYS_PROMPT)).apply(); }

    public void putMaxSeconds(int v)     { sp.edit().putInt("max_seconds", clamp(v, MIN_MAX_SECONDS, MAX_MAX_SECONDS)).apply(); }
    public void putFontSp(int v)         { sp.edit().putInt("font_sp",     clamp(v, MIN_FONT_SP, MAX_FONT_SP)).apply(); }
    public void putWidthDp(int v)        { sp.edit().putInt("width_dp",    clamp(v, MIN_WIDTH_DP, MAX_WIDTH_DP)).apply(); }
    public void putShowTranscript(boolean v) { sp.edit().putBoolean("show_transcript", v).apply(); }

    public void resetAll() { sp.edit().clear().apply(); }

    // ── 工具 ──────────────────────────────────
    static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    static String nullSafe(String v, String def) {
        return (v == null || v.isEmpty()) ? def : v;
    }
}
