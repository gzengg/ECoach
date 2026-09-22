package com.rd.englishcoach;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.view.animation.LinearInterpolator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 悬浮窗面板。
 * 暂停 → 上传整段 → 显示原文；「看参考回答」/「询问AI」按需触发。
 * 询问AI 使用内嵌输入框（不用 AlertDialog，避免 Service 上下文闪退）。
 */
public final class FloatingPanel {

    public interface Callback {
        void onTogglePause();
        void onAskAnswer(long turnId);
        void onAskQuestion(long turnId, String question);
        void onDeleteTurn(long turnId);
        void onReconsent();
        void onClose();
        /** 用户点了某个喇叭按钮。field ∈ transcript/answer/grabSrc/grabDst，用于拼 key。 */
        void onSpeak(long turnId, String field, String text, String langHint);
        /** 用户点「取词」按钮。 */
        void onGrab();
    }

    private final Context ctx;
    private final WindowManager wm;
    private final Callback cb;
    private final Prefs prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private View root;
    private WindowManager.LayoutParams wlp;
    private View tvDot;
    private TextView tvStatus, tvMessage;
    private TextView btnPause, btnReconsent, btnClose;
    private TextView btnFontMinus, btnFontPlus;
    private ProgressBar levelBar;
    private MaxHeightScrollView scrollTurns;
    private android.widget.LinearLayout turnList;
    // 内嵌提问输入框
    private View inputRow;
    private EditText etQuestion;
    private TextView btnSendQuestion;
    // v3.0 Tab
    private TextView btnTabListen, btnTabGrab;
    private View tabListening, tabGrab;
    private TextView tvGrabStatus, btnGrabStart;
    private static final int TAB_LISTEN = 0;
    private static final int TAB_GRAB = 1;
    private int currentTab = TAB_LISTEN;

    private final Map<Long, View> turnViews = new LinkedHashMap<>();
    private long pendingQuestionTurnId = -1;
    private final Runnable hideInputRunnable = this::dismissInput;
    private int lastRootHeight = -1;
    private long inputShownAt = 0; // 输入框显示的时间戳，防止误判
    // v2.0 动效：状态点呼吸动画 + 音量条平滑过渡
    private ObjectAnimator dotBreathAnim;
    private ObjectAnimator levelAnim;

    public FloatingPanel(Context ctx, Callback cb) {
        this.ctx = ctx.getApplicationContext();
        this.wm = ctx.getSystemService(WindowManager.class);
        this.cb = cb;
        this.prefs = new Prefs(ctx);
        init();
    }

