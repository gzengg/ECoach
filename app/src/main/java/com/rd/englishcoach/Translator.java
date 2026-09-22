package com.rd.englishcoach;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

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
            "MYMEMORY WARNING"
    };

    private Translator() {}

    /** 纯函数：判定目标语言。含 CJK 字符 → en，否则 → zh-CN。 */
    public static String detectTarget(String text) {
        if (text == null || text.isEmpty()) return "zh-CN";
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return "en"; // CJK → 英文
        }
        return "zh-CN"; // 默认 → 中文
    }

    /**
     * 朗读语言提示：文本含 CJK → "zh-CN"（中文声），否则 → "en"（英文声）。
     * 与 {@link #detectTarget} 方向相反——这里标的是文本本身是什么语言。
     */
    public static String speakLang(String text) {
        if (text == null || text.isEmpty()) return "en";
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return "zh-CN";
        }
        return "en";
    }

    /** 翻译文本，返回译文。失败抛 IOException。 */
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
            String resp = readStream(conn.getInputStream());

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

    /** 检测是否是 200 假错误的特征串。 */
    static boolean isErrorText(String text) {
        if (text == null || text.isEmpty()) return true;
        String upper = text.toUpperCase();
        for (String marker : ERROR_MARKERS) {
            if (upper.contains(marker)) return true;
        }
        return false;
    }

    private static String readStream(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder(4096);
        try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
        }
        return sb.toString().trim();
    }

    private static String truncate(String s) {
        return s != null && s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
