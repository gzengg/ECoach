package com.rd.englishcoach;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * SegmentStore 的回归测试。
 *
 * <p>需求契约：</p>
 * <ul>
 *   <li><b>原文优先</b>：addTranscript 立刻产生一条有原文、无答案的记录；列表最新在最前。</li>
 *   <li><b>参考答案不默认生成</b>：新记录 answer == null、state == NONE。</li>
 *   <li><b>点击才请求</b>：beginAnswerRequest 返回 true 才去发网络请求，防重复点击。</li>
 * </ul>
 */
public class SegmentStoreTest {

    private final SegmentStore store = new SegmentStore(5);

    // ── 原文优先 ────────────────────────────

    @Test
    public void addTranscript_immediatelyHasTranscript() {
        SegmentStore.Segment s = store.addTranscript("How are you?");
        assertNotNull(s.transcript);
        assertEquals("How are you?", s.transcript);
    }

    @Test
    public void addTranscript_trimmed() {
        SegmentStore.Segment s = store.addTranscript("  Hello!  ");
        assertEquals("Hello!", s.transcript);
    }

    @Test
    public void addTranscript_emptyIfNull() {
        assertEquals("", store.addTranscript(null).transcript);
    }

    @Test
    public void addTranscript_newestFirst() {
        store.addTranscript("first");
        store.addTranscript("second");
        assertEquals("second", store.newest().transcript);
        assertEquals(2, store.size());
    }

    @Test
    public void addTranscript_preservesOrder() {
        store.addTranscript("A");
        store.addTranscript("B");
        store.addTranscript("C");
        java.util.List<SegmentStore.Segment> snap = store.snapshot();
        assertEquals("C", snap.get(0).transcript);
        assertEquals("B", snap.get(1).transcript);
        assertEquals("A", snap.get(2).transcript);
    }

    // ── 参考答案不默认生成 ──────────────────────

    @Test
    public void addTranscript_answerIsNull_regression() {
        SegmentStore.Segment s = store.addTranscript("What time is it?");
        assertNull("回归：新记录的参考答案必须为 null（不默认生成）", s.answer);
    }

    @Test
    public void addTranscript_stateIsNone_regression() {
        SegmentStore.Segment s = store.addTranscript("Hi!");
        assertEquals("回归：新记录 state 必须是 NONE（参考答案不默认生成）",
                SegmentStore.AnswerState.NONE, s.state);
    }

    @Test
    public void addTranscript_hasAnswer_isFalse() {
        assertFalse(store.addTranscript("x").hasAnswer());
    }

    // ── 点击才请求 ──────────────────────────

    @Test
    public void beginAnswerRequest_fromNone() {
        SegmentStore.Segment s = store.addTranscript("test");
        assertTrue(store.beginAnswerRequest(s.id));
        assertEquals(SegmentStore.AnswerState.LOADING, s.state);
    }

    @Test
    public void beginAnswerRequest_fromError_allowsRetry() {
        SegmentStore.Segment s = store.addTranscript("test");
        store.beginAnswerRequest(s.id);
        store.failAnswer(s.id, "timeout");
        assertEquals(SegmentStore.AnswerState.ERROR, s.state);
        assertTrue("失败后应允许重试", store.beginAnswerRequest(s.id));
    }

    @Test
    public void beginAnswerRequest_blocksDuringLoading() {
        SegmentStore.Segment s = store.addTranscript("test");
        store.beginAnswerRequest(s.id);
        assertFalse("加载中不应重复请求", store.beginAnswerRequest(s.id));
    }

    @Test
    public void beginAnswerRequest_blocksWhenReady() {
        SegmentStore.Segment s = store.addTranscript("test");
        store.beginAnswerRequest(s.id);
        store.completeAnswer(s.id, "OK");
        assertFalse("已有答案不应重复请求", store.beginAnswerRequest(s.id));
    }

    @Test
    public void beginAnswerRequest_unknownId() {
        assertFalse(store.beginAnswerRequest(9999));
    }

    // ── 完成/失败 ──────────────────────────

    @Test
    public void completeAnswer_transitionsToReady() {
        SegmentStore.Segment s = store.addTranscript("test");
        store.beginAnswerRequest(s.id);
        store.completeAnswer(s.id, "I'm fine, thanks.");
        assertEquals(SegmentStore.AnswerState.READY, s.state);
        assertEquals("I'm fine, thanks.", s.answer);
        assertTrue(s.hasAnswer());
    }

    @Test
    public void failAnswer_transitionsToError() {
        SegmentStore.Segment s = store.addTranscript("test");
        store.beginAnswerRequest(s.id);
        store.failAnswer(s.id, "network error");
        assertEquals(SegmentStore.AnswerState.ERROR, s.state);
        assertEquals("network error", s.error);
        assertNull(s.answer);
        assertFalse(s.hasAnswer());
    }

    @Test
    public void completeAnswer_unknownId_noCrash() {
        store.completeAnswer(9999, "x"); // 不崩溃
    }

    // ── 容量 ──────────────────────────────

    @Test
    public void capacity_dropsOldest() {
        for (int i = 0; i < 7; i++) store.addTranscript("s" + i);
        assertEquals(5, store.size());
        assertEquals("s6", store.newest().transcript);
        assertEquals("s2", store.snapshot().get(4).transcript); // 最旧保留 s2
    }

    // ── clear ─────────────────────────────

    @Test
    public void clear_resetsEverything() {
        store.addTranscript("A");
        store.clear();
        assertEquals(0, store.size());
        assertNull(store.newest());
    }
}
