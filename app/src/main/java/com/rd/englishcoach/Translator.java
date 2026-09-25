package com.rd.englishcoach;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 免费翻译：MyMemory（api.mymemory.translated.net）。
 * 方向自动判定：含 CJK → en，否则 → zh-CN。
 *
 * <p>⚠️ 实测坑：空输入 / 非法语言对返回 HTTP 200 但正文是错误信息，必须自己判。</p>
 */
public final class Translator {

    private static final String BASE_URL =
            "https://api.mymemory.translated.net/get?q=%s&langpair=%s";

    // 200 假错误的特征串（全大写）
    private static final String[] ERROR_MARKERS = {
            "NO QUERY SPECIFIED",
            "INVALID SOURCE LANGUAGE",
            "INVALID TARGET LANGUAGE",
            "PLEASE SELECT TWO DISTINCT LANGUAGES",
            "QUERY LENGTH LIMIT EXCEEDED",
            "MYMEMORY WARNING"
    };

    /**
     * 单次查询的字符上限。MyMemory 硬限制是 500，这里留余量（真机报过
     * {@code QUERY LENGTH LIMIT EXCEEDED. MAX ALLOWED QUERY : 500 CHARS}）。
     */
    static final int MAX_QUERY_CHARS = 450;

    private Translator() {}

    /** 纯函数：判定目标语言。含 CJK 字符 → en，否则 → zh-CN。 */
    public static String detectTarget(String text) {
        return containsCjk(text) ? "en" : "zh-CN";
    }

    /**
     * 朗读语言提示：文本含 CJK → "zh-CN"（中文声），否则 → "en"（英文声）。
     * 与 {@link #detectTarget} 方向相反——这里标的是文本本身是什么语言。
     */
    public static String speakLang(String text) {
        return containsCjk(text) ? "zh-CN" : "en";
    }

    /** 是否含 CJK 统一表意文字（中/日/韩共用区）。 */
    private static boolean containsCjk(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return true;
        }
        return false;
    }

    /** 翻译文本，返回译文。失败抛 IOException。
     *
     * <p>⚠️ **单次查询上限 500 字符**（MyMemory 硬限制），所以业务代码请用
     * {@link #translateLong(String)}——取词整屏 OCR 很容易到 580 字符，直接调这个会当场失败。</p>
     */
    public static String translate(String text) throws IOException {
        if (text == null || text.trim().isEmpty()) {
            throw new IOException("empty text");
        }
        String target = detectTarget(text);
        String langpair = "autodetect|" + target;
        String url = String.format(BASE_URL,
                URLEncoder.encode(text.trim(), "UTF-8"),
                URLEncoder.encode(langpair, "UTF-8"));

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(20_000);
            conn.setRequestProperty("Accept", "application/json");

            int code = conn.getResponseCode();
            String resp = Http.readStream(conn.getInputStream());

            if (code != 200) {
                throw new IOException("HTTP " + code + ": " + truncate(resp));
            }

            // 解析 responseData.translatedText
            org.json.JSONObject json = new org.json.JSONObject(resp);
            int status = json.optInt("responseStatus", 0);
            String translated = json.optJSONObject("responseData") != null
                    ? json.optJSONObject("responseData").optString("translatedText", "")
                    : "";

            // 检查 200 假错误
            if (status != 200 || translated.isEmpty() || isErrorText(translated)) {
                throw new IOException("translation error: " + truncate(translated));
            }

            return translated;
        } catch (org.json.JSONException e) {
            throw new IOException("JSON parse error: " + e.getMessage(), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 翻译长文本：自动分段（每段 ≤ {@link #MAX_QUERY_CHARS}）逐段翻译后拼接。
     *
     * <p>为什么必须分段：取词是整屏 OCR，实测一次 580 字符，而 MyMemory 单次查询上限 500 字符
     * → 真机报 {@code QUERY LENGTH LIMIT EXCEEDED}，翻译整个失败（只能“已保留原文”）。</p>
     *
     * <p>分段优先在句末/换行处切，保留原有分隔符，拼接后排版与原文一致；
     * 单句超长（无标点的长串）才硬切。某段失败则整次失败（上层会保留原文），
     * 不做“半翻半不翻”——那比直接报错更难理解。</p>
     */
    public static String translateLong(String text) throws IOException {
        List<String> chunks = splitChunks(text, MAX_QUERY_CHARS);
        if (chunks.isEmpty()) throw new IOException("empty text");
        if (chunks.size() == 1) return translate(chunks.get(0));
        StringBuilder out = new StringBuilder();
        for (String chunk : chunks) out.append(translate(chunk));
        return out.toString();
    }

    /**
     * 把长文本切成每段 ≤ {@code max} 字符的片段（纯函数，便于测试）。
     *
     * <p>实现已抽到 {@link TextChunker}（朗读链也要同一套分段逻辑）；这里保留方法名，
     * 免得为一个名字改多处调用与测试。</p>
     */
    static List<String> splitChunks(String text, int max) {
        return TextChunker.split(text, max);
    }

    /** 检测是否是 200 假错误的特征串。 */
    static boolean isErrorText(String text) {
        if (text == null || text.isEmpty()) return true;
        String upper = text.toUpperCase();
        for (String marker : ERROR_MARKERS) {
            if (upper.contains(marker)) return true;
        }
        return false;
    }

    private static String truncate(String s) {
        return s != null && s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    /**
     * 把底层异常文案映射到**面向用户的中文文案**资源 id。
     *
     * <p>真机踩过：直接把 API 英文原文（{@code translation error: QUERY LENGTH LIMIT EXCEEDED...}）
     * 显示给用户，既看不懂也不知道怎么办。</p>
     */
    static int errorRes(String raw) {
        String s = raw == null ? "" : raw.toUpperCase(Locale.US);
        if (s.contains("QUERY LENGTH LIMIT") || s.contains("500 CHARS")) {
            return R.string.grab_translate_too_long;
        }
        if (s.contains("EMPTY TEXT") || s.contains("NO QUERY SPECIFIED")) {
            return R.string.grab_translate_empty;
        }
        if (s.contains("TIMEOUT") || s.contains("UNKNOWNHOST") || s.contains("CONNECT")
                || s.contains("HTTP ") || s.contains("JSON")) {
            return R.string.grab_translate_network;
        }
        return R.string.grab_translate_other;
    }
}
