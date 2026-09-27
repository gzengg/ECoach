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
                JSONArray ps = o.optJSONArray("supported_protocols");
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
     * <p>主要看 {@code supported_protocols}（ASR=含 asr，TTS=含 tts/t2a/voice），
     * 问答额外要求有对话协议且 {@code context_length > 0}。
     * <b>但 mimo-v2.5-tts 这类只声明 {@code openai:chat-completions}（与文本模型同协议），
     * 只能靠 id 里的 tts/speech 关键词识别</b>，否则会被误归到问答。</p>
     */
    static Kind kindOf(ModelInfo m) {
        String id = m.id.toLowerCase();
        boolean asr = id.contains("asr");
        boolean tts = id.contains("tts") || id.contains("speech");
        boolean chat = false;
        for (String p : m.protocols) {
            String s = p.toLowerCase();
            if (s.contains("asr")) asr = true;
            if (s.contains("tts") || s.contains("t2a") || s.contains("voice")) tts = true;
            if (s.contains("chat-completions") || s.contains("messages")
                    || s.contains("responses")) chat = true;
        }
        if (asr) return Kind.ASR;
        if (tts) return Kind.TTS;
        if (chat && m.contextLength > 0) return Kind.CHAT;
        return null;
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

    /** {@code GET {目录}}（无需鉴权）。失败返回空列表。 */
    static List<ModelInfo> fetchModels(String apiBase) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(modelsUrl(apiBase)).openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("Accept", "application/json");
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

    static String probeChat(String baseUrl, String key, String model) {
        try {
            ApiClient.answerWithHistory(new String[][]{{"user", "ping"}}, baseUrl, key, model);
            return null;
        } catch (Exception e) {
            return msg(e);
        }
    }

    static String probeTts(String baseUrl, String key, String model) {
        try {
            TtsClient.synthesize(baseUrl, key, model, Prefs.DEF_TTS_VOICE_EN, "hi", "wav");
            return null;
        } catch (Exception e) {
            return msg(e);
        }
    }

    /**
     * 识别探测：发 0.5 秒静音。
     *
     * <p>实测（TokenDance / qwen-audio）：静音返回 HTTP 400 空体，只是「没听到语音」，
     * 说明端点可达、Key 有效；Key 无效则返回 401。所以 400 也算连通正常。</p>
     */
    static String probeAsr(String asrUrl, String key, String model) {
        try {
            ApiClient.transcribe(WavUtil.toWav(new byte[16000], 16000), asrUrl, key, model);
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
