package com.rd.englishcoach;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

/**
 * 纯 HTTP 调用：ASR（qwen-audio 系列，DashScope 协议）+ Chat（OpenAI 兼容）。
 * 不引入 OkHttp，只用框架自带 HttpURLConnection + org.json。
 */
public final class ApiClient {

    /** 未填 API Key 时的提示（需与 strings.xml 的 msg_no_api_key 一致）。 */
    public static final String MSG_NO_API_KEY = "请先到设置页填 API Key";
    /** 服务端拒绝鉴权时的提示（需与 strings.xml 的 msg_api_key_invalid 一致）。 */
    public static final String MSG_API_KEY_INVALID = "API Key 无效或已过期，请到设置页更新";

    private ApiClient() {}

    // ── 自定义异常 ──────────────────────────

    public static class ApiException extends Exception {
        public final int httpCode;
        /**
         * 服务端原始响应体（可空）。
         *
         * <p>有些报错把关键信息放在非 message 字段：例如 TTS 未知音色时
         * 可用音色列表在 {@code error.param} 里，而 message 只有 “Param Incorrect”，
         * 所以音色探测需要拿到原始体自己解析。</p>
         */
        public final String rawBody;

        ApiException(int httpCode, String message) {
            this(httpCode, message, null);
        }

        ApiException(int httpCode, String message, String rawBody) {
            super(message);
            this.httpCode = httpCode;
            this.rawBody = rawBody;
        }
    }

    // ── ASR：音频 → 文字 ──────────────────

