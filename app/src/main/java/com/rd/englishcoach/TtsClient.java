package com.rd.englishcoach;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * mimo-v2.5-tts 请求封装。接口契约：
 * <ul>
 *   <li>文本必须放 {@code assistant} 角色（放 user 报 "must contain an assistant role"）。</li>
 *   <li>必须带 {@code modalities:["text","audio"]}。</li>
 *   <li>音频在 {@code choices[0].message.audio.data}（base64 WAV），不要读 {@code content}（它是空的）。</li>
 * </ul>
 */
public final class TtsClient {

    private static final String TAG = "TtsClient";

    private TtsClient() {}

    // ── 构建请求体（纯函数，单测直接调） ─────────────

    /** 构建 TTS 请求 JSON。 */
    public static String buildTtsBody(String text, String model, String voice, String format)
            throws IOException {
        try {
            JSONObject audio = new JSONObject();
            audio.put("voice", voice);
            audio.put("format", format);

            JSONObject msg = new JSONObject();
            msg.put("role", "assistant");
            msg.put("content", text);

            org.json.JSONArray messages = new org.json.JSONArray();
            messages.put(msg);

            org.json.JSONArray modalities = new org.json.JSONArray();
            modalities.put("text");
            modalities.put("audio");

            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("messages", messages);
            body.put("modalities", modalities);
            body.put("audio", audio);
            return body.toString();
        } catch (JSONException e) {
            throw new IOException("JSON build error: " + e.getMessage(), e);
        }
    }

    // ── 解析响应（纯函数，单测直接调） ─────────────

    /** 从 TTS 响应中提取 base64 音频并解码为 WAV 字节。失败抛明确异常。 */
    public static byte[] extractAudioBytes(String json) throws IOException {
        try {
            JSONObject resp = new JSONObject(json);

            // 顶层 error
            if (resp.has("error")) {
                String errMsg = resp.optJSONObject("error") != null
                        ? resp.optJSONObject("error").optString("message", "unknown")
                        : resp.optString("error", "unknown");
                throw new IOException("TTS error: " + errMsg);
            }

            JSONArray choices = resp.getJSONArray("choices");
            if (choices.length() == 0) throw new IOException("empty choices");

            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            if (message == null) throw new IOException("no message");

            // 音频在 message.audio.data（base64），不是 message.content
            JSONObject audio = message.optJSONObject("audio");
            if (audio == null) throw new IOException("no audio object in message");

            String b64 = audio.optString("data", null);
            if (b64 == null || b64.isEmpty()) throw new IOException("audio.data is empty");

            byte[] wav = java.util.Base64.getDecoder().decode(b64);
            if (wav.length < 44) throw new IOException("audio too short (" + wav.length + " bytes)");
            return wav;
        } catch (JSONException e) {
            throw new IOException("JSON parse error: " + e.getMessage(), e);
        }
    }

    // ── 网络调用 ─────────────────────

    /**
     * 请求 TTS，返回解码后的 WAV 字节。
     *
     * @param baseUrl  基础 URL（不含尾部 /）
     * @param apiKey   API Key
     * @param model    TTS 模型名（如 mimo-v2.5-tts）
     * @param voice    声音名（如 Mia、冰糖）
     * @param text     要朗读的文字
     * @param format   音频格式（wav 或 mp3，v3.0 统一用 wav）
     */
    public static byte[] synthesize(String baseUrl, String apiKey, String model,
                                     String voice, String text, String format)
            throws IOException, ApiClient.ApiException {
        String body = buildTtsBody(text, model, voice, format);
        String url = baseUrl + "/chat/completions";
        String resp = Http.postJson(url, apiKey, body, 120_000);
        return extractAudioBytes(resp);
    }

    // ── 音色探测 ─────────────────────

    /** 探测用的非法 voice：服务端必定报 Unknown voice 并回显可用列表。 */
    private static final String PROBE_VOICE = "__ec_voice_probe__";

    /**
     * 探测某个 TTS 模型的可选音色。
     *
     * <p>OpenAI 兼容的 TTS 没有「列音色」接口，但 mimo 这类服务在 voice 非法时会把可用音色
     * 回显在报错里（实测 {@code error.param = "Unknown voice: x. Available voices: [a, b, c]"}）。
     * 所以发一次明显不存在的 voice，从报错里解析列表。探测不到时返回空列表，由 UI 回退到手填。</p>
     */
    public static List<String> detectVoices(String baseUrl, String apiKey, String model) {
        try {
            String body = buildTtsBody("hi", model, PROBE_VOICE, "wav");
            Http.postJson(baseUrl + "/chat/completions", apiKey, body, 30_000);
            return Collections.emptyList(); // 探测请求反而成功 → 没有可解析的报错
        } catch (ApiClient.ApiException e) {
            return parseVoices(e.rawBody != null ? e.rawBody : e.getMessage());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * 从报错文本里解析 {@code Available voices: [a, b, c]}。纯函数，单测直接调。
     * 解析不到返回空列表。
     */
    public static List<String> parseVoices(String message) {
        List<String> out = new ArrayList<>();
        if (message == null) return out;
        int i = message.toLowerCase().indexOf("available voices");
        if (i < 0) return out;
        int open = message.indexOf('[', i);
        int close = open < 0 ? -1 : message.indexOf(']', open + 1);
        if (open < 0 || close < 0) return out;
        for (String s : message.substring(open + 1, close).split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
