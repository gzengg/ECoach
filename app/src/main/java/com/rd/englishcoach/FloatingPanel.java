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
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

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
        void onReconsent();
        void onClose();
    }

    private final Context ctx;
    private final WindowManager wm;
    private final Callback cb;
    private final Prefs prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private View root;
    private WindowManager.LayoutParams wlp;
    private TextView tvDot, tvStatus, tvMessage;
    private TextView btnPause, btnReconsent, btnClose;
    private TextView btnFontMinus, btnFontPlus;
    private ProgressBar levelBar;
    private MaxHeightScrollView scrollTurns;
    private android.widget.LinearLayout turnList;
    // 内嵌提问输入框
    private View inputRow;
    private EditText etQuestion;
    private TextView btnSendQuestion;

    private final Map<Long, View> turnViews = new LinkedHashMap<>();
    private long pendingQuestionTurnId = -1;

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

        root.findViewById(R.id.dragBar).setOnTouchListener(new DragTouchListener());

        btnPause.setOnClickListener(v -> cb.onTogglePause());
        btnReconsent.setOnClickListener(v -> cb.onReconsent());
        btnClose.setOnClickListener(v -> cb.onClose());
        btnFontMinus.setOnClickListener(v -> { prefs.putFontSp(prefs.fontSp() - 1); applyFontSize(prefs.fontSp()); });
        btnFontPlus.setOnClickListener(v -> { prefs.putFontSp(prefs.fontSp() + 1); applyFontSize(prefs.fontSp()); });

        // 内嵌发送按钮
        btnSendQuestion.setOnClickListener(v -> submitQuestion());

        wlp = new WindowManager.LayoutParams(
                dpToPx(prefs.widthDp()), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        wlp.gravity = Gravity.TOP | Gravity.START;
        wlp.x = 20; wlp.y = 200;

        applyFontSize(prefs.fontSp());
    }

    // ── 内嵌提问 ──────────────────────────

    private void submitQuestion() {
        if (pendingQuestionTurnId < 0) return;
        String q = etQuestion.getText().toString().trim();
        if (q.isEmpty()) return;
        long turnId = pendingQuestionTurnId;
        pendingQuestionTurnId = -1;
        etQuestion.setText("");
        inputRow.setVisibility(View.GONE);
        // 需要先隐藏输入行，再让外部处理焦点问题
        cb.onAskQuestion(turnId, q);
    }

    /** 显示内嵌提问输入框 */
    public void showQuestionInput(long turnId) {
        mainHandler.post(() -> {
            pendingQuestionTurnId = turnId;
            etQuestion.setText("");
            inputRow.setVisibility(View.VISIBLE);
            etQuestion.requestFocus();
        });
    }

    // ── 显示 / 隐藏 ────────────────────────

    public void show() { if (root.getParent() == null) wm.addView(root, wlp); }
    public void hide() { if (root.getParent() != null) wm.removeView(root); }

    // ── 状态 ──────────────────────────────

    public void setListening(boolean on) {
        mainHandler.post(() -> {
            btnPause.setText(on ? "⏸ 暂停" : "▶ 继续");
            tvDot.setTextColor(on ? 0xFF4CAF50 : 0xFF888888);
            tvStatus.setText(ListenToggle.statusFor(on));
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
        mainHandler.post(() -> levelBar.setProgress(Math.max(0, Math.min(100, level))));
    }

    // ── 轮次列表 ────────────────────────────

    public void addTurn(ConversationManager.Turn turn) {
        mainHandler.post(() -> {
            View item = LayoutInflater.from(ctx).inflate(R.layout.item_segment, turnList, false);
            item.setTag(turn.id);

            TextView tvTranscript = item.findViewById(R.id.tvTranscriptText);
            tvTranscript.setText(turn.content);
            tvTranscript.setTextSize(prefs.fontSp());

            // 「看参考回答」按钮
            item.findViewById(R.id.btnAskAnswer).setOnClickListener(v -> cb.onAskAnswer(turn.id));

            // 「询问AI」按钮 → 展开内嵌输入框
            item.findViewById(R.id.btnAskQuestion).setOnClickListener(v -> showQuestionInput(turn.id));

            applyTurnVisibility(item, turn);

            turnList.addView(item, 0);
            turnViews.put(turn.id, item);
            while (turnViews.size() > 20) {
                Long oldest = turnViews.keySet().iterator().next();
                turnViews.remove(oldest);
                turnList.removeViewAt(turnList.getChildCount() - 1);
            }
            scrollTurns.setVisibility(View.VISIBLE);
            scrollTurns.setMaxHeight(dpToPx(260));
        });
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
            turnViews.clear();
            scrollTurns.setVisibility(View.GONE);
        });
    }

    private void applyTurnVisibility(View item, ConversationManager.Turn turn) {
        TextView tvAnswerLabel = item.findViewById(R.id.tvAnswerLabel);
        TextView tvAnswerText  = item.findViewById(R.id.tvAnswerText);
        View btnAnswer         = item.findViewById(R.id.btnAskAnswer);
        View btnAsk            = item.findViewById(R.id.btnAskQuestion);
        TextView tvStatus      = item.findViewById(R.id.tvAnswerStatus);

        switch (turn.state) {
            case NONE:
                tvAnswerLabel.setVisibility(View.GONE);
                tvAnswerText.setVisibility(View.GONE);
                btnAnswer.setVisibility(View.VISIBLE);
                btnAsk.setVisibility(View.VISIBLE);
                tvStatus.setVisibility(View.GONE);
                break;
            case LOADING:
                tvAnswerLabel.setVisibility(View.GONE);
                tvAnswerText.setVisibility(View.GONE);
                btnAnswer.setVisibility(View.GONE);
                btnAsk.setVisibility(View.GONE);
                tvStatus.setText("AI 思考中…");
                tvStatus.setVisibility(View.VISIBLE);
                break;
            case READY:
                tvAnswerLabel.setVisibility(View.VISIBLE);
                tvAnswerText.setVisibility(View.VISIBLE);
                tvAnswerText.setText(turn.aiAnswer);
                btnAnswer.setVisibility(View.GONE);
                btnAsk.setVisibility(View.VISIBLE); // 仍可追问
                tvStatus.setVisibility(View.GONE);
                break;
            case ERROR:
                tvAnswerLabel.setVisibility(View.GONE);
                tvAnswerText.setVisibility(View.GONE);
                btnAnswer.setVisibility(View.VISIBLE);
                btnAsk.setVisibility(View.VISIBLE);
                tvStatus.setText("出错: " + turn.error + "（点击重试）");
                tvStatus.setVisibility(View.VISIBLE);
                break;
        }
    }

    // ── 字号 ──────────────────────────────

    public void applyFontSize(int sp) {
        if (tvStatus == null) return;
        tvStatus.setTextSize(sp);
        btnPause.setTextSize(sp);
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
