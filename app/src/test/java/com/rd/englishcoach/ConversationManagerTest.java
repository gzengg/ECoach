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

    // ── clear ────────────────────────────

    @Test
    public void clear_resetsAll() {
        mgr.addTranscript("A");
        mgr.clear();
        assertEquals(0, mgr.size());
        assertNull(mgr.newest());
    }
}
