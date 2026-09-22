package com.rd.englishcoach;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import org.junit.Test;

/**
 * 问题2（读没反应）的源码契约测试：
 * <ul>
 *   <li>CaptureService.onSpeak 必须真正调 speak()，不能是空 TODO；</li>
 *   <li>必须创建 FallbackSpeechPlayer（Mimo + SystemTts）并注册 Listener；</li>
 *   <li>取词卡 btnSpeakSrc/btnSpeakDst 必须有点击接线；</li>
 *   <li>onDestroy 必须释放 speechPlayer；</li>
 *   <li>SystemTtsEngine / FallbackSpeechPlayer 必须实现 SpeechPlayer；</li>
 *   <li>未填 API Key 要有明确提示（AGENTS §9）；</li>
 *   <li>喇叭状态反馈 setSpeakState 必须接到 ERROR 提示。</li>
 * </ul>
 */
public class SpeakWiringTest {

    private static String readFile(String path) throws IOException {
        java.io.File f = new java.io.File(path);
        assertTrue("文件不存在: " + f.getAbsolutePath(), f.exists());
        try (Scanner s = new Scanner(f, StandardCharsets.UTF_8.name())) {
            s.useDelimiter("\\A");
            return s.hasNext() ? s.next() : "";
        }
    }

    /** 去掉块注释与行注释，避免注释里的关键词误判。 */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        while (i < src.length()) {
            if (i + 1 < src.length() && src.charAt(i) == '/' && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                i = end < 0 ? src.length() : end + 2;
            } else if (i + 1 < src.length() && src.charAt(i) == '/' && src.charAt(i + 1) == '/') {
                int end = src.indexOf('\n', i);
                i = end < 0 ? src.length() : end;
            } else {
                out.append(src.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    /** 取方法体（签名后第一个 { 起，配对到 }）。 */
    private static String methodBody(String src, String signature) {
        int start = src.indexOf(signature);
        if (start < 0) return null;
        int brace = src.indexOf('{', start);
        if (brace < 0) return null;
        int depth = 0;
        for (int i = brace; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return src.substring(brace + 1, i);
            }
        }
        return null;
    }

    // ── CaptureService 接线 ────────────────────

    @Test
    public void captureService_onSpeak_callsSpeak() throws Exception {
        String src = stripComments(readFile("src/main/java/com/rd/englishcoach/CaptureService.java"));
        String body = methodBody(src,
                "public void onSpeak(long turnId, String field, String text, String langHint)");
        assertNotNull("CaptureService 必须实现 onSpeak", body);
        assertFalse("onSpeak 不能再是 TODO 空实现", body.contains("TODO"));
        assertTrue("onSpeak 必须调用 speak() 走 SpeechPlayer",
                body.contains("speak("));
        assertTrue("onSpeak 必须用统一 key（speakKey）",
                body.contains("FloatingPanel.speakKey"));
    }

    @Test
    public void captureService_createsFallbackChain_andListener() throws Exception {
        String src = stripComments(readFile("src/main/java/com/rd/englishcoach/CaptureService.java"));
        assertTrue("必须创建 FallbackSpeechPlayer 链",
                src.contains("new FallbackSpeechPlayer("));
        assertTrue("链的 primary 必须是 MimoTtsEngine",
                src.contains("new MimoTtsEngine("));
        assertTrue("链的 fallback 必须是 SystemTtsEngine",
                src.contains("new SystemTtsEngine("));
        assertTrue("必须注册朗读状态 Listener",
                src.contains("speechPlayer.setListener("));
    }

    @Test
    public void captureService_onDestroy_releasesSpeechPlayer() throws Exception {
        String src = stripComments(readFile("src/main/java/com/rd/englishcoach/CaptureService.java"));
        String body = methodBody(src, "public void onDestroy()");
        assertNotNull("CaptureService 必须有 onDestroy", body);
        assertTrue("onDestroy 必须释放 speechPlayer",
                body.contains("speechPlayer.release()"));
        assertTrue("onDestroy 应先停再释放", body.contains("speechPlayer.stop()"));
    }

    @Test
    public void captureService_grabCard_buttonsWired() throws Exception {
        String src = stripComments(readFile("src/main/java/com/rd/englishcoach/CaptureService.java"));
        String body = methodBody(src, "private void addGrabCard(String source, String translated)");
        assertNotNull("必须有 addGrabCard", body);
        assertTrue("读原文按钮必须接点击", body.contains("btnSrc.setOnClickListener"));
        assertTrue("读译文按钮必须接点击", body.contains("btnDst.setOnClickListener"));
        assertTrue("必须注册喇叭状态按钮（读原文）",
                body.contains("registerSpeakButton(srcKey"));
        assertTrue("必须注册喇叭状态按钮（读译文）",
                body.contains("registerSpeakButton(dstKey"));
        assertTrue("译文按钮必须走 speechPlayer（经 speak()）",
                body.contains("speak(srcKey") && body.contains("speak(dstKey"));
    }

    @Test
    public void captureService_speak_guardsEmptyApiKey() throws Exception {
        String src = stripComments(readFile("src/main/java/com/rd/englishcoach/CaptureService.java"));
        String body = methodBody(src, "private void speak(String key, String text, String langHint)");
        assertNotNull("必须有统一 speak() 入口", body);
        assertTrue("未填 API Key 必须拦下并提示（AGENTS §9）",
                body.contains("apiKey()") && body.contains("isEmpty()"));
        assertTrue("必须用 msg_no_api_key 提示",
                body.contains("msg_no_api_key"));
        assertTrue("有 key 时必须真的调 speechPlayer.speak",
                body.contains("speechPlayer.speak("));
    }

    @Test
    public void captureService_speechState_forwardsToPanelAndShowsError() throws Exception {
        String src = stripComments(readFile("src/main/java/com/rd/englishcoach/CaptureService.java"));
        String body = methodBody(src,
                "private void onSpeechState(String key, SpeechPlayer.State state, String error)");
        assertNotNull("必须有 onSpeechState 状态回调", body);
        assertTrue("状态必须反馈到面板按钮颜色",
                body.contains("panel.setSpeakState(key, state)"));
        assertTrue("ERROR（两者都失败）必须面板提示",
                body.contains("panel.showMessage"));
        assertTrue("必须切主线程更新 UI", body.contains("mainHandler.post"));
    }

    // ── FloatingPanel 接线 ────────────────────

    @Test
    public void floatingPanel_callbackOnSpeak_hasFieldParam() throws Exception {
        Method m = null;
        for (Method candidate : FloatingPanel.Callback.class.getDeclaredMethods()) {
            if (candidate.getName().equals("onSpeak")) { m = candidate; break; }
        }
        assertNotNull("Callback 必须有 onSpeak", m);
        Class<?>[] p = m.getParameterTypes();
        assertEquals("onSpeak 应为 (long, String, String, String)", 4, p.length);
        assertEquals(long.class, p[0]);
        assertEquals(String.class, p[1]);
        assertEquals(String.class, p[2]);
        assertEquals(String.class, p[3]);
    }

    @Test
    public void floatingPanel_hasSpeakStateApi() throws Exception {
        String src = stripComments(readFile("src/main/java/com/rd/englishcoach/FloatingPanel.java"));
        assertTrue("必须有 speakKey 统一 key 工厂",
                src.contains("public static String speakKey("));
        assertTrue("必须有 registerSpeakButton",
                src.contains("public void registerSpeakButton("));
        assertTrue("必须有 setSpeakState 状态反馈",
                src.contains("public void setSpeakState("));
        String body = methodBody(src, "public void setSpeakState(String key, SpeechPlayer.State state)");
        assertNotNull(body);
        assertTrue("PLAYING 应为 success 色（AGENTS §11.2）", body.contains("case PLAYING") && body.contains("success"));
        assertTrue("LOADING 应为 warn 色", body.contains("case LOADING") && body.contains("warn"));
        assertTrue("ERROR 应为 danger 色", body.contains("case ERROR") && body.contains("danger"));
        assertTrue("IDLE/默认复位 accent_solid", body.contains("accent_solid"));
    }

    @Test
    public void floatingPanel_addTurn_wiresSpeakButtonsWithKeys() throws Exception {
        String src = stripComments(readFile("src/main/java/com/rd/englishcoach/FloatingPanel.java"));
        String body = methodBody(src, "public void addTurn(ConversationManager.Turn turn)");
        assertNotNull("必须有 addTurn", body);
        assertTrue("原文喇叭必须接 onSpeak", body.contains("cb.onSpeak(turn.id, \"transcript\""));
        assertTrue("回答喇叭必须接 onSpeak", body.contains("cb.onSpeak(turn.id, \"answer\""));
        assertTrue("原文喇叭必须注册状态 key", body.contains("registerSpeakButton(transcriptKey"));
        assertTrue("回答喇叭必须注册状态 key", body.contains("registerSpeakButton(answerKey"));
        assertTrue("语言应自动判定（speakLang），不再写死 en",
                body.contains("Translator.speakLang"));
    }

    @Test
    public void floatingPanel_removesSpeakButtonsOnCleanup() throws Exception {
        String src = stripComments(readFile("src/main/java/com/rd/englishcoach/FloatingPanel.java"));
        String clear = methodBody(src, "public void clearTurns()");
        assertNotNull(clear);
        assertTrue("clearTurns 必须清理喇叭注册", clear.contains("unregisterSpeakButtons"));
        String remove = methodBody(src, "public void removeTurn(long turnId)");
        assertNotNull(remove);
        assertTrue("removeTurn 必须清理喇叭注册", remove.contains("unregisterSpeakButtons"));
        String grab = methodBody(src, "public void clearGrabCards()");
        assertNotNull(grab);
        assertTrue("clearGrabCards 必须清理取词喇叭 key", grab.contains("grab:"));
    }

    // ── 引擎实现 ────────────────────────────

    @Test
    public void systemTtsEngine_implementsSpeechPlayer() {
        assertTrue("SystemTtsEngine 必须实现 SpeechPlayer",
                SpeechPlayer.class.isAssignableFrom(SystemTtsEngine.class));
    }

    @Test
    public void fallbackSpeechPlayer_implementsSpeechPlayer() {
        assertTrue("FallbackSpeechPlayer 必须实现 SpeechPlayer",
                SpeechPlayer.class.isAssignableFrom(FallbackSpeechPlayer.class));
    }

    @Test
    public void systemTtsEngine_usesQueueFlush_singleStream() throws Exception {
        String src = stripComments(readFile("src/main/java/com/rd/englishcoach/SystemTtsEngine.java"));
        assertTrue("系统 TTS 必须 QUEUE_FLUSH（同一时刻只播一路）",
                src.contains("TextToSpeech.QUEUE_FLUSH"));
        assertTrue("必须用 UtteranceProgressListener 收播完/失败状态",
                src.contains("UtteranceProgressListener"));
        assertTrue("utterance 结束必须回 IDLE", src.contains("State.IDLE"));
        assertTrue("utterance 失败必须回 ERROR", src.contains("State.ERROR"));
        assertTrue("release 必须 shutdown 系统 TTS", src.contains("tts.shutdown()"));
        assertTrue("初始化失败要报 ERROR", src.contains("TextToSpeech.SUCCESS"));
    }

    @Test
    public void fallbackSpeechPlayer_degradesOnError() throws Exception {
        String src = stripComments(readFile("src/main/java/com/rd/englishcoach/FallbackSpeechPlayer.java"));
        String body = methodBody(src, "private void onPrimaryState(String key, State state, String error)");
        assertNotNull("必须有 primary 状态处理", body);
        assertTrue("primary ERROR 必须触发降级", body.contains("State.ERROR"));
        assertTrue("降级必须让 fallback 重试同一段文本",
                body.contains("fallback.speak(key, pendingText, pendingLang)"));
        String fb = methodBody(src, "private void onFallbackState(String key, State state, String error)");
        assertNotNull("必须有 fallback 状态处理", fb);
        assertTrue("fallback 再 ERROR 才是两者都失败，透传 ERROR", fb.contains("forward(key, state, error)"));
    }

    // ── MimoTtsEngine 加固 ────────────────────

    @Test
    public void mimoTtsEngine_stopCancelsInflight() throws Exception {
        String src = stripComments(readFile("src/main/java/com/rd/englishcoach/MimoTtsEngine.java"));
        assertTrue("必须有代数计数取消在途合成", src.contains("generation++"));
        String stop = methodBody(src, "public void stop()");
        assertNotNull("MimoTtsEngine 必须有 stop", stop);
        assertTrue("stop 必须作废在途合成", stop.contains("generation++"));
        assertTrue("播放中 stop 应抛 IDLE 复位按钮", stop.contains("State.IDLE"));
        assertTrue("网络结果回调必须做代数/key 校验（过期不播）",
                src.contains("gen != generation") && src.contains("!key.equals(currentKey)"));
    }

    // ── Translator.speakLang ────────────────

    @Test
    public void translator_speakLang_detectsLanguage() {
        assertEquals("en", Translator.speakLang("Hello world"));
        assertEquals("zh-CN", Translator.speakLang("你好世界"));
        assertEquals("zh-CN", Translator.speakLang("hello 你好混合")); // 含 CJK → 中文
        assertEquals("en", Translator.speakLang(null));
        assertEquals("en", Translator.speakLang(""));
    }
}
