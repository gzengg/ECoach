package com.rd.englishcoach;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 句子记录的<b>唯一数据源</b>：每条记录 = 一句英文原文（+ 可选参考答案）。
 *
 * <p>需求契约：</p>
 * <ul>
 *   <li><b>原文优先</b>：{@link #addTranscript} 一被调用（即 ASR 刚返回）就必须能拿到完整原文，
 *       不依赖任何 AI 调用；列表按"最新在最前"排列。</li>
 *   <li><b>参考答案不默认生成</b>：新记录加入时 {@code answer == null}、
 *       {@code state == NONE}，必须由用户显式触发才进入 {@code LOADING}。</li>
 * </ul>
 *
 * <p>纯 Java，无 Android 依赖，可直接 JVM 单测。</p>
 */
public final class SegmentStore {

    /** 参考答案的生命周期。 */
    public enum AnswerState {
        /** 还没请求过（默认状态：参考答案不默认生成）。 */
        NONE,
        /** 正在请求。 */
        LOADING,
        /** 已拿到答案。 */
        READY,
        /** 请求失败，可重试。 */
        ERROR
    }

    /** 一条句子记录。 */
    public static final class Segment {
        public final long id;
        /** 英文原文（ASR 结果），永远非空。 */
        public final String transcript;
        /** 参考答案，未请求成功前为 null。 */
        public String answer;
        public AnswerState state = AnswerState.NONE;
        /** 失败原因，仅 ERROR 时有值。 */
        public String error;

        Segment(long id, String transcript) {
            this.id = id;
            this.transcript = transcript;
        }

        public boolean hasAnswer() {
            return state == AnswerState.READY && answer != null && !answer.isEmpty();
        }
    }

    private final int maxSegments;
    private final List<Segment> segments = new ArrayList<>();
    private long nextId = 1;

    public SegmentStore(int maxSegments) {
        if (maxSegments <= 0) throw new IllegalArgumentException("maxSegments must be > 0");
        this.maxSegments = maxSegments;
    }

    /**
     * ASR 成功后立刻调用：产生一条"只有原文"的记录。
     *
     * @return 新记录（{@code state == NONE}，{@code answer == null}）
     */
    public synchronized Segment addTranscript(String transcript) {
        String t = transcript == null ? "" : transcript.trim();
        Segment s = new Segment(nextId++, t);
        segments.add(0, s); // 最新在最前
        while (segments.size() > maxSegments) {
            segments.remove(segments.size() - 1);
        }
        return s;
    }

    /**
     * 用户点了「看参考回答」：尝试开始一次请求。
     *
     * <p>只有在 {@code NONE}（没请求过）或 {@code ERROR}（失败可重试）时才允许发起，
     * 防止重复点击打出多个并发请求。</p>
     *
     * @return true 表示调用方应该真的去发网络请求
     */
    public synchronized boolean beginAnswerRequest(long id) {
        Segment s = findById(id);
        if (s == null) return false;
        if (s.state == AnswerState.LOADING || s.state == AnswerState.READY) return false;
        s.state = AnswerState.LOADING;
        s.error = null;
        return true;
    }

    /** 请求成功。 */
    public synchronized void completeAnswer(long id, String answer) {
        Segment s = findById(id);
        if (s == null) return;
        s.answer = answer == null ? "" : answer.trim();
        s.state = AnswerState.READY;
        s.error = null;
    }

    /** 请求失败（可再次点击重试）。 */
    public synchronized void failAnswer(long id, String error) {
        Segment s = findById(id);
        if (s == null) return;
        s.state = AnswerState.ERROR;
        s.error = error == null ? "未知错误" : error;
        s.answer = null;
    }

    public synchronized Segment findById(long id) {
        for (Segment s : segments) {
            if (s.id == id) return s;
        }
        return null;
    }

    /** 按"最新在最前"返回快照。 */
    public synchronized List<Segment> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(segments));
    }

    /** 最新一条（没有则为 null）。 */
    public synchronized Segment newest() {
        return segments.isEmpty() ? null : segments.get(0);
    }

    public synchronized int size() {
        return segments.size();
    }

    public int maxSegments() {
        return maxSegments;
    }

    public synchronized void clear() {
        segments.clear();
    }
}