    private void init() {
        root = LayoutInflater.from(ctx).inflate(R.layout.window_panel, null);

        tvDot          = root.findViewById(R.id.tvDot);
        tvStatus       = root.findViewById(R.id.tvStatus);
        tvMessage      = root.findViewById(R.id.tvMessage);
        btnPause       = root.findViewById(R.id.btnPause);
        btnReconsent   = root.findViewById(R.id.btnReconsent);
        btnClose       = root.findViewById(R.id.btnClose);
        btnFontMinus   = root.findViewById(R.id.btnFontMinus);
        btnFontPlus    = root.findViewById(R.id.btnFontPlus);
        levelBar       = root.findViewById(R.id.levelBar);
        scrollTurns    = root.findViewById(R.id.scrollTurns);
        turnList       = root.findViewById(R.id.turnList);
        inputRow       = root.findViewById(R.id.inputRow);
        etQuestion     = root.findViewById(R.id.etQuestion);
        btnSendQuestion = root.findViewById(R.id.btnSendQuestion);
        btnTabListen    = root.findViewById(R.id.btnTabListen);
        btnTabGrab      = root.findViewById(R.id.btnTabGrab);
        tabListening    = root.findViewById(R.id.tabListening);
        tabGrab         = root.findViewById(R.id.tabGrab);
        tvGrabStatus    = root.findViewById(R.id.tvGrabStatus);
        btnGrabStart    = root.findViewById(R.id.btnGrabStart);

        root.findViewById(R.id.dragBar).setOnTouchListener(new DragTouchListener());

        btnTabListen.setOnClickListener(v -> switchToTab(TAB_LISTEN));
        btnTabGrab.setOnClickListener(v -> switchToTab(TAB_GRAB));

        btnPause.setOnClickListener(v -> cb.onTogglePause());
        btnReconsent.setOnClickListener(v -> cb.onReconsent());
        btnClose.setOnClickListener(v -> cb.onClose());
        btnFontMinus.setOnClickListener(v -> { prefs.putFontSp(prefs.fontSp() - 1); applyFontSize(prefs.fontSp()); });
        btnFontPlus.setOnClickListener(v -> { prefs.putFontSp(prefs.fontSp() + 1); applyFontSize(prefs.fontSp()); });

        // v3.0 取词：按钮接线（缺失则点「取词」无任何反应）
        btnGrabStart.setOnClickListener(v -> cb.onGrab());

        // 内嵌发送按钮
        btnSendQuestion.setOnClickListener(v -> submitQuestion());

        // 键盘收起检测：通过 ViewTreeObserver 监听窗口高度变化
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            int h = root.getHeight();
            long now = System.currentTimeMillis();
            if (lastRootHeight > 0 && h > lastRootHeight
                    && inputRow.getVisibility() == View.VISIBLE
                    && now - inputShownAt > 300) {
                // 根布局变高 = 键盘收起（排除刚显示输入框导致的布局变化）
                mainHandler.postDelayed(hideInputRunnable, 50);
            }
            lastRootHeight = h;
        });

        // 点击输入框以外的区域也收起输入框
        root.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN
                    && inputRow.getVisibility() == View.VISIBLE) {
                // 判断触摸点是否在 inputRow 之外
                int[] inputLoc = new int[2];
                inputRow.getLocationOnScreen(inputLoc);
                float y = event.getRawY();
                if (y < inputLoc[1] || y > inputLoc[1] + inputRow.getHeight()) {
                    mainHandler.postDelayed(hideInputRunnable, 50);
                }
            }
            return false; // 不拦截，让子 View 正常处理
        });

        wlp = new WindowManager.LayoutParams(
                dpToPx(prefs.widthDp()), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        wlp.gravity = Gravity.TOP | Gravity.START;
        wlp.x = 20; wlp.y = 200;

        applyFontSize(prefs.fontSp());
    }

    // ── Tab 切换 ──────────────────────────

    private void switchToTab(int tab) {
        currentTab = tab;
        boolean listen = (tab == TAB_LISTEN);
        tabListening.setVisibility(listen ? View.VISIBLE : View.GONE);
        tabGrab.setVisibility(listen ? View.GONE : View.VISIBLE);
        // 两个主按钮共用同一位置（primaryBar）：只切可见性，保证位置/尺寸完全一致
        btnPause.setVisibility(listen ? View.VISIBLE : View.GONE);
        btnGrabStart.setVisibility(listen ? View.GONE : View.VISIBLE);
        btnTabListen.setTextColor(ctx.getColor(listen ? R.color.accent_solid : R.color.text_secondary));
        btnTabGrab.setTextColor(ctx.getColor(listen ? R.color.text_secondary : R.color.accent_solid));
        // 切到听力页时，如果有输入框打开就收起
        if (listen && inputRow.getVisibility() == View.VISIBLE) {
            dismissInput();
        }
    }

    /** 外部获取当前 Tab（用于 hide/show 恢复）。 */
    public int getCurrentTab() { return currentTab; }

    /** 外部强制切 Tab（用于取词完成后自动切回）。 */
    public void switchToTabExternal(int tab) {
        mainHandler.post(() -> switchToTab(tab));
    }

    // ── 取词页公共方法（P4 使用） ──────────

    public void setGrabStatus(String text) {
        mainHandler.post(() -> {
            tvGrabStatus.setText(text);
            tvGrabStatus.setVisibility(text != null && !text.isEmpty() ? View.VISIBLE : View.GONE);
        });
    }

    private android.widget.LinearLayout grabList;
    private MaxHeightScrollView scrollGrabs;

    /** 添加取词卡片到取词页签。 */
    public void addGrabCard(View item) {
        mainHandler.post(() -> {
            if (grabList == null) grabList = root.findViewById(R.id.grabList);
            if (scrollGrabs == null) scrollGrabs = root.findViewById(R.id.scrollGrabs);
            grabList.addView(item, 0);
            scrollGrabs.setVisibility(View.VISIBLE);
            scrollGrabs.setMaxHeight(dpToPx(260));
        });
    }

    /** 清空取词卡片。 */
    public void clearGrabCards() {
        mainHandler.post(() -> {
            if (grabList != null) grabList.removeAllViews();
            if (scrollGrabs != null) scrollGrabs.setVisibility(View.GONE);
            if (tvGrabStatus != null) tvGrabStatus.setVisibility(View.GONE);
            speakButtons.keySet().removeIf(k -> k.startsWith("grab:"));
        });
    }

    // ── 内嵌提问 ──────────────────────────

    private void submitQuestion() {
        if (pendingQuestionTurnId < 0) return;
        String q = etQuestion.getText().toString().trim();
        if (q.isEmpty()) return;
        long turnId = pendingQuestionTurnId;
        pendingQuestionTurnId = -1;
        etQuestion.setText("");
        mainHandler.removeCallbacks(hideInputRunnable);
        dismissInput();
        cb.onAskQuestion(turnId, q);
    }

    /** 收起内嵌输入框，恢复 FLAG_NOT_FOCUSABLE */
    private void dismissInput() {
        if (inputRow.getVisibility() != View.VISIBLE) return;
        inputRow.setVisibility(View.GONE);
        etQuestion.clearFocus();
        // 恢复 FLAG_NOT_FOCUSABLE 让触摸穿透到下面的 App
        wlp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (root.getParent() != null) wm.updateViewLayout(root, wlp);
    }

    /** 显示内嵌提问输入框 */
    public void showQuestionInput(long turnId) {
        mainHandler.post(() -> {
            pendingQuestionTurnId = turnId;
            etQuestion.setText("");
            inputRow.setVisibility(View.VISIBLE);
            inputShownAt = System.currentTimeMillis(); // 记录显示时间
            etQuestion.requestFocus();
            // 动态去掉 FLAG_NOT_FOCUSABLE 让键盘弹出
            wlp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            wm.updateViewLayout(root, wlp);
        });
    }

    // ── 显示 / 隐藏 ────────────────────────

    public void show() { if (root.getParent() == null) wm.addView(root, wlp); }
    public void hide() {
        // v2.0：取消动画避免泄漏
        if (dotBreathAnim != null) dotBreathAnim.cancel();
        if (levelAnim != null) levelAnim.cancel();
        if (root.getParent() != null) wm.removeView(root);
    }

    /** hide 时保存 Tab 状态，show 时恢复。 */
    public void showRestore() {
        show();
        setCaptureInvisible(false); // 取词结束后确保面板恢复可见/可点
        switchToTab(currentTab); // 恢复到之前的 Tab
    }

    /**
     * 取词截图期间的面板可见性（v3.1）：
     * <b>不 hide 面板</b>，只把窗口设为全透明——用户视觉上「面板没消失」（不会以为 App 退出了），
     * 而截图里也不会带上面板文字（取词前必须先藏自己的文字）。
     * 同时加 FLAG_NOT_TOUCHABLE，避免用户点到看不见的按钮。
     */
    public void setCaptureInvisible(boolean invisible) {
        mainHandler.post(() -> {
            if (root == null || root.getParent() == null) return;
            wlp.alpha = invisible ? 0f : 1f;
            if (invisible) {
                wlp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            } else {
                wlp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            }
            try {
                wm.updateViewLayout(root, wlp);
            } catch (Exception ignored) {
                // 面板可能刚好被移除，忽略
            }
        });
    }

    // ── 状态 ──────────────────────────────

    public void setListening(boolean on) {
        mainHandler.post(() -> {
            btnPause.setText(ListenToggle.labelFor(on));
            tvStatus.setText(ListenToggle.statusFor(on));
            // v2.0：用 drawable 切换状态点，替代旧的 setTextColor
            tvDot.setBackgroundResource(on ? R.drawable.dot_active : R.drawable.dot_idle);
            // 呼吸动画：监听中时脉动，停止时取消
            if (on) {
                if (dotBreathAnim == null) {
                    dotBreathAnim = ObjectAnimator.ofFloat(tvDot, "alpha", 1f, 0.45f);
                    dotBreathAnim.setDuration(1600);
                    dotBreathAnim.setRepeatCount(ValueAnimator.INFINITE);
                    dotBreathAnim.setRepeatMode(ValueAnimator.REVERSE);
                    dotBreathAnim.setInterpolator(new LinearInterpolator());
                }
                dotBreathAnim.start();
            } else {
                if (dotBreathAnim != null) dotBreathAnim.cancel();
                tvDot.setAlpha(1f);
            }
        });
    }

    public void setStatus(String text) { mainHandler.post(() -> tvStatus.setText(text)); }

    public void showMessage(String text) {
        mainHandler.post(() -> {
            tvMessage.setText(text); tvMessage.setVisibility(View.VISIBLE);
            mainHandler.postDelayed(() -> tvMessage.setVisibility(View.GONE), 5000);
        });
    }

    public void setReconsentVisible(boolean v) {
        mainHandler.post(() -> btnReconsent.setVisibility(v ? View.VISIBLE : View.GONE));
    }

    public void setLevel(int level) {
        mainHandler.post(() -> {
            int target = Math.max(0, Math.min(100, level));
            // v2.0：平滑过渡替代硬跳变
            if (levelAnim != null) levelAnim.cancel();
            levelAnim = ObjectAnimator.ofInt(levelBar, "progress", levelBar.getProgress(), target);
            levelAnim.setDuration(120);
            levelAnim.setInterpolator(new LinearInterpolator());
            levelAnim.start();
        });
    }

    // ── 轮次列表 ────────────────────────────

    public void addTurn(ConversationManager.Turn turn) {
        mainHandler.post(() -> {
            View item = LayoutInflater.from(ctx).inflate(R.layout.item_segment, turnList, false);
            item.setTag(turn.id);

            TextView tvTranscript = item.findViewById(R.id.tvTranscriptText);
            tvTranscript.setText(turn.content);
            tvTranscript.setTextSize(prefs.fontSp());

            // 长按转录文字 → 弹出删除/复制菜单
            tvTranscript.setOnLongClickListener(v -> {
                showTurnContextMenu(turn.id, turn.content, tvTranscript);
                return true;
            });

            // 「看参考回答」按钮
            item.findViewById(R.id.btnAskAnswer).setOnClickListener(v -> cb.onAskAnswer(turn.id));

            // 「询问AI」按钮 → 展开内嵌输入框
            item.findViewById(R.id.btnAskQuestion).setOnClickListener(v -> showQuestionInput(turn.id));

            // 朗读按钮（key 参与喇叭状态反馈）
            String transcriptKey = speakKey(turn.id, "transcript");
            String answerKey = speakKey(turn.id, "answer");
            TextView btnSpeakTranscript = item.findViewById(R.id.btnSpeakTranscript);
            TextView btnSpeakAnswer = item.findViewById(R.id.btnSpeakAnswer);
            btnSpeakTranscript.setOnClickListener(
                    v -> cb.onSpeak(turn.id, "transcript", turn.content,
                            Translator.speakLang(turn.content)));
            btnSpeakAnswer.setOnClickListener(
                    v -> cb.onSpeak(turn.id, "answer", turn.aiAnswer,
                            Translator.speakLang(turn.aiAnswer)));
            registerSpeakButton(transcriptKey, btnSpeakTranscript);
            registerSpeakButton(answerKey, btnSpeakAnswer);

            applyTurnVisibility(item, turn);

            turnList.addView(item, 0);
            // v2.0：卡片入场动画（180ms alpha + translationY）
            item.setAlpha(0f);
            item.setTranslationY(dpToPx(8));
            item.animate().alpha(1f).translationY(0)
                    .setDuration(180)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            turnViews.put(turn.id, item);
            while (turnViews.size() > 20) {
                Long oldest = turnViews.keySet().iterator().next();
                turnViews.remove(oldest);
                unregisterSpeakButtons(oldest);
                turnList.removeViewAt(turnList.getChildCount() - 1);
            }
            scrollTurns.setVisibility(View.VISIBLE);
            scrollTurns.setMaxHeight(dpToPx(260));
        });
    }

    // 喇叭按钮状态反馈：key → 按钮，随轮次/卡片生命周期清理
    private final Map<String, TextView> speakButtons = new LinkedHashMap<>();

    /** 拼喇叭状态 key（FloatingPanel 与 CaptureService 共用同一套 key）。 */
    public static String speakKey(long turnId, String field) {
        return turnId + ":" + field;
    }

    /** 注册喇叭按钮，后续 {@link #setSpeakState} 按 key 改色。 */
    public void registerSpeakButton(String key, TextView btn) {
        mainHandler.post(() -> speakButtons.put(key, btn));
    }

    /**
     * 喇叭状态反馈：
     * IDLE→accent_solid，LOADING→warn，PLAYING→success，ERROR→danger。
     * 只改色不动画（§10.4：状态切换只做进入动画，这里纯色切换最稳）。
     */
    public void setSpeakState(String key, SpeechPlayer.State state) {
        mainHandler.post(() -> {
            TextView btn = speakButtons.get(key);
            if (btn == null) return;
            int color;
            switch (state) {
                case LOADING:  color = ctx.getColor(R.color.warn); break;
                case PLAYING:  color = ctx.getColor(R.color.success); break;
                case ERROR:    color = ctx.getColor(R.color.danger); break;
                default:       color = ctx.getColor(R.color.accent_solid); break;
            }
            btn.setTextColor(color);
        });
    }

    /** 移除某轮次注册的喇叭 key（含 transcript/answer）。 */
    private void unregisterSpeakButtons(long turnId) {
        String prefix = turnId + ":";
        speakButtons.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public void updateTurn(ConversationManager.Turn turn) {
        mainHandler.post(() -> {
            View item = turnViews.get(turn.id);
            if (item != null) applyTurnVisibility(item, turn);
        });
    }

    public void clearTurns() {
        mainHandler.post(() -> {
            turnList.removeAllViews();
            for (Long id : turnViews.keySet()) unregisterSpeakButtons(id);
            turnViews.clear();
            scrollTurns.setVisibility(View.GONE);
        });
    }

    /** 从面板移除某一轮次 */
    public void removeTurn(long turnId) {
        mainHandler.post(() -> {
            View item = turnViews.remove(turnId);
            unregisterSpeakButtons(turnId);
            if (item != null) turnList.removeView(item);
            if (turnViews.isEmpty()) scrollTurns.setVisibility(View.GONE);
        });
    }

    // ── 长按菜单 ──────────────────────────

    private void showTurnContextMenu(long turnId, String text, TextView anchor) {
        android.widget.PopupMenu popup = new android.widget.PopupMenu(ctx, anchor);
        popup.getMenu().add(0, 1, 0, ctx.getString(R.string.menu_copy));
        popup.getMenu().add(0, 2, 1, ctx.getString(R.string.menu_delete));
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    android.content.ClipData clip = android.content.ClipData.newPlainText("transcript", text);
                    cm.setPrimaryClip(clip);
                    showMessage(ctx.getString(R.string.msg_copied));
                }
                return true;
            } else if (item.getItemId() == 2) {
                removeTurn(turnId);
                cb.onDeleteTurn(turnId);
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void applyTurnVisibility(View item, ConversationManager.Turn turn) {
        TextView tvAnswerLabel = item.findViewById(R.id.tvAnswerLabel);
        TextView tvAnswerText  = item.findViewById(R.id.tvAnswerText);
        View btnAnswer         = item.findViewById(R.id.btnAskAnswer);
        View btnAsk            = item.findViewById(R.id.btnAskQuestion);
        TextView tvAnswerStatus = item.findViewById(R.id.tvAnswerStatus);

        switch (turn.state) {
            case NONE:
                tvAnswerLabel.setVisibility(View.GONE);
                tvAnswerText.setVisibility(View.GONE);
                btnAnswer.setVisibility(View.VISIBLE);
                btnAsk.setVisibility(View.VISIBLE);
                tvAnswerStatus.setVisibility(View.GONE);
                item.findViewById(R.id.btnSpeakAnswer).setVisibility(View.GONE);
                break;
            case LOADING:
                tvAnswerLabel.setVisibility(View.GONE);
                tvAnswerText.setVisibility(View.GONE);
                btnAnswer.setVisibility(View.GONE);
                btnAsk.setVisibility(View.GONE);
                tvAnswerStatus.setText(R.string.msg_ai_thinking);
                tvAnswerStatus.setVisibility(View.VISIBLE);
                item.findViewById(R.id.btnSpeakAnswer).setVisibility(View.GONE);
                break;
            case READY:
                tvAnswerLabel.setVisibility(View.VISIBLE);
                tvAnswerText.setVisibility(View.VISIBLE);
                tvAnswerText.setText(turn.aiAnswer);
                btnAnswer.setVisibility(View.GONE);
                btnAsk.setVisibility(View.VISIBLE); // 仍可追问
                tvAnswerStatus.setVisibility(View.GONE);
                item.findViewById(R.id.btnSpeakAnswer).setVisibility(View.VISIBLE);
                break;
            case ERROR:
                tvAnswerLabel.setVisibility(View.GONE);
                tvAnswerText.setVisibility(View.GONE);
                btnAnswer.setVisibility(View.VISIBLE);
                btnAsk.setVisibility(View.VISIBLE);
                tvAnswerStatus.setText(ctx.getString(R.string.msg_error_retry, turn.error));
                tvAnswerStatus.setVisibility(View.VISIBLE);
                item.findViewById(R.id.btnSpeakAnswer).setVisibility(View.GONE);
                break;
        }
    }

    // ── 字号 ──────────────────────────────

    public void applyFontSize(int sp) {
        if (tvStatus == null) return;
        tvStatus.setTextSize(sp);
        // 两个 Tab 的主按钮（继续 / 取词）字号保持一致，风格才统一；
        // 上限 16sp，避免大字号把 44dp 胶囊擑破
        int ctaSp = Math.min(sp, 16);
        btnPause.setTextSize(ctaSp);
        if (btnGrabStart != null) btnGrabStart.setTextSize(ctaSp);
        for (View v : turnViews.values()) {
            TextView t = v.findViewById(R.id.tvTranscriptText);
            if (t != null) t.setTextSize(sp);
            TextView a = v.findViewById(R.id.tvAnswerText);
            if (a != null) a.setTextSize(Math.max(11, sp - 1));
        }
    }

    public void applyWidth(int widthDp) {
        if (wlp == null) return;
        wlp.width = dpToPx(widthDp);
        if (root.getParent() != null) wm.updateViewLayout(root, wlp);
    }

    // ── 拖动 ──────────────────────────────

    private class DragTouchListener implements View.OnTouchListener {
        private int sx, sy, stx, sty; private boolean moved;
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    sx = wlp.x; sy = wlp.y; stx = (int) e.getRawX(); sty = (int) e.getRawY(); moved = false; return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) e.getRawX() - stx, dy = (int) e.getRawY() - sty;
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) moved = true;
                    wlp.x = sx + dx; wlp.y = sy + dy; wm.updateViewLayout(root, wlp); return true;
                case MotionEvent.ACTION_UP: return moved;
            }
            return false;
        }
    }

    private int dpToPx(int dp) { return Math.round(dp * ctx.getResources().getDisplayMetrics().density); }
}
