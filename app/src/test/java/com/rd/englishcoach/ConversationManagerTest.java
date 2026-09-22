package com.rd.englishcoach;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * ConversationManager 的回归测试。
 */
public class ConversationManagerTest {

    private final ConversationManager mgr = new ConversationManager(5);

    // ── 转录 ──────────────────────────────

    @Test
    public void addTranscript_newestFirst() {
        mgr.addTranscript("A");
        mgr.addTranscript("B");
        assertEquals("B", mgr.newest().content);
    }

    @Test
    public void addTranscript_turnTypeIsTranscript() {
        assertEquals(ConversationManager.TurnType.TRANSCRIPT, mgr.addTranscript("x").type);
    }

    @Test
    public void addTranscript_answerIsNull_regression() {
        ConversationManager.Turn t = mgr.addTranscript("test");
        assertNull("回归：新转录不应自动生成答案", t.aiAnswer);
        assertEquals(ConversationManager.AnswerState.NONE, t.state);
    }

    // ── 自定义提问 ──────────────────────────

    @Test
    public void addQuestion_turnTypeIsQuestion() {
        assertEquals(ConversationManager.TurnType.QUESTION, mgr.addQuestion("Why?").type);
    }

    @Test
    public void addQuestion_newestFirst() {
        mgr.addTranscript("A");
        mgr.addQuestion("Q1");
        assertEquals(ConversationManager.TurnType.QUESTION, mgr.newest().type);
        assertEquals(2, mgr.size());
    }

    // ── AI 回答生命周期 ──────────────────────

    @Test
    public void beginAnswerRequest_blocksDuplicate() {
        ConversationManager.Turn t = mgr.addTranscript("x");
        assertTrue(mgr.beginAnswerRequest(t.id));
        assertFalse(mgr.beginAnswerRequest(t.id));
    }

    @Test
    public void completeAnswer_transitionsToReady() {
        ConversationManager.Turn t = mgr.addTranscript("x");
        mgr.beginAnswerRequest(t.id);
        mgr.completeAnswer(t.id, "OK");
        assertEquals(ConversationManager.AnswerState.READY, t.state);
        assertTrue(t.hasAnswer());
    }

    @Test
    public void failAnswer_allowsRetry() {
        ConversationManager.Turn t = mgr.addTranscript("x");
        mgr.beginAnswerRequest(t.id);
        mgr.failAnswer(t.id, "error");
        assertEquals(ConversationManager.AnswerState.ERROR, t.state);
        assertTrue(mgr.beginAnswerRequest(t.id));
    }

    // ── buildMessages ─────────────────────

    @Test
    public void buildMessages_includesSystemPrompt() {
        mgr.addTranscript("Hello");
        String[][] msgs = mgr.buildMessages("You are a test");
        assertEquals("system", msgs[0][0]);
        assertEquals("You are a test", msgs[0][1]);
    }

    @Test
    public void buildMessages_oldestFirst() {
        mgr.addTranscript("A");
        mgr.addTranscript("B");
        String[][] msgs = mgr.buildMessages("sys");
        // sys, A, B → 3 messages
        assertEquals(3, msgs.length);
        assertEquals("user", msgs[1][0]);
        assertEquals("A", msgs[1][1]);
        assertEquals("user", msgs[2][0]);
        assertEquals("B", msgs[2][1]);
    }

    @Test
    public void buildMessages_includesAnswers() {
        ConversationManager.Turn t = mgr.addTranscript("Q?");
        mgr.beginAnswerRequest(t.id);
        mgr.completeAnswer(t.id, "A.");
        String[][] msgs = mgr.buildMessages("sys");
        // sys, Q?, A. → 3 messages
        assertEquals(3, msgs.length);
        assertEquals("assistant", msgs[2][0]);
        assertEquals("A.", msgs[2][1]);
    }

    // ── capacity ─────────────────────────

    @Test
    public void capacity_dropsOldest() {
        for (int i = 0; i < 7; i++) mgr.addTranscript("s" + i);
        assertEquals(5, mgr.size());
    }

    // ── deleteById ────────────────────────────

    @Test
    public void deleteById_removesExisting() {
        mgr.addTranscript("A");
        ConversationManager.Turn t = mgr.addTranscript("B");
        assertTrue(mgr.deleteById(t.id));
        assertEquals(1, mgr.size());
        assertNull(mgr.findById(t.id));
    }

