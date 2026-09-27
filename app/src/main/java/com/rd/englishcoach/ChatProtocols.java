package com.rd.englishcoach;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 问答协议适配器：同一份对话历史，按不同协议发出去、各自解析回答。
 *
 * <p>四种协议对应主流 provider / agent 工具：</p>
 * <ul>
 *   <li>{@link #OPENAI_CHAT} —— OpenAI 兼容 {@code POST {base}/chat/completions}（绝大多数中转站）</li>
 *   <li>{@link #OPENAI_RESPONSES} —— OpenAI Responses {@code POST {base}/responses}（Codex 系）</li>
 *   <li>{@link #ANTHROPIC} —— Anthropic Messages {@code POST {base}/messages}（Claude / Claude Code）</li>
 *   <li>{@link #GEMINI} —— Google Gemini {@code POST {base}/models/{model}:generateContent}</li>
 * </ul>
 *
 * <p>构建与解析都是纯函数（单测直接调）；只有 {@link #complete} 走网络。</p>
 */
final class ChatProtocols {

    static final String OPENAI_CHAT = "openai-chat";
    static final String OPENAI_RESPONSES = "openai-responses";
    static final String ANTHROPIC = "anthropic";
    static final String GEMINI = "gemini";
    static final String[] ALL = {OPENAI_CHAT, OPENAI_RESPONSES, ANTHROPIC, GEMINI};

    /** Anthropic 要求的 API 版本头。 */
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    /** Anthropic 必须给 max_tokens。 */
    private static final int ANTHROPIC_MAX_TOKENS = 2048;

    private ChatProtocols() {}

    /** 发一次问答请求，返回回答文本。 */
    static String complete(String protocol, String baseUrl, String apiKey, String model,
                           String[][] messages) throws IOException, ApiClient.ApiException {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new ApiClient.ApiException(0, ApiClient.MSG_NO_API_KEY);
        }
        switch (protocol) {
            case OPENAI_RESPONSES: return responses(baseUrl, apiKey, model, messages);
            case ANTHROPIC:        return anthropic(baseUrl, apiKey, model, messages);
            case GEMINI:           return gemini(baseUrl, apiKey, model, messages);
            default:
                // OpenAI 兼容：沿用既有实现（返回 choices[0].message.content，忽略 reasoning_content）
                return ApiClient.answerWithHistory(messages, baseUrl, apiKey, model);
        }
    }

    // ── OpenAI Responses ─────────────────────

    private static String responses(String baseUrl, String apiKey, String model, String[][] messages)
            throws IOException, ApiClient.ApiException {
        String body = buildResponsesBody(model, messages);
        String resp = Http.postJson(trimSlash(baseUrl) + "/responses", apiKey, body, 60_000);
        return parseResponses(resp);
    }

    static String buildResponsesBody(String model, String[][] messages) throws IOException {
        try {
            JSONArray input = new JSONArray();
            String instructions = null;
            for (String[] m : messages) {
                if ("system".equals(m[0])) { instructions = m[1]; continue; } // Responses 用 instructions
                JSONObject o = new JSONObject();
                o.put("role", m[0]);
                o.put("content", m[1]);
                input.put(o);
            }
            JSONObject body = new JSONObject();
            body.put("model", model);
            if (instructions != null) body.put("instructions", instructions);
            body.put("input", input);
            return body.toString();
        } catch (JSONException e) {
            throw new IOException("JSON build error: " + e.getMessage(), e);
        }
    }

    /** 取 {@code output_text}，否则拼 {@code output[].content[]} 里 type=output_text 的 text（跳过 reasoning）。 */
    static String parseResponses(String json) throws ApiClient.ApiException {
        try {
            JSONObject resp = new JSONObject(json);
            checkError(resp);
            String ot = resp.optString("output_text", "");
            if (!ot.isEmpty()) return ot.trim();
            StringBuilder sb = new StringBuilder();
            JSONArray output = resp.optJSONArray("output");
            if (output != null) {
                for (int i = 0; i < output.length(); i++) {
                    JSONObject item = output.optJSONObject(i);
                    JSONArray content = item == null ? null : item.optJSONArray("content");
                    if (content == null) continue;
                    for (int j = 0; j < content.length(); j++) {
                        JSONObject c = content.optJSONObject(j);
                        if (c == null || !"output_text".equals(c.optString("type", ""))) continue;
                        append(sb, c.optString("text", ""));
                    }
                }
            }
            if (sb.length() == 0) throw new ApiClient.ApiException(0, "empty content in response");
            return sb.toString();
        } catch (JSONException e) {
            throw new ApiClient.ApiException(0, "JSON parse error: " + e.getMessage());
        }
    }

    // ── Anthropic Messages ───────────────────

    private static String anthropic(String baseUrl, String apiKey, String model, String[][] messages)
            throws IOException, ApiClient.ApiException {
        String body = buildAnthropicBody(model, messages);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + apiKey);   // 网关通用
        h.put("x-api-key", apiKey);                   // Anthropic 原生
        h.put("anthropic-version", ANTHROPIC_VERSION);
        String resp = Http.postJson(trimSlash(baseUrl) + "/messages", h, body, 60_000);
        return parseAnthropic(resp);
    }

    static String buildAnthropicBody(String model, String[][] messages) throws IOException {
        try {
            JSONArray msgs = new JSONArray();
            String system = null;
            for (String[] m : messages) {
                if ("system".equals(m[0])) { system = m[1]; continue; } // Anthropic 用顶层 system
                JSONObject o = new JSONObject();
                o.put("role", m[0]); // user / assistant
                o.put("content", m[1]);
                msgs.put(o);
            }
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("max_tokens", ANTHROPIC_MAX_TOKENS);
            if (system != null) body.put("system", system);
            body.put("messages", msgs);
            return body.toString();
        } catch (JSONException e) {
            throw new IOException("JSON build error: " + e.getMessage(), e);
        }
    }

    /** 拼 {@code content[]} 里 type=text 的块（跳过 thinking）。 */
    static String parseAnthropic(String json) throws ApiClient.ApiException {
        try {
            JSONObject resp = new JSONObject(json);
            checkError(resp);
            StringBuilder sb = new StringBuilder();
            JSONArray content = resp.optJSONArray("content");
            if (content != null) {
                for (int i = 0; i < content.length(); i++) {
                    JSONObject c = content.optJSONObject(i);
                    if (c == null || !"text".equals(c.optString("type", ""))) continue;
                    append(sb, c.optString("text", ""));
                }
            }
            if (sb.length() == 0) throw new ApiClient.ApiException(0, "empty content in response");
            return sb.toString();
        } catch (JSONException e) {
            throw new ApiClient.ApiException(0, "JSON parse error: " + e.getMessage());
        }
    }

    // ── Google Gemini ────────────────────────

    private static String gemini(String baseUrl, String apiKey, String model, String[][] messages)
            throws IOException, ApiClient.ApiException {
        String body = buildGeminiBody(messages);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + apiKey);
        h.put("x-goog-api-key", apiKey);   // Gemini 原生
        String resp = Http.postJson(geminiUrl(baseUrl, model), h, body, 60_000);
        return parseGemini(resp);
    }

    static String geminiUrl(String baseUrl, String model) {
        return trimSlash(baseUrl) + "/models/" + model + ":generateContent";
    }

    /** Gemini 的角色是 user / model（不是 assistant），系统提示走 systemInstruction。 */
    static String buildGeminiBody(String[][] messages) throws IOException {
        try {
            JSONArray contents = new JSONArray();
            String system = null;
            for (String[] m : messages) {
                if ("system".equals(m[0])) { system = m[1]; continue; }
                JSONObject part = new JSONObject();
                part.put("text", m[1]);
                JSONArray parts = new JSONArray();
                parts.put(part);
                JSONObject c = new JSONObject();
                c.put("role", "assistant".equals(m[0]) ? "model" : m[0]);
                c.put("parts", parts);
                contents.put(c);
            }
            JSONObject body = new JSONObject();
            body.put("contents", contents);
            if (system != null) {
                JSONObject sp = new JSONObject();
                sp.put("text", system);
                JSONArray sparts = new JSONArray();
                sparts.put(sp);
                JSONObject si = new JSONObject();
                si.put("parts", sparts);
                body.put("systemInstruction", si);
            }
            return body.toString();
        } catch (JSONException e) {
            throw new IOException("JSON build error: " + e.getMessage(), e);
        }
    }

    /** 拼 {@code candidates[0].content.parts[].text}（跳过 thought 思考块）。 */
    static String parseGemini(String json) throws ApiClient.ApiException {
        try {
            JSONObject resp = new JSONObject(json);
            checkError(resp);
            JSONArray cands = resp.optJSONArray("candidates");
            if (cands == null || cands.length() == 0) {
                throw new ApiClient.ApiException(0, "empty candidates");
            }
            JSONObject content = cands.getJSONObject(0).optJSONObject("content");
            JSONArray parts = content == null ? null : content.optJSONArray("parts");
            StringBuilder sb = new StringBuilder();
            if (parts != null) {
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject p = parts.optJSONObject(i);
                    if (p == null || p.optBoolean("thought", false)) continue;
                    append(sb, p.optString("text", ""));
                }
            }
            if (sb.length() == 0) throw new ApiClient.ApiException(0, "empty text in response");
            return sb.toString();
        } catch (JSONException e) {
            throw new ApiClient.ApiException(0, "JSON parse error: " + e.getMessage());
        }
    }

    // ── 共用 ────────────────────────────────

    private static void append(StringBuilder sb, String text) {
        if (text == null || text.isEmpty()) return;
        if (sb.length() > 0) sb.append('\n');
        sb.append(text);
    }

    /** 2xx 但正文带 error 的情况（网关把错误放在 200 里）。 */
    private static void checkError(JSONObject resp) throws ApiClient.ApiException {
        if (!resp.has("error") || resp.isNull("error")) return;
        Object err = resp.opt("error");
        String msg = "unknown error";
        if (err instanceof JSONObject) msg = ((JSONObject) err).optString("message", msg);
        else if (err != null) msg = String.valueOf(err);
        throw new ApiClient.ApiException(0, ApiClient.cleanMessage(0, msg));
    }

    private static String trimSlash(String s) {
        return s == null ? "" : s.trim().replaceAll("/+$", "");
    }
}
