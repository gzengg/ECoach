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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 悬浮窗面板：拖动、状态显示、音量条、句子列表（原文优先 + 答案按需加载）、控制按钮。
 */
public final class FloatingPanel {

    public interface Callback {
        void onToggleListen();
        void onSegment();
        void onAskAnswer(long segmentId);
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
    private TextView btnToggle, btnSegment, btnReconsent, btnClose;
    private TextView btnFontMinus, btnFontPlus;
    private ProgressBar levelBar;
    private MaxHeightScrollView scrollSegments;
    private android.widget.LinearLayout segmentList;

    /** segmentId → view，用于 updateSegment 定位 */
    private final Map<Long, View> segmentViews = new LinkedHashMap<>();

    public FloatingPanel(Context ctx, Callback cb) {
        this.ctx = ctx.getApplicationContext();
        this.wm = ctx.getSystemService(WindowManager.class);
        this.cb = cb;
        this.prefs = new Prefs(ctx);
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
        scrollSegments = root.findViewById(R.id.scrollAnswers);
        segmentList  = root.findViewById(R.id.answerList);

        View dragBar = root.findViewById(R.id.dragBar);
        dragBar.setOnTouchListener(new DragTouchListener());

        btnToggle.setOnClickListener(v -> cb.onToggleListen());
        btnSegment.setOnClickListener(v -> cb.onSegment());
        btnReconsent.setOnClickListener(v -> cb.onReconsent());
        btnClose.setOnClickListener(v -> cb.onClose());
        btnFontMinus.setOnClickListener(v -> {
            prefs.putFontSp(prefs.fontSp() - 1);
            applyFontSize(prefs.fontSp());
        });
        btnFontPlus.setOnClickListener(v -> {
            prefs.putFontSp(prefs.fontSp() + 1);
            applyFontSize(prefs.fontSp());
        });

        int widthPx = dpToPx(prefs.widthDp());
        wlp = new WindowManager.LayoutParams(
                widthPx, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        wlp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        wlp.x = 20;
        wlp.y = 200;

        applyFontSize(prefs.fontSp());
    }

    // ── 显示 / 隐藏 ────────────────────────

    public void show() { if (root.getParent() == null) wm.addView(root, wlp); }
    public void hide() { if (root.getParent() != null) wm.removeView(root); }
    public boolean isShowing() { return root.getParent() != null; }

    // ── 状态更新 ────────────────────────────

    public void setListening(boolean on) {
        mainHandler.post(() -> {
            btnToggle.setText(ListenToggle.labelFor(on));
            tvDot.setTextColor(on ? 0xFF4CAF50 : 0xFF888888);
            tvStatus.setText(ListenToggle.statusFor(on));
        });
    }

    public void setStatus(String text) { mainHandler.post(() -> tvStatus.setText(text)); }

    public void showMessage(String text) {
        mainHandler.post(() -> {
            tvMessage.setText(text);
            tvMessage.setVisibility(View.VISIBLE);
            mainHandler.postDelayed(() -> tvMessage.setVisibility(View.GONE), 5000);
        });
    }

    public void hideMessage() { mainHandler.post(() -> tvMessage.setVisibility(View.GONE)); }

    public void setReconsentVisible(boolean visible) {
        mainHandler.post(() -> btnReconsent.setVisibility(visible ? View.VISIBLE : View.GONE));
    }

    public void setLevel(int level) {
        mainHandler.post(() -> levelBar.setProgress(Math.max(0, Math.min(100, level))));
    }

    // ── 句子列表 ────────────────────────────

    /**
     * 新增一条句子（只有原文），显示在列表最上。
     */
    public void addSegment(SegmentStore.Segment seg) {
        mainHandler.post(() -> {
            View item = LayoutInflater.from(ctx).inflate(R.layout.item_segment, segmentList, false);
            item.setTag(seg.id);

            TextView tvTranscript = item.findViewById(R.id.tvTranscriptText);
            tvTranscript.setText(seg.transcript);
            tvTranscript.setTextSize(prefs.fontSp());

            TextView tvAnswerText = item.findViewById(R.id.tvAnswerText);
            tvAnswerText.setTextSize(Math.max(11, prefs.fontSp() - 1));

            item.findViewById(R.id.btnAskAnswer).setOnClickListener(v ->
                    cb.onAskAnswer(seg.id));

            applyAnswerVisibility(item, seg);

            segmentList.addView(item, 0);
            segmentViews.put(seg.id, item);
            enforceMax();
            scrollSegments.setVisibility(View.VISIBLE);
            scrollSegments.setMaxHeight(dpToPx(260));
        });
    }

    /**
     * 更新一条已存在的句子（答案状态变化）。
     */
    public void updateSegment(SegmentStore.Segment seg) {
        mainHandler.post(() -> {
            View item = segmentViews.get(seg.id);
            if (item == null) return;
            applyAnswerVisibility(item, seg);
        });
    }

    public void clearSegments() {
        mainHandler.post(() -> {
            segmentList.removeAllViews();
            segmentViews.clear();
            scrollSegments.setVisibility(View.GONE);
        });
    }

    private void applyAnswerVisibility(View item, SegmentStore.Segment seg) {
        TextView tvAnswerLabel = item.findViewById(R.id.tvAnswerLabel);
        TextView tvAnswerText = item.findViewById(R.id.tvAnswerText);
        TextView btnAskAnswer = item.findViewById(R.id.btnAskAnswer);
        TextView tvStatus     = item.findViewById(R.id.tvAnswerStatus);

        switch (seg.state) {
            case NONE:
                tvAnswerLabel.setVisibility(View.GONE);
                tvAnswerText.setVisibility(View.GONE);
                btnAskAnswer.setVisibility(View.VISIBLE);
                tvStatus.setVisibility(View.GONE);
                break;
            case LOADING:
                tvAnswerLabel.setVisibility(View.GONE);
                tvAnswerText.setVisibility(View.GONE);
                btnAskAnswer.setVisibility(View.GONE);
                tvStatus.setText("生成中…");
                tvStatus.setVisibility(View.VISIBLE);
                break;
            case READY:
                tvAnswerLabel.setVisibility(View.VISIBLE);
                tvAnswerText.setVisibility(View.VISIBLE);
                tvAnswerText.setText(seg.answer);
                btnAskAnswer.setVisibility(View.GONE);
                tvStatus.setVisibility(View.GONE);
                break;
            case ERROR:
                tvAnswerLabel.setVisibility(View.GONE);
                tvAnswerText.setVisibility(View.GONE);
                btnAskAnswer.setVisibility(View.VISIBLE);
                tvAnswerText.setText("");
                tvStatus.setText("出错: " + seg.error + "（点击重试）");
                tvStatus.setVisibility(View.VISIBLE);
                break;
        }
    }

    private void enforceMax() {
        int max = 20;
        while (segmentViews.size() > max) {
            Long oldestId = segmentViews.keySet().iterator().next();
            segmentViews.remove(oldestId);
            // segmentList 的 child 顺序与 segmentViews 一致（addView(0) 最新）
            segmentList.removeViewAt(segmentList.getChildCount() - 1);
        }
    }

    // ── 字号 / 宽度 ────────────────────────

    public void applyFontSize(int sp) {
        if (tvStatus == null) return;
        tvStatus.setTextSize(sp);
        btnToggle.setTextSize(sp);
        btnSegment.setTextSize(sp);
        // 已有卡片也要刷新
        for (View v : segmentViews.values()) {
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

    // ── 拖动实现 ────────────────────────────

    private class DragTouchListener implements View.OnTouchListener {
        private int startX, startY, startTouchX, startTouchY;
        private boolean moved;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = wlp.x; startY = wlp.y;
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

    private int dpToPx(int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }
}
