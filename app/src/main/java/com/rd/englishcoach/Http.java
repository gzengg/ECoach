package com.rd.englishcoach;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 极简 HTTP 工具（框架自带 HttpURLConnection，零第三方依赖）。
 *
 * <p>ApiClient（ASR / Chat）、TtsClient（朗读）、Translator（翻译）共用这一份实现，
 * 避免三处重复的「建连接 → 发 JSON → 读流 → 判错」代码各写一遍、各自修 bug。</p>
 */
final class Http {

    private static final int CONNECT_TIMEOUT_MS = 15_000;

    private Http() {}

    /**
     * 发一个 JSON POST，返回响应正文。
     *
     * @param apiKey 非空时带 {@code Authorization: Bearer}；为空则不带（如免费翻译接口）
     * @return HTTP &lt; 400 时的响应正文
     * @throws ApiClient.ApiException HTTP &ge; 400，message 已转成可读提示
     */
    static String postJson(String url, String apiKey, String body, int readTimeoutMs)
            throws IOException, ApiClient.ApiException {

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(readTimeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            if (apiKey != null && !apiKey.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            }

            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bodyBytes.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }

            int code = conn.getResponseCode();
            String resp = readStream(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 400) {
                // 不把原始 JSON / 网关 HTML 整段丢给用户，统一转成一句可读提示
                throw new ApiClient.ApiException(code, ApiClient.friendlyError(code, resp));
            }
            return resp;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 读尽输入流并转成字符串（null 安全）。 */
    static String readStream(InputStream in) throws IOException {
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
