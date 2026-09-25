package com.rd.englishcoach;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * FallbackSpeechPlayer 行为测试（问题2：朗读没反应的兜底链核心逻辑）。
 * 用假的 SpeechPlayer 记录调用，验证：
 *   primary 成功不触发兜底；primary 失败自动降级且不外抛；
 *   两者都失败只抛一次 ERROR；同 key 再点 = 停止；
 *   换 key 先停旧的（不双响）；过期事件被丢弃；stop()/release() 传导。
 */
public class FallbackSpeechPlayerTest {

    /** 假 player：记录调用，可由测试手动触发状态回调。 */
    private static final class FakePlayer implements SpeechPlayer {
        final String name;
        final List<String> calls = new ArrayList<>();
        Listener listener;
        boolean speaking;
        FakePlayer(String name) { this.name = name; }

        @Override public void speak(String key, String text, String langHint) {
            calls.add("speak:" + key + ":" + text + ":" + langHint);
        }
        @Override public void stop() { calls.add("stop"); speaking = false; }
        @Override public boolean isSpeaking() { return speaking; }
        @Override public void release() { calls.add("release"); }
        @Override public void setListener(Listener l) { this.listener = l; }

        void emit(String key, State state, String err) {
            if (listener != null) listener.onState(key, state, err);
        }
    }

    /** 记录对外事件的监听器。 */
    private static final class Recorder implements SpeechPlayer.Listener {
        final List<String> events = new ArrayList<>();
        @Override public void onState(String key, SpeechPlayer.State state, String errorMessage) {
            events.add(key + ":" + state + (errorMessage != null ? ":" + errorMessage : ""));
        }
    }

    private final FakePlayer primary = new FakePlayer("primary");
    private final FakePlayer fallback = new FakePlayer("fallback");
    private final FallbackSpeechPlayer player = new FallbackSpeechPlayer(primary, fallback);
    private final Recorder rec = new Recorder();

    @Test
    public void primarySuccess_fallbackNeverEngaged() {
        player.setListener(rec);
        player.speak("k1", "hello", "en");

        assertEquals(1, primary.calls.size());
        assertTrue("primary 应收到 speak", primary.calls.get(0).startsWith("speak:k1:hello"));
        assertTrue("primary 成功时不应调 fallback", fallback.calls.isEmpty());

        primary.emit("k1", SpeechPlayer.State.LOADING, null);
        primary.emit("k1", SpeechPlayer.State.PLAYING, null);
        primary.emit("k1", SpeechPlayer.State.IDLE, null);

        assertEquals(3, rec.events.size());
        assertEquals("k1:LOADING", rec.events.get(0));
        assertEquals("k1:PLAYING", rec.events.get(1));
        assertEquals("k1:IDLE", rec.events.get(2));
    }

    @Test
    public void primaryError_fallsBack_withoutForwardingError() {
        player.setListener(rec);
        player.speak("k1", "hello", "en");
        primary.emit("k1", SpeechPlayer.State.LOADING, null);
        primary.emit("k1", SpeechPlayer.State.ERROR, "boom");

        assertEquals("降级时 fallback 应重试同一段文本",
                1, fallback.calls.size());
        assertTrue(fallback.calls.get(0).startsWith("speak:k1:hello"));
        for (String e : rec.events) {
            assertFalse("primary 失败不应对外抛 ERROR（要先降级）: " + e,
                    e.contains("ERROR"));
        }
    }

    @Test
    public void bothFail_forwardsSingleError() {
        player.setListener(rec);
        player.speak("k1", "hello", "en");
        primary.emit("k1", SpeechPlayer.State.ERROR, "p-fail");
        fallback.emit("k1", SpeechPlayer.State.ERROR, "f-fail");

        long errors = rec.events.stream().filter(e -> e.contains("ERROR")).count();
        assertEquals("两者都失败只应对外抛一次 ERROR", 1, errors);
        String last = rec.events.get(rec.events.size() - 1);
        // 真机踩过：只报兜底的「系统朗读初始化失败」，用户完全看不到在线朗读为什么挂
        assertTrue("必须报出主引擎（根因）的原因: " + last, last.contains("p-fail"));
        assertTrue("兜底的原因也要带上: " + last, last.contains("f-fail"));
    }

