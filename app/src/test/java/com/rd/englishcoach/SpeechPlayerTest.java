package com.rd.englishcoach;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * SpeechPlayer 接口 + MimoTtsEngine 行为验证。
 * MimoTtsEngine 依赖 Android Context，无法在纯 JVM 上实例化，
 * 但可以验证接口约定和 TtsClient 静态方法（已由 TtsClientTest 覆盖）。
 */
public class SpeechPlayerTest {

    @Test
    public void speechPlayerInterface_hasAllMethods() throws Exception {
        // 验证 SpeechPlayer 接口的方法签名存在
        java.lang.reflect.Method[] methods = SpeechPlayer.class.getDeclaredMethods();
        java.util.Set<String> names = new java.util.HashSet<>();
        for (java.lang.reflect.Method m : methods) names.add(m.getName());

        assertTrue("必须有 speak 方法", names.contains("speak"));
        assertTrue("必须有 stop 方法", names.contains("stop"));
        assertTrue("必须有 isSpeaking 方法", names.contains("isSpeaking"));
        assertTrue("必须有 release 方法", names.contains("release"));
        assertTrue("必须有 setListener 方法", names.contains("setListener"));
    }

    @Test
    public void speechPlayerStateEnum_hasAllValues() throws Exception {
        SpeechPlayer.State[] values = SpeechPlayer.State.values();
        assertEquals(4, values.length);
        assertEquals(SpeechPlayer.State.IDLE, SpeechPlayer.State.valueOf("IDLE"));
        assertEquals(SpeechPlayer.State.LOADING, SpeechPlayer.State.valueOf("LOADING"));
        assertEquals(SpeechPlayer.State.PLAYING, SpeechPlayer.State.valueOf("PLAYING"));
        assertEquals(SpeechPlayer.State.ERROR, SpeechPlayer.State.valueOf("ERROR"));
    }

    @Test
    public void speechPlayerListenerInterface_exists() {
        // 验证 Listener 内部接口存在
        assertNotNull(SpeechPlayer.Listener.class);
        java.lang.reflect.Method[] methods = SpeechPlayer.Listener.class.getDeclaredMethods();
        assertEquals(1, methods.length);
        assertEquals("onState", methods[0].getName());
    }

    @Test
    public void mimoTtsEngine_implementsSpeechPlayer() {
        // 验证 MimoTtsEngine 实现了 SpeechPlayer（编译时检查，运行时需要 Context）
        assertTrue("MimoTtsEngine 必须实现 SpeechPlayer",
                SpeechPlayer.class.isAssignableFrom(MimoTtsEngine.class));
    }

    @Test
    public void ttsClient_buildTtsBody_voiceIsPassed() throws Exception {
        // 验证不同声音名被正确传递
        String body1 = TtsClient.buildTtsBody("Hi", "mimo-v2.5-tts", "Mia", "wav");
        String body2 = TtsClient.buildTtsBody("Hi", "mimo-v2.5-tts", "冰糖", "wav");
        org.json.JSONObject j1 = new org.json.JSONObject(body1);
        org.json.JSONObject j2 = new org.json.JSONObject(body2);
        assertEquals("Mia", j1.getJSONObject("audio").getString("voice"));
        assertEquals("冰糖", j2.getJSONObject("audio").getString("voice"));
    }
}
