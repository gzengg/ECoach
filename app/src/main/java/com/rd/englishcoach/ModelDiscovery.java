package com.rd.englishcoach;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 在线模型发现 + 三类接口连通性探测。
 *
 * <p>TokenDance 这类 OpenAI 兼容网关的 {@code GET {base}/models} 会返回全部模型及其
 * {@code supported_protocols}，据此把模型分成「问答 / 识别 / 朗读」三类，供设置页点击选择。
 * 解析是纯函数（单测直接调），只有 {@link #fetchModels} 与 probe* 走网络。</p>
 */
final class ModelDiscovery {

    /** 一个可发现的模型。 */
    static final class ModelInfo {
        final String id;
        final String name;
        final List<String> protocols;
        final long contextLength;

        ModelInfo(String id, String name, List<String> protocols, long contextLength) {
            this.id = id;
            this.name = name;
            this.protocols = protocols;
            this.contextLength = contextLength;
        }
    }

    enum Kind { CHAT, ASR, TTS }

    private ModelDiscovery() {}

    // ── 解析与分类（纯函数，单测直接调） ─────────

    static List<ModelInfo> parseModels(String json) {
        List<ModelInfo> out = new ArrayList<>();
        try {
            JSONArray data = new JSONObject(json).optJSONArray("data");
            if (data == null) return out;
            for (int i = 0; i < data.length(); i++) {
                JSONObject o = data.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", "");
                if (id.isEmpty()) continue;
                List<String> protocols = new ArrayList<>();
                // 不同网关字段名不一：TokenDance 用 supported_protocols，
                // tbtk/new-api 用 supported_endpoint_types（值为 openai / openai-response / anthropic）
                JSONArray ps = o.optJSONArray("supported_protocols");
                if (ps == null) ps = o.optJSONArray("supported_endpoint_types");
                if (ps != null) {
                    for (int j = 0; j < ps.length(); j++) protocols.add(ps.optString(j, ""));
                }
                out.add(new ModelInfo(id, o.optString("name", id), protocols,
                        o.optLong("context_length", 0)));
            }
        } catch (JSONException ignored) {
            // 非 JSON（网关 HTML / 报错体）→ 当空列表，UI 报「没拿到模型」
        }
        return out;
    }

    /**
     * 按用途分类；不属于三类（图像 / 视频 / 搜索 / 向量等）返回 null。
     *
     * <p>用<b>白名单</b>认对话协议（chat/messages/responses/generate-content/…），
     * 避开「openai:image-generations」这类含 openai 但非对话的协议；
     * ASR / TTS 同样看协议关键词，另外 mimo-v2.5-tts 这类只声明
     * {@code openai:chat-completions} 的靠 id 里的 tts/speech 识别。</p>
     */
    static Kind kindOf(ModelInfo m) {
        String id = m.id.toLowerCase();
        boolean asr = id.contains("asr") || id.contains("transcri") || id.contains("whisper");
        boolean tts = id.contains("tts") || id.contains("speech") || id.contains("t2a");
        boolean chat = false;
        for (String p : m.protocols) {
            String s = p.toLowerCase();
            if (s.contains("asr") || s.contains("transcription")) { asr = true; continue; }
            if (s.contains("tts") || s.contains("t2a") || s.contains("voice")) { tts = true; continue; }
            if (isChatProtocol(s)) chat = true;
        }
        if (asr) return Kind.ASR;
        if (tts) return Kind.TTS;
        if (chat) return Kind.CHAT;
        // 目录没给协议信息（部分网关只返回 id/name）→ 当文本模型
        if (m.protocols.isEmpty()) return Kind.CHAT;
        // 有协议但都不属于三类：只有明显是文本模型（有上下文长度）才收录
        return m.contextLength > 0 ? Kind.CHAT : null;
    }

    /** 白名单识别对话协议，避开含 openai 但非对话的（图像/向量/重排等）。 */
    private static boolean isChatProtocol(String s) {
        return s.contains("chat")            // openai:chat-completions
            || s.contains("messages")        // anthropic:messages
            || s.contains("responses")       // openai:responses
            || s.contains("response")        // openai-response（new-api 风格）
            || s.equals("openai")            // new-api 简要写法
            || s.contains("generate-content")// gemini
            || s.contains("gemini")
            || s.contains("anthropic");
    }

    static List<ModelInfo> byKind(List<ModelInfo> all, Kind kind) {
        List<ModelInfo> out = new ArrayList<>();
        for (ModelInfo m : all) if (kindOf(m) == kind) out.add(m);
        return out;
    }

    // ── 网络：拉模型目录 ────────────────

    /**
     * 模型目录地址。
     *
     * <p>OpenAI 兼容的根（{@code .../v1}）直接拼 {@code /models}；
     * <b>DashScope 识别端点是完整路径</b>（{@code .../alibaba/api/v1/services/aigc/…/generation}），
     * 没有 {@code /models}，改用同网关的目录 {@code {origin}/gateway/v1/models}。</p>
     */
    static String modelsUrl(String apiBase) {
        String b = trimSlash(apiBase);
        int i = b.indexOf("/alibaba/api/v1/services/");
        if (i > 0) return b.substring(0, i) + "/v1/models";
        return b + "/models";
    }

    /** {@code GET {目录}}。有的网关（new-api/tbtk）列表也要鉴权，所以要带 key；失败返回空列表。 */
    static List<ModelInfo> fetchModels(String apiBase, String apiKey) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(modelsUrl(apiBase)).openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("Accept", "application/json");
            if (apiKey != null && !apiKey.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            return parseModels(Http.readStream(conn.getInputStream()));
        } catch (Exception e) {
            return new ArrayList<>();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String trimSlash(String s) {
        return s == null ? "" : s.trim().replaceAll("/+$", "");
    }

    // ── 连通性探测（返回 null = 正常，否则可读原因） ──────

    static String probeChat(String protocol, String baseUrl, String key, String model) {
        try {
            ChatProtocols.complete(protocol, baseUrl, key, model, new String[][]{{"user", "ping"}});
            return null;
        } catch (Exception e) {
            return msg(e);
        }
    }

    static String probeTts(String protocol, String baseUrl, String key, String model) {
        try {
            TtsProtocols.synthesize(protocol, baseUrl, key, model, firstVoice(protocol), "hi");
            return null;
        } catch (Exception e) {
            return msg(e);
        }
    }

    private static String firstVoice(String protocol) {
        String[] v = TtsProtocols.defaultVoices(protocol);
        return (v != null && v.length > 0) ? v[0] : Prefs.DEF_TTS_VOICE_EN;
    }

    /**
     * 识别探测：发 0.5 秒静音。
     *
     * <p>实测（TokenDance / qwen-audio）：静音返回 HTTP 400 空体，只是「没听到语音」，
     * 说明端点可达、Key 有效；Key 无效则返回 401。所以 400 也算连通正常。</p>
     */
    static String probeAsr(String protocol, String asrUrl, String key, String model) {
        try {
            AsrProtocols.transcribe(protocol, asrUrl, key, model,
                    WavUtil.toWav(new byte[16000], 16000));
            return null;
        } catch (ApiClient.ApiException e) {
            if (e.httpCode == 400) return null; // 静音无语音 → 端点与 Key 都正常
            return msg(e);
        } catch (Exception e) {
            return msg(e);
        }
    }

    private static String msg(Exception e) {
        String m = e.getMessage();
        return (m == null || m.isEmpty()) ? e.toString() : m;
    }
}
