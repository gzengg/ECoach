package com.rd.englishcoach;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在线模型发现 + 三类接口连通性探测。
 *
 * <p>TokenDance 这类 OpenAI 兼容网关的 {@code GET {base}/models} 会返回全部模型及其
 * {@code supported_protocols}，据此把模型分成「问答 / 识别 / 朗读」三类，供模型页点击选择。
 * 解析是纯函数（单测直接调），只有 {@link #fetch} 与 probe* 走网络。</p>
 *
 * <p><b>鉴权与响应格式随协议走</b>（照 kelivo 的 listModels 做法）：OpenAI 兼容用
 * {@code Authorization: Bearer}；Gemini 要 {@code x-goog-api-key} 且目录在 {@code models[]}
 * （id 是 {@code models/xxx}，还有 {@code displayName}）；Anthropic 要 {@code x-api-key}
 * 加 {@code anthropic-version}。不按协议发头的话，这三家都会拉不到模型。</p>
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

    /** 拉目录的结果：{@code error != null} 表示失败，{@code error} 是给用户看的可读原因。 */
    static final class Listing {
        final List<ModelInfo> models;
        final String error;

        Listing(List<ModelInfo> models, String error) {
            this.models = models;
            this.error = error;
        }

        boolean ok() { return error == null; }
    }

    private ModelDiscovery() {}

    // ── 解析与分类（纯函数，单测直接调） ─────────

    /**
     * 解析模型目录，兼容三种形态：OpenAI / new-api 的 {@code data[]}、Gemini 的 {@code models[]}。
     *
     * <p>Gemini 的条目没有 {@code id}：id 在 {@code name} 里且带 {@code models/} 前缀
     * （{@code "models/gemini-2.5-flash"}），显示名在 {@code displayName}，上下文长度叫
     * {@code inputTokenLimit}——不认这几个字段就会「目录拿到了但列表是空的」。</p>
     */
    static List<ModelInfo> parseModels(String json) {
        List<ModelInfo> out = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray data = root.optJSONArray("data");
            if (data == null) data = root.optJSONArray("models"); // Gemini
            if (data == null) return out;
            for (int i = 0; i < data.length(); i++) {
                JSONObject o = data.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", "");
                String label = o.optString("name", "");
                // Anthropic 的目录叫 display_name（没有 name）——不认它就只剩一串 id 可看
                if (label.isEmpty()) label = o.optString("display_name", "");
                if (id.isEmpty()) {
                    id = stripModelsPrefix(label);   // Gemini：name 才是 id
                    label = o.optString("displayName", id);
                }
                if (id.isEmpty()) continue;
                if (label.isEmpty()) label = id;
                List<String> protocols = new ArrayList<>();
                // 不同网关字段名不一：TokenDance 用 supported_protocols，
                // tbtk/new-api 用 supported_endpoint_types（值为 openai / openai-response / anthropic）
                JSONArray ps = o.optJSONArray("supported_protocols");
                if (ps == null) ps = o.optJSONArray("supported_endpoint_types");
                if (ps != null) {
                    for (int j = 0; j < ps.length(); j++) protocols.add(ps.optString(j, ""));
                }
                long ctx = o.optLong("context_length", 0);
                if (ctx == 0) ctx = o.optLong("inputTokenLimit", 0);   // Gemini
                out.add(new ModelInfo(id, label, protocols, ctx));
            }
        } catch (JSONException ignored) {
            // 非 JSON（网关 HTML / 报错体）→ 当空列表，UI 报「没拿到模型」
        }
        return out;
    }

    private static String stripModelsPrefix(String name) {
        return name.startsWith("models/") ? name.substring("models/".length()) : name;
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
     * <p>网关的目录只挂在 OpenAI 根下（TokenDance 是 {@code /gateway/v1/models}）；
     * 而厂商协议的 Base 是别的子路径（{@code /gateway/minimax/v1}、{@code /gateway/ark}、
     * {@code /gateway/alibaba/...}），直接拼 {@code /models} 会 404。
     * 所以只要 Base 在 {@code /gateway/} 下但不是 {@code /gateway/v1*}，就改用网关目录。</p>
     */
    static String modelsUrl(String apiBase) {
        String b = trimSlash(apiBase);
        int g = b.indexOf("/gateway/");
        if (g > 0) {
            String after = b.substring(g);
            if (!after.startsWith("/gateway/v1")) {   // minimax / ark / alibaba 等厂商路径
                return b.substring(0, g) + "/gateway/v1/models";
            }
        }
        return b + "/models";
    }

    /**
     * 按协议拉模型目录（{@code GET {目录}}）。
     *
     * <p>失败时返回的 {@link Listing#error} 带<b>实际原因</b>（HTTP 码 + URL + 响应片段）——
     * 旧版一律吞成空列表，UI 只能说「检查 Base URL 是否可达」，401/404/超时完全分不清。</p>
     */
    static Listing fetch(String protocol, String apiBase, String apiKey) {
        String url = modelsUrl(apiBase);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("Accept", "application/json");
            for (Map.Entry<String, String> e : headers(protocol, apiKey).entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
            int code = conn.getResponseCode();
            String body = Http.readStream(code >= 400
                    ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 400) {
                return new Listing(new ArrayList<>(),
                        "HTTP " + code + " · " + url + "\n" + brief(body));
            }
            List<ModelInfo> models = parseModels(body);
            if (models.isEmpty()) {
                return new Listing(models, "返回的不是模型列表 · " + url + "\n" + brief(body));
            }
            return new Listing(models, null);
        } catch (Exception e) {
            return new Listing(new ArrayList<>(), "请求失败 · " + url + "\n" + msg(e));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 拉目录该带的鉴权头。
     *
     * <p>OpenAI 兼容 / MiniMax / 豆包 / new-api 都是 {@code Bearer}；
     * Gemini 只认 {@code x-goog-api-key}；Anthropic 要 {@code x-api-key}（并各自带版本头）。</p>
     */
    static Map<String, String> headers(String protocol, String apiKey) {
        Map<String, String> h = new LinkedHashMap<>();
        boolean hasKey = apiKey != null && !apiKey.isEmpty();
        if (ChatProtocols.GEMINI.equals(protocol)) {
            if (hasKey) h.put("x-goog-api-key", apiKey);
        } else if (ChatProtocols.ANTHROPIC.equals(protocol)) {
            if (hasKey) {
                h.put("x-api-key", apiKey);
                h.put("Authorization", "Bearer " + apiKey); // 网关 / 原生通吃
            }
            h.put("anthropic-version", "2023-06-01");
        } else if (hasKey) {
            h.put("Authorization", "Bearer " + apiKey);
        }
        return h;
    }

    /** 响应片段裁成一行，够定位就行（网关报错体可能是一整页 HTML）。 */
    private static String brief(String body) {
        if (body == null) return "";
        String s = body.trim().replaceAll("\\s+", " ");
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
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
