package com.rd.englishcoach;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * TtsClient 纯函数单测：验证请求体构建 + base64 音频解析。
 */
public class TtsClientTest {

    // ── buildTtsBody ──────────────────────────

    @Test
    public void buildTtsBody_assistantRole() throws Exception {
        String body = TtsClient.buildTtsBody("Hello", "mimo-v2.5-tts", "Mia", "wav");
        JSONObject json = new JSONObject(body);
        // messages[0].role 必须是 assistant
        assertEquals("assistant", json.getJSONArray("messages").getJSONObject(0).getString("role"));
        assertEquals("Hello", json.getJSONArray("messages").getJSONObject(0).getString("content"));
    }

    @Test
    public void buildTtsBody_hasModalities() throws Exception {
        String body = TtsClient.buildTtsBody("Hi", "mimo-v2.5-tts", "Mia", "wav");
        JSONObject json = new JSONObject(body);
        assertTrue(json.has("modalities"));
        assertEquals(2, json.getJSONArray("modalities").length());
        assertEquals("text", json.getJSONArray("modalities").getString(0));
        assertEquals("audio", json.getJSONArray("modalities").getString(1));
    }

    @Test
    public void buildTtsBody_hasAudio() throws Exception {
        String body = TtsClient.buildTtsBody("Hi", "mimo-v2.5-tts", "Mia", "wav");
        JSONObject json = new JSONObject(body);
        assertTrue(json.has("audio"));
        assertEquals("Mia", json.getJSONObject("audio").getString("voice"));
        assertEquals("wav", json.getJSONObject("audio").getString("format"));
    }

    @Test
    public void buildTtsBody_hasModel() throws Exception {
        String body = TtsClient.buildTtsBody("Hi", "mimo-v2.5-tts", "Mia", "wav");
        JSONObject json = new JSONObject(body);
        assertEquals("mimo-v2.5-tts", json.getString("model"));
    }

    // ── extractAudioBytes ─────────────────────

    @Test
    public void extractAudioBytes_valid() throws Exception {
        // 手动构造 44 字节最小 WAV 头（不依赖 android.util.Base64）
        byte[] wav = new byte[44];
        // RIFF header
        wav[0]='R'; wav[1]='I'; wav[2]='F'; wav[3]='F';
        wav[8]='W'; wav[9]='A'; wav[10]='V'; wav[11]='E';
        // fmt sub-chunk
        wav[12]='f'; wav[13]='m'; wav[14]='t'; wav[15]=' ';
        wav[20]=16; // PCM
        wav[22]=1;  // mono
        wav[24]=(byte)0x80; wav[25]=0x3E; // 16000 Hz
        wav[28]=(byte)0x00; wav[29]=0x7D; // byte rate
        wav[30]=2; // block align
        wav[34]=16; // bits per sample
        // data sub-chunk (0 bytes of audio)
        wav[36]='d'; wav[37]='a'; wav[38]='t'; wav[39]='a';

        // 编码为 base64（手写简单编码，不依赖 android）
        String fakeWavB64 = simpleBase64(wav);

        JSONObject audio = new JSONObject();
        audio.put("data", fakeWavB64);

        JSONObject message = new JSONObject();
        message.put("audio", audio);
        message.put("content", ""); // content 是空的，应该被忽略

        JSONObject choice = new JSONObject();
        choice.put("message", message);

        org.json.JSONArray choices = new org.json.JSONArray();
        choices.put(choice);

        JSONObject resp = new JSONObject();
        resp.put("choices", choices);

        byte[] result = TtsClient.extractAudioBytes(resp.toString());
        assertEquals(44, result.length);
    }

    @Test(expected = java.io.IOException.class)
    public void extractAudioBytes_emptyChoices() throws Exception {
        JSONObject resp = new JSONObject();
        resp.put("choices", new org.json.JSONArray());
        TtsClient.extractAudioBytes(resp.toString());
    }

    @Test(expected = java.io.IOException.class)
    public void extractAudioBytes_noAudioObject() throws Exception {
        JSONObject message = new JSONObject();
        message.put("content", "");

        JSONObject choice = new JSONObject();
        choice.put("message", message);

        org.json.JSONArray choices = new org.json.JSONArray();
        choices.put(choice);

        JSONObject resp = new JSONObject();
        resp.put("choices", choices);

        TtsClient.extractAudioBytes(resp.toString());
    }

    @Test(expected = java.io.IOException.class)
    public void extractAudioBytes_topLevelError() throws Exception {
        JSONObject error = new JSONObject();
        error.put("message", "quota exceeded");

        JSONObject resp = new JSONObject();
        resp.put("error", error);

        TtsClient.extractAudioBytes(resp.toString());
    }

    /** 最简 base64 编码（测试用，不依赖 android.util.Base64）。 */
    private static final String B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    private static String simpleBase64(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xFF;
            int b1 = (i + 1 < data.length) ? data[i + 1] & 0xFF : 0;
            int b2 = (i + 2 < data.length) ? data[i + 2] & 0xFF : 0;
            sb.append(B64.charAt(b0 >> 2));
            sb.append(B64.charAt(((b0 & 3) << 4) | (b1 >> 4)));
            sb.append((i + 1 < data.length) ? B64.charAt(((b1 & 15) << 2) | (b2 >> 6)) : '=');
            sb.append((i + 2 < data.length) ? B64.charAt(b2 & 63) : '=');
        }
        return sb.toString();
    }

    @Test(expected = java.io.IOException.class)
    public void extractAudioBytes_emptyData() throws Exception {
        JSONObject audio = new JSONObject();
        audio.put("data", "");

        JSONObject message = new JSONObject();
        message.put("audio", audio);

        JSONObject choice = new JSONObject();
        choice.put("message", message);

        org.json.JSONArray choices = new org.json.JSONArray();
        choices.put(choice);

        JSONObject resp = new JSONObject();
        resp.put("choices", choices);

        TtsClient.extractAudioBytes(resp.toString());
    }
}
