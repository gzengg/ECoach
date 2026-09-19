package com.rd.englishcoach;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 纯 HTTP 调用：ASR（mimo-v2.5-asr）+ Chat（deepseek-flash）。
 * 不引入 OkHttp，只用框架自带 HttpURLConnection + org.json。
 */
public final class ApiClient {

    private ApiClient() {}

    // ── 自定义异常 ──────────────────────────

    public static class ApiException extends Exception {
        public final int httpCode;
        ApiException(int httpCode, String message) {
            super(message);
            this.httpCode = httpCode;
        }
    }

    // ── ASR：音频 → 文字 ──────────────────

    /**
     * 将 WAV 字节 base64 后发给 mimo-v2.5-asr，返回识别出的英文文本。
     */
    public static String transcribe(byte[] wav, String baseUrl, String apiKey, String model)
            throws IOException, ApiException {

        String body = buildAsrBody(wav, model);
        String url = baseUrl + "/chat/completions";
        String resp = post(url, apiKey, body, 120_000);
        return extractContent(resp);
    }

    // ── Chat：原文 → 英文参考回答 ────────────

    /** 将完整对话历史发给 AI，返回回答。 */
    public static String answerWithHistory(String[][] messages, String baseUrl,
                                           String apiKey, String model)
            throws IOException, ApiException {
        String body = buildHistoryBody(messages, model);
        String url = baseUrl + "/chat/completions";
        String resp = post(url, apiKey, body, 60_000);
        return extractContent(resp);
    }

    /**
     * 将 ASR 原文发给 deepseek-flash，返回英文参考回答。
     */
    public static String answer(String transcript, String baseUrl, String apiKey,
                                String model, String sysPrompt)
            throws IOException, ApiException {

        String body = buildChatBody(transcript, model, sysPrompt);
        String url = baseUrl + "/chat/completions";
        String resp = post(url, apiKey, body, 60_000);
        return extractContent(resp);
    }

    // ── JSON 构建（异常包装） ────────────────

    private static String buildAsrBody(byte[] wav, String model) throws IOException {
        try {
            String b64 = Base64.encodeToString(wav, Base64.NO_WRAP);
            JSONObject inputAudio = new JSONObject();
            inputAudio.put("data", b64);
            inputAudio.put("format", "wav");
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
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("messages", messages);
            return body.toString();
        } catch (JSONException e) {
            throw new IOException("JSON build error: " + e.getMessage(), e);
        }
    }

    private static String buildChatBody(String transcript, String model, String sysPrompt)
            throws IOException {
        try {
            JSONObject sysMsg = new JSONObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", sysPrompt);
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", transcript);
            JSONArray messages = new JSONArray();
            messages.put(sysMsg);
            messages.put(userMsg);
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("messages", messages);
            body.put("temperature", 0.7);
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

    // ── HTTP POST ────────────────────────

    private static String post(String urlStr, String apiKey, String body, int readTimeoutMs)
            throws IOException, ApiException {

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(readTimeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Accept", "application/json");

            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bodyBytes.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }

            int code = conn.getResponseCode();
            InputStream in = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            String resp = readStream(in);

            if (code >= 400) {
                // 截断错误体，避免把整个 HTML 展示给用户
                String msg = resp.length() > 500 ? resp.substring(0, 500) + "…" : resp;
                throw new ApiException(code, msg);
            }

            return resp;
        } finally {
            if (conn != null) conn.disconnect();
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
                throw new ApiException(0, errMsg);
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

    // ── 工具 ──────────────────────────────

    private static String readStream(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder(4096);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString().trim();
    }
}