    /**
     * 将 WAV 字节以 data URI 形式发给 DashScope 协议的识别端点，返回识别文本。
     *
     * <p>与旧版 mimo-v2.5-asr（OpenAI chat/completions + 裸 base64）不同：qwen-audio 系列走
     * 阿里云 DashScope 协议，端点是<b>完整 URL</b>，且 base64 <b>必须带
     * {@code data:audio/wav;base64,} 前缀</b>（裸 base64 实测返回 500）。</p>
     *
     * @param asrUrl 完整识别端点（不是 OpenAI 的 {@code /v1} 前缀）
     */
    public static String transcribe(byte[] wav, String asrUrl, String apiKey, String model)
            throws IOException, ApiException {

        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new ApiException(0, MSG_NO_API_KEY); // 未填 key 不发请求
        }
        String body = buildAsrBody(wav, model);
        String resp = Http.postJson(asrUrl, apiKey, body, 120_000);
        return extractAsrText(resp);
    }

    // ── Chat：原文 → 英文参考回答 ────────────

    /** 将完整对话历史发给 AI，返回回答。 */
    public static String answerWithHistory(String[][] messages, String baseUrl,
                                           String apiKey, String model)
            throws IOException, ApiException {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new ApiException(0, MSG_NO_API_KEY); // 未填 key 不发请求
        }
        String body = buildHistoryBody(messages, model);
        String url = baseUrl + "/chat/completions";
        String resp = Http.postJson(url, apiKey, body, 60_000);
        return extractContent(resp);
    }

    // ── JSON 构建（异常包装） ────────────────

    static String buildAsrBody(byte[] wav, String model) throws IOException {
        try {
            String b64 = java.util.Base64.getEncoder().encodeToString(wav);
            JSONObject inputAudio = new JSONObject();
            // 必须带 data URI 前缀，裸 base64 服务端不认（实测 500）
            inputAudio.put("data", "data:audio/wav;base64," + b64);
            JSONObject part = new JSONObject();
            part.put("type", "input_audio");
            part.put("input_audio", inputAudio);
            JSONArray content = new JSONArray();
            content.put(part);
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", content);
            JSONArray messages = new JSONArray();
            messages.put(userMsg);
            JSONObject input = new JSONObject();
            input.put("messages", messages);
            JSONObject parameters = new JSONObject();
            parameters.put("format", "wav");
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("input", input);
            body.put("parameters", parameters);
            return body.toString();
        } catch (JSONException e) {
            throw new IOException("JSON build error: " + e.getMessage(), e);
        }
    }

    private static String buildHistoryBody(String[][] messages, String model) throws IOException {
        try {
            JSONArray msgs = new JSONArray();
            for (String[] m : messages) {
                JSONObject o = new JSONObject();
                o.put("role", m[0]);
                o.put("content", m[1]);
                msgs.put(o);
            }
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("messages", msgs);
            body.put("temperature", 0.7);
            return body.toString();
        } catch (JSONException e) {
            throw new IOException("JSON build error: " + e.getMessage(), e);
        }
    }

    // ── 错误文案 ──────────────────────────

    /**
     * 服务端错误体 → 一句可读提示（面板要给明确提示，不吐原始 JSON）。
     * 支持 OpenAI 风格 {@code {"error":{"message":"…"}}} 与纯字符串 error。
     */
    static String friendlyError(int httpCode, String resp) {
        String raw = resp == null ? "" : resp.trim();
        String msg = null;
        try {
            JSONObject o = new JSONObject(raw);
            JSONObject err = o.optJSONObject("error");
            if (err != null) {
                msg = err.optString("message", null);
            } else {
                String plain = o.optString("error", null);
                msg = (plain != null && !plain.isEmpty()) ? plain : o.optString("message", null);
            }
        } catch (JSONException ignored) {
            // 非 JSON（网关 HTML 等）→ 走下面兜底
        }
        if (msg == null || msg.trim().isEmpty()) {
            if (raw.isEmpty()) return "HTTP " + httpCode;
            return raw.length() > 200 ? raw.substring(0, 200) + "…" : raw;
        }
        return cleanMessage(httpCode, msg);
    }

    /** 去掉 request id 噪音；鉴权类错误换成可执行提示。 */
    static String cleanMessage(int httpCode, String msg) {
        String m = msg == null ? "" : msg.trim();
        m = m.replaceAll("\\s*\\(request id:[^)]*\\)", "").trim();
        String lower = m.toLowerCase();
        if (httpCode == 401 || httpCode == 403
                || lower.contains("invalid token") || lower.contains("unauthorized")
                || lower.contains("invalid api key")) {
            return MSG_API_KEY_INVALID;
        }
        return m.isEmpty() ? "unknown error" : m;
    }

    /**
     * 从 DashScope 识别响应中取文本：优先 {@code output.text}，兜底顶层 {@code text}。
     *
     * <p>空文本<b>不是错误</b>（静音/无语音时就是空），交给调用方按「没听到」处理。
     * 顶层 error（网关/鉴权类）仍转成可读提示抛出。</p>
     */
    static String extractAsrText(String json) throws ApiException {
        try {
            JSONObject resp = new JSONObject(json);
            if (resp.has("error")) {
                String errMsg = resp.optJSONObject("error") != null
                        ? resp.optJSONObject("error").optString("message", "unknown error")
                        : resp.optString("error", "unknown error");
                throw new ApiException(0, cleanMessage(0, errMsg));
            }
            JSONObject output = resp.optJSONObject("output");
            String text = output != null ? output.optString("text", null) : null;
            if (text == null || text.isEmpty()) text = resp.optString("text", "");
            return text == null ? "" : text.trim();
        } catch (JSONException e) {
            throw new ApiException(0, "JSON parse error: " + e.getMessage());
        }
    }

    // ── 响应解析 ──────────────────────────

    /**
     * 从 OpenAI-compatible 的 chat/completions 响应中提取 assistant 的 content。
     * 忽略 reasoning_content 字段（deepseek-flash 会吐思考过程）。
     */
    static String extractContent(String json) throws ApiException {
        try {
            JSONObject resp = new JSONObject(json);

            // 检查顶层 error
            if (resp.has("error")) {
                String errMsg = resp.optJSONObject("error") != null
                        ? resp.optJSONObject("error").optString("message", "unknown error")
                        : resp.optString("error", "unknown error");
                throw new ApiException(0, cleanMessage(0, errMsg));
            }

            JSONArray choices = resp.getJSONArray("choices");
            if (choices.length() == 0) throw new ApiException(0, "empty choices");

            JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
            if (msg == null) throw new ApiException(0, "no message in choice");

            String content = msg.optString("content", null);
            if (content == null || content.isEmpty()) {
                throw new ApiException(0, "empty content in response");
            }
            return content.trim();
        } catch (JSONException e) {
            throw new ApiException(0, "JSON parse error: " + e.getMessage());
        }
    }
}
