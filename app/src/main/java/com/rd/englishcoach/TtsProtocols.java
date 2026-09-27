package com.rd.englishcoach;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 朗读协议适配器：同一段文本，按不同协议合成为可播放的 WAV 字节。
 *
 * <ul>
 *   <li>{@link #CHAT_TTS} —— OpenAI 兼容 {@code POST {base}/chat/completions} + {@code modalities}（mimo，现网默认）</li>
 *   <li>{@link #SPEECH} —— OpenAI {@code POST {base}/audio/speech}，直接返回音频字节</li>
 *   <li>{@link #MINIMAX_T2A} —— MiniMax {@code POST {base}/t2a_v2}，音频在 {@code data.audio}（hex）</li>
 *   <li>{@link #ARK_TTS} —— 豆包 {@code POST {base}/v3/tts/unidirectional}，SSE 分帧 base64 音频</li>
 * </ul>
 *
 * <p>统一返回 WAV。Ark 的 SSE 分帧不能直接拼 WAV（会拼出多个头），所以请求 {@code pcm}
 * 再自己封 WAV 头。</p>
 */
final class TtsProtocols {

    static final String CHAT_TTS = "chat-tts";
    static final String SPEECH = "speech";
    static final String MINIMAX_T2A = "minimax-t2a";
    static final String ARK_TTS = "ark-tts";
    static final String[] ALL = {CHAT_TTS, SPEECH, MINIMAX_T2A, ARK_TTS};

    private static final int ARK_SAMPLE_RATE = 24000;

    private TtsProtocols() {}

    /** 合成一段文字，返回 WAV 字节。 */
    static byte[] synthesize(String protocol, String baseUrl, String apiKey, String model,
                             String voice, String text) throws IOException, ApiClient.ApiException {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new ApiClient.ApiException(0, ApiClient.MSG_NO_API_KEY);
        }
        switch (protocol) {
            case SPEECH:      return speech(baseUrl, apiKey, model, voice, text);
            case MINIMAX_T2A: return minimax(baseUrl, apiKey, model, voice, text);
            case ARK_TTS:     return ark(baseUrl, apiKey, model, voice, text);
            default:
                // mimo：assistant 角色 + modalities + audio.data
                return TtsClient.synthesize(baseUrl, apiKey, model, voice, text, "wav");
        }
    }

    /** 该协议的常见音色（用于设置页兜底列表；chat-tts 返回 null 表示用 Prefs 里的 mimo 列表）。 */
    static String[] defaultVoices(String protocol) {
        switch (protocol) {
            case SPEECH:
                return new String[]{"alloy", "echo", "fable", "onyx", "nova", "shimmer"};
            case MINIMAX_T2A:
                return new String[]{"male-qn-qingse", "female-shaonv", "male-qn-jingying",
                        "female-yujie", "audiobook_male_1"};
            case ARK_TTS:
                return new String[]{"zh_female_vv_uranus_bigtts",
                        "zh_male_M392_conversation_wvae_bigtts"};
            default:
                return null;
        }
    }

    // ── OpenAI /audio/speech ─────────────────

    private static byte[] speech(String baseUrl, String apiKey, String model, String voice, String text)
            throws IOException, ApiClient.ApiException {
        String body = buildSpeechBody(model, voice, text);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + apiKey);
        byte[] audio = Http.postBytes(trimSlash(baseUrl) + "/audio/speech", h,
                "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8), 120_000);
        if (audio.length < 44) throw new IOException("speech returned too few bytes (" + audio.length + ")");
        return audio;
    }

    static String buildSpeechBody(String model, String voice, String text) throws IOException {
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("input", text);
            body.put("voice", orElse(voice, "alloy"));
            body.put("response_format", "wav");
            return body.toString();
        } catch (JSONException e) {
            throw new IOException("JSON build error: " + e.getMessage(), e);
        }
    }

    // ── MiniMax T2A v2 ───────────────────────

    private static byte[] minimax(String baseUrl, String apiKey, String model, String voice, String text)
            throws IOException, ApiClient.ApiException {
        String body = buildMinimaxBody(model, voice, text);
        String resp = Http.postJson(trimSlash(baseUrl) + "/t2a_v2", apiKey, body, 120_000);
        return parseMinimax(resp);
    }

    static String buildMinimaxBody(String model, String voice, String text) throws IOException {
        try {
            JSONObject vs = new JSONObject();
            vs.put("voice_id", orElse(voice, "male-qn-qingse"));
            vs.put("speed", 1);
            vs.put("vol", 1);
            vs.put("pitch", 0);
            JSONObject as = new JSONObject();
            as.put("sample_rate", 32000);
            as.put("bitrate", 128000);
            as.put("format", "wav");
            as.put("channel", 1);
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("text", text);
            body.put("voice_setting", vs);
            body.put("audio_setting", as);
            return body.toString();
        } catch (JSONException e) {
            throw new IOException("JSON build error: " + e.getMessage(), e);
        }
    }

    /** 音频在 {@code data.audio}（hex 编码的完整 WAV）；出错时把 {@code base_resp} 的原因报出来。 */
    static byte[] parseMinimax(String json) throws IOException {
        try {
            JSONObject o = new JSONObject(json);
            JSONObject br = o.optJSONObject("base_resp");
            if (br != null && br.optInt("status_code", 0) != 0) {
                throw new IOException("MiniMax TTS " + br.optInt("status_code") + ": "
                        + br.optString("status_msg", ""));
            }
            JSONObject data = o.optJSONObject("data");
            String hex = data == null ? null : data.optString("audio", null);
            if (hex == null || hex.isEmpty()) throw new IOException("MiniMax TTS returned no audio");
            return hexToBytes(hex);
        } catch (JSONException e) {
            throw new IOException("JSON parse error: " + e.getMessage(), e);
        }
    }

    // ── 豆包 Ark TTS（SSE） ───────────────────

    private static byte[] ark(String baseUrl, String apiKey, String model, String voice, String text)
            throws IOException, ApiClient.ApiException {
        String body = buildArkBody(text, voice, ARK_SAMPLE_RATE);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + apiKey);
        h.put("X-Api-Resource-Id", model);
        InputStream in = Http.postStream(trimSlash(baseUrl) + "/v3/tts/unidirectional", h,
                "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8), 120_000);
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String payload = line.substring(5).trim();
                if (payload.isEmpty()) continue;
                JSONObject frame = new JSONObject(payload);
                int code = frame.optInt("code", 0);
                if (code == 20000000) break;                     // 成功完成（不是错误码）
                if (code != 0) {
                    throw new IOException("Ark TTS error: "
                            + frame.optString("message", "code " + code));
                }
                String data = frame.optString("data", "");
                if (!data.isEmpty()) pcm.write(java.util.Base64.getDecoder().decode(data));
            }
        } catch (JSONException e) {
            throw new IOException("Ark TTS frame parse error: " + e.getMessage(), e);
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
        byte[] raw = pcm.toByteArray();
        if (raw.length == 0) throw new IOException("Ark TTS returned no audio");
        return WavUtil.toWav(raw, ARK_SAMPLE_RATE);   // pcm 拼接后再封 WAV 头
    }

    static String buildArkBody(String text, String voice, int sampleRate) throws IOException {
        try {
            JSONObject audioParams = new JSONObject();
            audioParams.put("format", "pcm");   // pcm 分帧可直接拼接；wav 分帧拼不出合法文件
            audioParams.put("sample_rate", sampleRate);
            JSONObject req = new JSONObject();
            req.put("text", text);
            req.put("speaker", orElse(voice, "zh_female_vv_uranus_bigtts"));
            req.put("audio_params", audioParams);
            JSONObject body = new JSONObject();
            body.put("req_params", req);
            return body.toString();
        } catch (JSONException e) {
            throw new IOException("JSON build error: " + e.getMessage(), e);
        }
    }

    // ── 工具 ────────────────────────────────

    private static String orElse(String v, String def) {
        return (v == null || v.isEmpty()) ? def : v;
    }

    static byte[] hexToBytes(String hex) {
        int n = hex.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String trimSlash(String s) {
        return s == null ? "" : s.trim().replaceAll("/+$", "");
    }
}