    @Test
    public void fallbackSuccess_forwardsNormally() {
        player.setListener(rec);
        player.speak("k1", "hello", "en");
        primary.emit("k1", SpeechPlayer.State.ERROR, "p-fail");
        fallback.emit("k1", SpeechPlayer.State.PLAYING, null);
        fallback.emit("k1", SpeechPlayer.State.IDLE, null);

        assertEquals(2, rec.events.size());
        assertEquals("k1:PLAYING", rec.events.get(0));
        assertEquals("k1:IDLE", rec.events.get(1));
    }

    @Test
    public void sameKeyWhileActive_stops() {
        player.setListener(rec);
        player.speak("k1", "hello", "en");
        primary.emit("k1", SpeechPlayer.State.PLAYING, null);

        player.speak("k1", "hello", "en"); // 同 key 再点 = 停止

        assertTrue("primary 应被停", primary.calls.contains("stop"));
        assertTrue("fallback 应被停", fallback.calls.contains("stop"));
        assertEquals("停止应对外抛 IDLE（让按钮复位）",
                "k1:IDLE", rec.events.get(rec.events.size() - 1));
        assertFalse(player.isSpeaking());
    }

    @Test
    public void switchKey_stopsOldFirst_noDoubleStream() {
        player.setListener(rec);
        player.speak("k1", "one", "en");
        primary.emit("k1", SpeechPlayer.State.PLAYING, null);
        primary.speaking = true;

        player.speak("k2", "two", "en");

        int stopIdx = primary.calls.indexOf("stop");
        int speakK2 = -1;
        for (int i = 0; i < primary.calls.size(); i++) {
            if (primary.calls.get(i).startsWith("speak:k2")) { speakK2 = i; break; }
        }
        assertTrue("换 key 应先停旧的", stopIdx >= 0);
        assertTrue("换 key 应发起新 speak", speakK2 >= 0);
        assertTrue("stop 必须发生在新 speak 之前", stopIdx < speakK2);
        assertEquals("旧 key 应抛 IDLE 让按钮复位",
                "k1:IDLE", rec.events.get(rec.events.size() - 1));
    }

    @Test
    public void staleEvents_afterStop_areIgnored() {
        player.setListener(rec);
        player.speak("k1", "hello", "en");
        player.stop();
        rec.events.clear();

        // 迟到的过期回调（网络合成在 stop 后才返回）
        primary.emit("k1", SpeechPlayer.State.PLAYING, null);
        primary.emit("k1", SpeechPlayer.State.ERROR, "late");

        assertEquals("stop 后的过期事件应被丢弃", 0, rec.events.size());
        assertFalse(player.isSpeaking());
    }

    @Test
    public void isSpeaking_reflectsEitherPlayer() {
        player.setListener(rec);
        player.speak("k1", "hello", "en");
        primary.emit("k1", SpeechPlayer.State.ERROR, "p-fail");
        fallback.speaking = true;
        assertTrue("兜底在播也算在播", player.isSpeaking());
        fallback.speaking = false;
        assertFalse(player.isSpeaking());
    }

    @Test
    public void release_propagatesToBoth() {
        player.release();
        assertTrue(primary.calls.contains("release"));
        assertTrue(fallback.calls.contains("release"));
    }

    @Test
    public void stop_withoutActive_doesNotNotifyIdle() {
        player.setListener(rec);
        player.stop();
        assertEquals("空 stop 不应发事件", 0, rec.events.size());
    }

    @Test
    public void constructor_wiresInternalListeners() {
        // 构造时就把内部监听器挂到两个引擎上（setListener 被外部覆盖前）
        assertTrue("primary 必须挂上内部监听", primary.listener != null);
        assertTrue("fallback 必须挂上内部监听", fallback.listener != null);
    }
}
