package com.rd.englishcoach;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极简 HTTP 工具（框架自带 HttpURLConnection，零第三方依赖）。
 *
 * <p>各协议适配器（ChatProtocols / AsrProtocols / TtsProtocols）与 Translator 共用这一份实现，
 * 避免「建连接 → 发请求 → 读流 → 判错」到处各写一遍。支持四种形态：</p>
 * <ul>
 *   <li>{@link #postJson} —— JSON 请求/响应（大多数协议）</li>
 *   <li>{@link #postBytes} —— 二进制响应（TTS /audio/speech）</li>
 *   <li>{@link #postMultipart} —— 文件上传（ASR /audio/transcriptions）</li>
 *   <li>{@link #postStream} —— 流式响应（豆包 Ark TTS 的 SSE）</li>
 * </ul>
 */
final class Http {

    private static final int CONNECT_TIMEOUT_MS = 15_000;

    private Http() {}

    /** JSON POST：非空 key 时带 {@code Authorization: Bearer}。 */
    static String postJson(String url, String apiKey, String body, int readTimeoutMs)
            throws IOException, ApiClient.ApiException {
        Map<String, String> h = new LinkedHashMap<>();
        if (apiKey != null && !apiKey.isEmpty()) h.put("Authorization", "Bearer " + apiKey);
        return postJson(url, h, body, readTimeoutMs);
    }

    /** JSON POST：自定义请求头（Anthropic 的 x-api-key / Gemini 的 x-goog-api-key 等）。 */
    static String postJson(String url, Map<String, String> headers, String body, int readTimeoutMs)
            throws IOException, ApiClient.ApiException {
        byte[] resp = post(url, headers, "application/json; charset=utf-8",
                body.getBytes(StandardCharsets.UTF_8), readTimeoutMs);
        return new String(resp, StandardCharsets.UTF_8).trim();
    }

    /** POST 并返回原始响应字节（TTS /audio/speech 这类二进制响应，不能按 UTF-8 解）。 */
    static byte[] postBytes(String url, Map<String, String> headers, String contentType,
                            byte[] body, int readTimeoutMs) throws IOException, ApiClient.ApiException {
        return post(url, headers, contentType, body, readTimeoutMs);
    }

    /** multipart/form-data 上传（ASR /audio/transcriptions）。 */
    static String postMultipart(String url, Map<String, String> headers, String fileField,
                                String fileName, byte[] fileBytes, String mime,
                                Map<String, String> fields, int readTimeoutMs)
            throws IOException, ApiClient.ApiException {
        String boundary = "----ECoach" + Long.toHexString(System.nanoTime());
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            writeUtf8(buf, "--" + boundary + "\r\n");
            writeUtf8(buf, "Content-Disposition: form-data; name=\"" + e.getKey() + "\"\r\n\r\n");
            writeUtf8(buf, e.getValue() + "\r\n");
        }
        writeUtf8(buf, "--" + boundary + "\r\n");
        writeUtf8(buf, "Content-Disposition: form-data; name=\"" + fileField
                + "\"; filename=\"" + fileName + "\"\r\n");
        writeUtf8(buf, "Content-Type: " + mime + "\r\n\r\n");
        buf.write(fileBytes);
        writeUtf8(buf, "\r\n--" + boundary + "--\r\n");
        byte[] resp = post(url, headers, "multipart/form-data; boundary=" + boundary,
                buf.toByteArray(), readTimeoutMs);
        return new String(resp, StandardCharsets.UTF_8).trim();
    }

    /**
     * POST 并返回响应流（SSE 用）。<b>调用方负责关闭流</b>；连接不在此处 disconnect。
     * HTTP {@code >= 400} 时读错误体并抛可读异常。
     */
    static InputStream postStream(String url, Map<String, String> headers, String contentType,
                                  byte[] body, int readTimeoutMs)
            throws IOException, ApiClient.ApiException {
        HttpURLConnection conn = open(url, headers, contentType, readTimeoutMs);
        conn.setFixedLengthStreamingMode(body.length);
        try (java.io.OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }
        int code = conn.getResponseCode();
        if (code >= 400) {
            String resp = readStream(conn.getErrorStream());
            conn.disconnect();
            throw new ApiClient.ApiException(code, ApiClient.friendlyError(code, resp), resp);
        }
        return conn.getInputStream();
    }

    // ── 内部 ──────────────────────────────

    private static byte[] post(String url, Map<String, String> headers, String contentType,
                               byte[] body, int readTimeoutMs)
            throws IOException, ApiClient.ApiException {
        HttpURLConnection conn = null;
        try {
            conn = open(url, headers, contentType, readTimeoutMs);
            conn.setFixedLengthStreamingMode(body.length);
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            int code = conn.getResponseCode();
            byte[] bytes = readBytes(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 400) {
                String resp = new String(bytes, StandardCharsets.UTF_8);
                throw new ApiClient.ApiException(code, ApiClient.friendlyError(code, resp), resp);
            }
            return bytes;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static HttpURLConnection open(String url, Map<String, String> headers,
                                          String contentType, int readTimeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(readTimeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", contentType);
        conn.setRequestProperty("Accept", "application/json");
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getValue() != null) conn.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        return conn;
    }

    private static void writeUtf8(ByteArrayOutputStream buf, String s) throws IOException {
        buf.write(s.getBytes(StandardCharsets.UTF_8));
    }

    /** 读尽输入流并转成字符串（null 安全）。 */
    static String readStream(InputStream in) throws IOException {
        return new String(readBytes(in), StandardCharsets.UTF_8).trim();
    }

    /** 读尽输入流为字节（null 安全）。 */
    static byte[] readBytes(InputStream in) throws IOException {
        if (in == null) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