    @Test
    public void deleteById_unknownId_returnsFalse() {
        mgr.addTranscript("A");
        assertFalse(mgr.deleteById(9999));
        assertEquals(1, mgr.size());
    }

    @Test
    public void deleteById_emptyStore() {
        assertFalse(mgr.deleteById(1));
    }

    // ── clear ────────────────────────────

    @Test
    public void clear_resetsAll() {
        mgr.addTranscript("A");
        mgr.clear();
        assertEquals(0, mgr.size());
        assertNull(mgr.newest());
    }

    // ── clear：真正清掉「发给 AI 的上下文」（验证清空上下文有没有用） ──

    @Test
    public void clear_removesEverythingFromAiContext() {
        // 构造一份完整上下文：转录 + 回答 + 取词 + 提问
        ConversationManager.Turn t = mgr.addTranscript("Where is Tom's hometown?");
        mgr.beginAnswerRequest(t.id);
        mgr.completeAnswer(t.id, "Inner Mongolia.");
        mgr.addGrab("hello", "你好");
        mgr.addQuestion("what does that mean?");

        String[][] before = mgr.buildMessages("sys");
        assertTrue("清空前上下文里应有内容，实际 " + before.length, before.length > 1);

        mgr.clear();

        String[][] after = mgr.buildMessages("sys");
        assertEquals("清空后只剩 system 提示词（上下文真的清了）", 1, after.length);
        assertEquals("system", after[0][0]);
        assertEquals(0, mgr.size());
    }

    @Test
    public void clear_thenNewTranscript_contextStartsFresh() {
        mgr.addTranscript("old sentence");
        mgr.clear();
        mgr.addTranscript("new sentence");

        String[][] msgs = mgr.buildMessages("sys");
        assertEquals(2, msgs.length);
        assertEquals("new sentence", msgs[1][1]);
    }

    @Test
    public void clear_makesOldTurnsUnreachable() {
        ConversationManager.Turn t = mgr.addTranscript("x");
        long id = t.id;
        mgr.clear();
        assertNull("清空后旧轮次不能再被找到", mgr.findById(id));
        assertFalse("清空后不能再对旧轮次发起回答请求", mgr.beginAnswerRequest(id));
    }

    @Test
    public void completeAnswer_returnsTrueWhileTurnExists() {
        ConversationManager.Turn t = mgr.addTranscript("x");
        mgr.beginAnswerRequest(t.id);
        assertTrue("轮次还在 → 回答写入成功", mgr.completeAnswer(t.id, "OK"));
    }

    @Test
    public void completeAnswer_afterClear_isRejected() {
        // 清空上下文时若有请求在途，迟到的回答必须被拒（否则会被写进持久化历史里别的记录）
        ConversationManager.Turn t = mgr.addTranscript("x");
        mgr.beginAnswerRequest(t.id);
        mgr.clear();
        assertFalse("清空后迟到的回答必须返回 false", mgr.completeAnswer(t.id, "late"));
        assertEquals("不得把已清空的轮次复活", 0, mgr.size());
    }

    // ── addGrab (P7) ──────────────────────

    @Test
    public void addGrab_turnTypeIsGRAB() {
        ConversationManager.Turn t = mgr.addGrab("hello", "你好");
        assertEquals(ConversationManager.TurnType.GRAB, t.type);
    }

    @Test
    public void addGrab_translatedStored() {
        ConversationManager.Turn t = mgr.addGrab("hello", "你好");
        assertEquals("你好", t.grabTranslated);
    }

    @Test
    public void addGrab_includedInBuildMessages() {
        mgr.addGrab("hello", "你好");
        String[][] msgs = mgr.buildMessages("system");
        // system + 1 user(grab)
        assertEquals(2, msgs.length);
        assertTrue(msgs[1][1].contains("hello"));
        assertTrue(msgs[1][1].contains("你好"));
    }

    @Test
    public void addGrab_canAskFollowUp() {
        ConversationManager.Turn g = mgr.addGrab("hello", "你好");
        ConversationManager.Turn q = mgr.addQuestion("What does this mean?");
        assertTrue(mgr.beginAnswerRequest(q.id));
        mgr.completeAnswer(q.id, "It means hello.");
        // buildMessages should include grab + question + answer
        String[][] msgs = mgr.buildMessages("system");
        // system + grab(user) + question(user) + answer(assistant) = 4
        assertEquals(4, msgs.length);
    }
}
