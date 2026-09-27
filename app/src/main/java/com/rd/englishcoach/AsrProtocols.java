package com.rd.englishcoach;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 识别协议适配器：同一段 WAV，按不同协议发出去、各自解析文本。
 *
 * <ul>
 *   <li>{@link #DASHSCOPE} —— 阿里云 DashScope 多模态端点（qwen-audio 系列），
 *       端点是完整 URL，base64 必须带 {@code data:audio/wav;base64,} 前缀</li>
 *   <li>{@link #CHAT_AUDIO} —— OpenAI 兼容 {@code POST {base}/chat/completions} + {@code input_audio}
 *       （裸 base64，mimo-v2.5-asr / qwen3-asr-flash 那类）</li>
 *   <li>{@link #TRANSCRIPTIONS} —— OpenAI {@code POST {base}/audio/transcriptions}（multipart，Whisper 及兼容服务）</li>
 * </ul>
 */
final class AsrProtocols {

    static final String DASHSCOPE = "dashscope";
    static final String CHAT_AUDIO = "chat-audio";
    static final String TRANSCRIPTIONS = "transcriptions";
    static final String[] ALL = {DASHSCOPE, CHAT_AUDIO, TRANSCRIPTIONS};

    private AsrProtocols() {}

    /** 识别整段 WAV，返回文本（可能空串，表示没识别到内容）。 */
    static String transcribe(String protocol, String baseUrl, String apiKey, String model, byte[] wav)
            throws IOException, ApiClient.ApiException {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new ApiClient.ApiException(0, ApiClient.MSG_NO_API_KEY);
        }
        switch (protocol) {
            case CHAT_AUDIO:     return chatAudio(baseUrl, apiKey, model, wav);
            case TRANSCRIPTIONS: return transcriptions(baseUrl, apiKey, model, wav);
            default:
                // DashScope：完整端点，data URI 前缀（旧实现）
                return ApiClient.transcribe(wav, baseUrl, apiKey, model);
        }
    }

    // ── OpenAI 兼容 chat + input_audio ────────

    private static String chatAudio(String baseUrl, String apiKey, String model, byte[] wav)
            throws IOException, ApiClient.ApiException {
        String body = buildChatAudioBody(model, wav);
        String resp = Http.postJson(trimSlash(baseUrl) + "/chat/completions", apiKey, body, 120_000);
        try {
            return ApiClient.extractContent(resp);
        } catch (ApiClient.ApiException e) {
            // 识别接口对静音常常返回空 content，这不是错误
            if (e.getMessage() != null && e.getMessage().contains("empty content")) return "";
            throw e;
        }
    }

    /** OpenAI 多模态：{@code content:[{type:input_audio, input_audio:{data:<裸base64>, format:wav}}]}。 */
    static String buildChatAudioBody(String model, byte[] wav) throws IOException {
        try {
            JSONObject inputAudio = new JSONObject();
            inputAudio.put("data", java.util.Base64.getEncoder().encodeToString(wav));
            inputAudio.put("format", "wav");
            JSONObject part = new JSONObject();
            part.put("type", "input_audio");
            part.put("input_audio", inputAudio);
            JSONArray content = new JSONArray();
            content.put(part);
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", content);
            JSONArray messages = new JSONArray();
            messages.put(user);
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("messages", messages);
            return body.toString();
        } catch (JSONException e) {
            throw new IOException("JSON build error: " + e.getMessage(), e);
        }
    }

    // ── OpenAI /audio/transcriptions ─────────

    private static String transcriptions(String baseUrl, String apiKey, String model, byte[] wav)
            throws IOException, ApiClient.ApiException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("model", model);
        fields.put("response_format", "json");
        String resp = Http.postMultipart(trimSlash(baseUrl) + "/audio/transcriptions", headers,
                "file", "audio.wav", wav, "audio/wav", fields, 120_000);
        return parseTranscription(resp);
    }

    /** Whisper 风格响应：{@code {"text":"…"}}（空文本不是错误）。 */
    static String parseTranscription(String json) throws ApiClient.ApiException {
        try {
            JSONObject o = new JSONObject(json);
            if (o.has("error") && !o.isNull("error")) {
                Object err = o.opt("error");
                String msg = err instanceof JSONObject
                        ? ((JSONObject) err).optString("message", "unknown error")
                        : String.valueOf(err);
                throw new ApiClient.ApiException(0, ApiClient.cleanMessage(0, msg));
            }
            String t = o.optString("text", "");
            return t == null ? "" : t.trim();
        } catch (JSONException e) {
            throw new ApiClient.ApiException(0, "JSON parse error: " + e.getMessage());
        }
    }

    private static String trimSlash(String s) {
        return s == null ? "" : s.trim().replaceAll("/+$", "");
    }
}
