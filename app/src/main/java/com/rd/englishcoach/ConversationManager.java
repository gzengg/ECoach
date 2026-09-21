package com.rd.englishcoach;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 对话管理器：管理「转录条目 + AI 回答 + 自定义问答」，同时维护发给 AI 的完整消息历史。
 *
 * <p>核心流程（广东听说考试）：</p>
 * <ol>
 *   <li>学生录到标准录音 → pause → ASR → {@link #addTranscript}（只存原文，不自动调 AI）</li>
 *   <li>学生点「看参考回答」→ {@link #beginAnswerRequest} → AI 基于完整上下文生成回答</li>
 *   <li>学生点「询问AI」→ {@link #addQuestion} + {@link #beginAnswerRequest} → AI 回答自定义问题</li>
 *   <li>学生点「开启新对话」→ {@link #clear} → 清空所有历史</li>
 * </ol>
 *
 * <p>纯 Java，无 Android 依赖。</p>
 */
public final class ConversationManager {

    /** 条目类型 */
    public enum TurnType { TRANSCRIPT, QUESTION, GRAB }

    /** AI 回答的生命周期 */
    public enum AnswerState { NONE, LOADING, READY, ERROR }

    /** 一个对话轮次（一段录音转录 / 一个自定义问题 / 取词结果） */
    public static final class Turn {
        public final long id;
        public final TurnType type;
        public final String content;        // ASR 转录文本 / 用户提问 / 取词原文
        public String aiAnswer;             // AI 回答（null = 还没生成）
        public String grabTranslated;       // 取词译文（仅 GRAB 类型）
        public AnswerState state = AnswerState.NONE;
        public String error;
        public final List<QAPair> qaPairs = new ArrayList<>(); // 该条目下的自定义问答

        Turn(long id, TurnType type, String content) {
            this.id = id;
            this.type = type;
            this.content = content;
        }

        public boolean hasAnswer() {
            return state == AnswerState.READY && aiAnswer != null && !aiAnswer.isEmpty();
        }
    }

    public static final class QAPair {
        public String question;
        public String answer;
        public AnswerState state = AnswerState.NONE;
        public String error;

        QAPair(String question) { this.question = question; }
    }

    private final int maxTurns;
    private final List<Turn> turns = new ArrayList<>();  // index 0 = 最新
    private long nextId = 1;

    public ConversationManager(int maxTurns) {
        this.maxTurns = maxTurns;
    }

    // ── 转录条目 ────────────────────────────

    /** 暂停后调用：存入转录文本（不自动调 AI）。 */
    public synchronized Turn addTranscript(String transcript) {
        String t = transcript == null ? "" : transcript.trim();
        Turn turn = new Turn(nextId++, TurnType.TRANSCRIPT, t);
        turns.add(0, turn);
        while (turns.size() > maxTurns) turns.remove(turns.size() - 1);
        return turn;
    }

    /** 自定义提问（「询问AI」按钮）。 */
    public synchronized Turn addQuestion(String question) {
        String q = question == null ? "" : question.trim();
        Turn turn = new Turn(nextId++, TurnType.QUESTION, q);
        turns.add(0, turn);
        while (turns.size() > maxTurns) turns.remove(turns.size() - 1);
        return turn;
    }

    /** 取词结果（P7）。 */
    public synchronized Turn addGrab(String source, String translated) {
        String s = source == null ? "" : source.trim();
        Turn turn = new Turn(nextId++, TurnType.GRAB, s);
        turn.grabTranslated = translated;
        turns.add(0, turn);
        while (turns.size() > maxTurns) turns.remove(turns.size() - 1);
        return turn;
    }

    // ── AI 回答生命周期 ──────────────────────

    /**
     * 尝试开始一次 AI 回答请求。
     * 只有 NONE 或 ERROR 状态才允许发起（防重复点击）。
     * @return true = 调用方应真的去发网络请求
     */
    public synchronized boolean beginAnswerRequest(long turnId) {
        Turn t = findById(turnId);
        if (t == null) return false;
        if (t.state == AnswerState.LOADING || t.state == AnswerState.READY) return false;
        t.state = AnswerState.LOADING;
        t.error = null;
        return true;
    }

    public synchronized void completeAnswer(long turnId, String answer) {
        Turn t = findById(turnId);
        if (t == null) return;
        t.aiAnswer = answer == null ? "" : answer.trim();
        t.state = AnswerState.READY;
        t.error = null;
    }

    public synchronized void failAnswer(long turnId, String error) {
        Turn t = findById(turnId);
        if (t == null) return;
        t.state = AnswerState.ERROR;
        t.error = error == null ? "未知错误" : error;
        t.aiAnswer = null;
    }

    // ── 自定义问答 ──────────────────────────

    public synchronized QAPair addQAPair(long turnId, String question) {
        Turn t = findById(turnId);
        if (t == null) return null;
        QAPair qa = new QAPair(question);
        t.qaPairs.add(qa);
        return qa;
    }

    public synchronized void completeQA(QAPair qa, String answer) {
        qa.answer = answer;
        qa.state = AnswerState.READY;
    }

    public synchronized void failQA(QAPair qa, String error) {
        qa.state = AnswerState.ERROR;
        qa.error = error;
    }

    // ── 查询 ──────────────────────────────

    public synchronized Turn findById(long id) {
        for (Turn t : turns) if (t.id == id) return t;
        return null;
    }

    /** 删除指定轮次（长按删除用） */
    public synchronized boolean deleteById(long id) {
        return turns.removeIf(t -> t.id == id);
    }

    public synchronized List<Turn> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(turns));
    }

    public synchronized Turn newest() {
        return turns.isEmpty() ? null : turns.get(0);
    }

    public synchronized int size() { return turns.size(); }

    public synchronized void clear() {
        turns.clear();
    }

    // ── AI 消息历史构建 ──────────────────────

    /**
     * 构建发给 AI 的 messages 数组（从最新往最旧，再加系统提示词）。
     * 注意：API 要求从旧到新排列，所以这里反转。
     */
    public synchronized String[][] buildMessages(String systemPrompt) {
        List<Turn> ordered = new ArrayList<>(turns);
        Collections.reverse(ordered);

        // 先计算总消息数
        int total = 1; // system prompt
        for (Turn t : ordered) {
            total += 1; // user message
            if (t.hasAnswer()) total += 1; // assistant reply
        }

        String[][] msgs = new String[total][2];
        msgs[0] = new String[]{"system", systemPrompt};
        int i = 1;
        for (Turn t : ordered) {
            // GRAB 类型：原文 + 译文作为上下文
            if (t.type == TurnType.GRAB && t.grabTranslated != null) {
                msgs[i++] = new String[]{"user",
                        "[屏幕取词] 原文: " + t.content + "\n译文: " + t.grabTranslated};
            } else {
                msgs[i++] = new String[]{"user", t.content};
            }
            if (t.hasAnswer()) {
                msgs[i++] = new String[]{"assistant", t.aiAnswer};
            }
        }
        return msgs;
    }
}
