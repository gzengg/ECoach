package com.rd.englishcoach;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 悬浮窗面板：拖动、状态显示、音量条、答案列表、控制按钮。
 * 由 CaptureService 创建和管理。
 */
public final class FloatingPanel {

    public interface Callback {
        void onToggleListen();
        void onSegment();
        void onReconsent();
        void onClose();
    }

    private final Context ctx;
    private final WindowManager wm;
    private final Callback cb;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private View root;
    private WindowManager.LayoutParams wlp;
    private TextView tvDot, tvStatus, tvMessage;
    private TextView btnToggle, btnSegment, btnReconsent, btnClose;
    private TextView btnFontMinus, btnFontPlus;
    private ProgressBar levelBar;
    private MaxHeightScrollView scrollAnswers;
    private android.widget.LinearLayout answerList;

    private boolean listening = false;
    private int maxAnswers = 20;
    private final List<View> answerViews = new ArrayList<>();

    public FloatingPanel(Context ctx, Callback cb) {
        this.ctx = ctx.getApplicationContext();
        this.wm = ctx.getSystemService(WindowManager.class);
        this.cb = cb;
        init();
    }

    private void init() {
        root = LayoutInflater.from(ctx).inflate(R.layout.window_panel, null);

        tvDot        = root.findViewById(R.id.tvDot);
        tvStatus     = root.findViewById(R.id.tvStatus);
        tvMessage    = root.findViewById(R.id.tvMessage);
        btnToggle    = root.findViewById(R.id.btnToggle);
        btnSegment   = root.findViewById(R.id.btnSegment);
        btnReconsent = root.findViewById(R.id.btnReconsent);
        btnClose     = root.findViewById(R.id.btnClose);
        btnFontMinus = root.findViewById(R.id.btnFontMinus);
        btnFontPlus  = root.findViewById(R.id.btnFontPlus);
        levelBar     = root.findViewById(R.id.levelBar);
        scrollAnswers= root.findViewById(R.id.scrollAnswers);
        answerList   = root.findViewById(R.id.answerList);

        // ── 拖动 ──
        View dragBar = root.findViewById(R.id.dragBar);
        dragBar.setOnTouchListener(new DragTouchListener());

        // ── 按钮 ──
        btnToggle.setOnClickListener(v -> cb.onToggleListen());
        btnSegment.setOnClickListener(v -> cb.onSegment());
        btnReconsent.setOnClickListener(v -> cb.onReconsent());
        btnClose.setOnClickListener(v -> cb.onClose());
        btnFontMinus.setOnClickListener(v -> {
            Prefs p = new Prefs(ctx);
            p.putFontSp(p.fontSp() - 1);
            applyFontSize(p.fontSp());
        });
        btnFontPlus.setOnClickListener(v -> {
            Prefs p = new Prefs(ctx);
            p.putFontSp(p.fontSp() + 1);
            applyFontSize(p.fontSp());
        });

        // ── 窗口参数 ──
        Prefs prefs = new Prefs(ctx);
        int widthPx = dpToPx(prefs.widthDp());

        wlp = new WindowManager.LayoutParams(
                widthPx,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        wlp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        wlp.x = 20;
        wlp.y = 200;

        applyFontSize(prefs.fontSp());
    }

    // ── 显示 / 隐藏 ────────────────────────

    public void show() {
        if (root.getParent() == null) {
            wm.addView(root, wlp);
        }
    }

    public void hide() {
        if (root.getParent() != null) {
            wm.removeView(root);
        }
    }

    public boolean isShowing() {
        return root.getParent() != null;
    }

    // ── 状态更新 ────────────────────────────

    public void setListening(boolean on) {
        this.listening = on;
        mainHandler.post(() -> {
            btnToggle.setText(on ? "暂停" : "开始听");
            tvDot.setTextColor(on ? 0xFF4CAF50 : 0xFF888888); // green / gray
            tvStatus.setText(on ? "正在听…" : "已暂停");
        });
    }

    public void setStatus(String text) {
        mainHandler.post(() -> tvStatus.setText(text));
    }

    public void showMessage(String text) {
        mainHandler.post(() -> {
            tvMessage.setText(text);
            tvMessage.setVisibility(View.VISIBLE);
            // 5 秒后自动隐藏
            mainHandler.postDelayed(() -> tvMessage.setVisibility(View.GONE), 5000);
        });
    }

    public void hideMessage() {
        mainHandler.post(() -> tvMessage.setVisibility(View.GONE));
    }

    public void setReconsentVisible(boolean visible) {
        mainHandler.post(() ->
            btnReconsent.setVisibility(visible ? View.VISIBLE : View.GONE));
    }

    /**
     * 更新音量条（0-100）。由采集线程通过 Handler 调用。
     */
    public void setLevel(int level) {
        mainHandler.post(() -> levelBar.setProgress(Math.max(0, Math.min(100, level))));
    }

    // ── 答案列表 ────────────────────────────

    /**
     * 添加一条英文参考回答到列表顶部。
     * 可选地同时显示原文（灰色小字）。
     */
    public void addAnswer(String answer, String transcript, boolean showTranscript) {
        mainHandler.post(() -> {
            View item = LayoutInflater.from(ctx).inflate(R.layout.item_answer, answerList, false);
            TextView tvAnswer = item.findViewById(R.id.tvAnswerText);
            TextView tvTranscript = item.findViewById(R.id.tvTranscriptText);

            tvAnswer.setText(answer);
            if (showTranscript && transcript != null && !transcript.isEmpty()) {
                tvTranscript.setText("原文: " + transcript);
                tvTranscript.setVisibility(View.VISIBLE);
            } else {
                tvTranscript.setVisibility(View.GONE);
            }

            answerList.addView(item, 0); // 最新在最上
            answerViews.add(0, item);

            // 限制条数
            while (answerViews.size() > maxAnswers) {
                View oldest = answerViews.remove(answerViews.size() - 1);
                answerList.removeView(oldest);
            }

            scrollAnswers.setVisibility(View.VISIBLE);
            scrollAnswers.setMaxHeight(dpToPx(260));
        });
    }

    public void clearAnswers() {
        mainHandler.post(() -> {
            answerList.removeAllViews();
            answerViews.clear();
            scrollAnswers.setVisibility(View.GONE);
        });
    }

    // ── 字号 / 宽度 ────────────────────────

    public void applyFontSize(int sp) {
        if (tvStatus == null) return;
        tvStatus.setTextSize(sp);
        btnToggle.setTextSize(sp);
        btnSegment.setTextSize(sp);
    }

    public void applyWidth(int widthDp) {
        if (wlp == null) return;
        wlp.width = dpToPx(widthDp);
        if (root.getParent() != null) {
            wm.updateViewLayout(root, wlp);
        }
    }

    // ── 拖动实现 ────────────────────────────

    private class DragTouchListener implements View.OnTouchListener {
        private int startX, startY;
        private int startTouchX, startTouchY;
        private boolean moved;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = wlp.x;
                    startY = wlp.y;
                    startTouchX = (int) event.getRawX();
                    startTouchY = (int) event.getRawY();
                    moved = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    int dx = (int) event.getRawX() - startTouchX;
                    int dy = (int) event.getRawY() - startTouchY;
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) moved = true;
                    wlp.x = startX + dx;
                    wlp.y = startY + dy;
                    wm.updateViewLayout(root, wlp);
                    return true;

                case MotionEvent.ACTION_UP:
                    return moved;
            }
            return false;
        }
    }

    // ── 工具 ──────────────────────────────

    private int dpToPx(int dp) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        return Math.round(dp * dm.density);
    }
}
