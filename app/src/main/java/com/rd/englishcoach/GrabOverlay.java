package com.rd.englishcoach;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * 取词框选层 <b>v3.1（重做）</b>：全屏覆盖定格截图 + 拖动选择矩形区域。
 *
 * <p>v3.0 的真机问题（本次全部修掉）：</p>
 * <ol>
 *   <li><b>框选范围不对应屏幕</b>：截图按原始像素画在 (0,0)，视图与位图尺寸不一致
 *       时选择框与屏幕内容错位。现在截图<b>等比缩放画满整个视图</b>，选择框按同一
 *       映射换算回位图坐标（{@link #mapToBitmap}）；</li>
 *   <li><b>点空白处会退出</b>：旧逻辑「点击框外=取消」极易误触。现在取消只通过右上角
 *       「✕ 取消」按钮；单击空白只是清掉当前框，不退出；</li>
 *   <li><b>概率闪退</b>：旧 {@code dismiss()} 里 recycle 截图，而窗口刚 remove 时
 *       RenderThread 可能还持有该位图引用 →
 *       {@code Canvas: trying to use a recycled bitmap} 闪退（连带投屏会话被杀，
 *       要重新授权）。现在不 recycle 显示位图，交给 GC；</li>
 *   <li><b>框太小无反馈</b>：旧逻辑静默清空选择框。现在给出提示文案让用户重选。</li>
 * </ol>
 */
public final class GrabOverlay {

    private static final String TAG = "GrabOverlay";

    /** 返回手势每侧边缘的排除高度上限（官方限制 200dp）。 */
    private static final int MAX_GESTURE_EXCLUSION_DP = 200;

    public interface Callback {
        /** 框选完成：region 已从展示帧裁好（回调方负责回收）。 */
        void onRegionSelected(Bitmap region);
        void onCancelled();
    }

    private final Context ctx;
    private final WindowManager wm;
    private final Callback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private View overlayView;
    private Bitmap screenshot;
    private float startX, startY;
    /** 选择框（视图坐标）。 */
    private RectF selectionRect;
    private RectF cancelRect;
    private boolean dragging;
    private boolean movedFar;
    private String hintText;
    private final int minSizePx;
    private final int tapSlopPx;
    /** 贴边吸附阈值：拖到距屏幕左右边缘这么近就吸附到边，避免内容被切掉。 */
    private final int edgeSnapPx;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint maskPaint = new Paint();
    private final Paint chipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public GrabOverlay(Context ctx, Callback callback) {
        this.ctx = ctx;
        this.wm = ctx.getSystemService(WindowManager.class);
        this.callback = callback;
        float density = ctx.getResources().getDisplayMetrics().density;
        this.minSizePx = Math.round(32 * density);
        this.tapSlopPx = Math.round(10 * density);
        this.edgeSnapPx = Math.round(32 * density);

        maskPaint.setColor(Color.argb(110, 0, 0, 0));
        chipPaint.setColor(Color.argb(210, 18, 20, 30));
        chipTextPaint.setColor(Color.WHITE);
        chipTextPaint.setTextSize(Math.round(13 * density));
        chipTextPaint.setTextAlign(Paint.Align.CENTER);
        borderPaint.setColor(Color.WHITE);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(3f);
        hintPaint.setColor(Color.WHITE);
        hintPaint.setTextSize(Math.round(12 * density));
        hintPaint.setTextAlign(Paint.Align.CENTER);
        hintPaint.setShadowLayer(4f, 0, 1, Color.argb(200, 0, 0, 0));
    }

    /** 显示框选层，背景为定格截图（等比缩放铺满全屏）。 */
    public void show(Bitmap frame) {
        if (frame == null) {
            callback.onCancelled();
            return;
        }
        dismiss(); // 防重入：上一张还没撤时先撤掉
        this.screenshot = frame;
        this.selectionRect = null;
        this.dragging = false;
        this.movedFar = false;
        this.hintText = "拖动框选要翻译的文字，点右上角「✕ 取消」退出";

        overlayView = new View(ctx) {
            @Override
            protected void onDraw(Canvas canvas) {
                drawOverlay(canvas, getWidth(), getHeight());
            }

            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                super.onLayout(changed, l, t, r, b);
                // 手势排除区必须在 onLayout/onDraw 里声明（官方要求）
                applyGestureExclusion(getWidth(), getHeight());
            }

            @Override
            public boolean onTouchEvent(MotionEvent event) {
                return onTouch(event);
            }
        };

        WindowManager.LayoutParams wlp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        wlp.gravity = Gravity.TOP | Gravity.START;
        wlp.x = 0;
        wlp.y = 0;
        wm.addView(overlayView, wlp);
    }

    // ── 绘制 ─────────────────────────────────

    /**
     * 声明系统手势排除区：否则从左右边缘起拖会被系统「返回」手势抢走，
     * 框选范围被截断（用户反馈）。
     *
     * <p>官方限制：返回手势每侧边缘最多只能排除 <b>200dp 垂直高度</b>，
     * 所以这里给一条「全宽 × 200dp」的顶部条带（正文通常在这一带），
     * 其余位置靠 {@link #snapToEdges} 的贴边吸附保证能选到屏幕边缘。</p>
     */
    private void applyGestureExclusion(int vw, int vh) {
        if (overlayView == null || vw <= 0 || vh <= 0) return;
        try {
            int bandH = Math.min(dp(MAX_GESTURE_EXCLUSION_DP), vh);
            java.util.List<Rect> rects = new java.util.ArrayList<>(1);
            rects.add(new Rect(0, 0, vw, bandH));
            overlayView.setSystemGestureExclusionRects(rects);
        } catch (Exception e) {
            Log.w(TAG, "setSystemGestureExclusionRects failed: " + e.getMessage());
        }
    }

    private void drawOverlay(Canvas canvas, int vw, int vh) {
        if (screenshot == null || vw <= 0 || vh <= 0) return;

        // 1. 截图等比缩放画满整个视图 → 画面与真实屏幕严格对应
        RectF dst = new RectF(0, 0, vw, vh);
        canvas.drawBitmap(screenshot, null, dst, bitmapPaint);

        // 2. 全屏半透明遮罩
        canvas.drawRect(dst, maskPaint);

        // 3. 选中区：重画未变暗的原始内容（等效「打洞」，但不依赖 PorterDuff.CLEAR，
        //    硬件加速下更稳），再描白框
        if (selectionRect != null && !selectionRect.isEmpty()) {
            int[] m = mapToBitmap(vw, vh, screenshot.getWidth(), screenshot.getHeight(),
                    selectionRect.left, selectionRect.top,
                    selectionRect.right, selectionRect.bottom);
            Rect src = new Rect(m[0], m[1], m[2], m[3]);
            if (!src.isEmpty()) {
                canvas.drawBitmap(screenshot, src, selectionRect, bitmapPaint);
            }
            canvas.drawRect(selectionRect, borderPaint);
        }

        // 4. 右上角「✕ 取消」按钮（唯一的取消出口）
        computeCancelRect(vw);
        float radius = cancelRect.height() / 2f;
        canvas.drawRoundRect(cancelRect, radius, radius, chipPaint);
        Paint.FontMetrics fm = chipTextPaint.getFontMetrics();
        float textY = cancelRect.centerY() - (fm.ascent + fm.descent) / 2f;
        canvas.drawText("✕ 取消", cancelRect.centerX(), textY, chipTextPaint);

        // 5. 顶部提示文案
        if (hintText != null) {
            canvas.drawText(hintText, vw / 2f, dp(28), hintPaint);
        }
    }

    private void computeCancelRect(int vw) {
        int w = dp(88), h = dp(34), margin = dp(10);
        cancelRect = new RectF(vw - w - margin, margin, vw - margin, margin + h);
    }

    // ── 触摸 ─────────────────────────────────

    private boolean onTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX = event.getX();
                startY = event.getY();
                dragging = true;
                movedFar = false;
                selectionRect = new RectF(startX, startY, startX, startY);
                invalidateOverlay();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    if (Math.abs(event.getX() - startX) > tapSlopPx
                            || Math.abs(event.getY() - startY) > tapSlopPx) {
                        movedFar = true;
                    }
                    selectionRect.set(startX, startY, event.getX(), event.getY());
                    invalidateOverlay();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                // 系统手势（返回/多任务）抢走了这次滑动：
                // 不能用它去完成框选（会得到被截断的区域），保留当前框并提示重拖
                dragging = false;
                hintText = "边缘手势被系统拦截，请从屏幕内侧重新拖";
                invalidateOverlay();
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    dragging = false;
                    if (!movedFar) {
                        // 单击（没拖成框）：只有命中「✕ 取消」才退出；
                        // 点空白只是清掉当前框，绝不退出、不误触
                        if (cancelRect != null && cancelRect.contains(event.getX(), event.getY())) {
                            dismiss();
                            callback.onCancelled();
                        } else {
                            selectionRect = null;
                            invalidateOverlay();
                        }
                    } else {
                        onSelectionComplete();
                    }
                }
                return true;
        }
        return false;
    }

    // ── 框选完成 ─────────────────────────────

    private void onSelectionComplete() {
        if (selectionRect == null || screenshot == null) {
            dismiss();
            callback.onCancelled();
            return;
        }
        View v = overlayView;
        int vw = v == null ? 0 : v.getWidth();
        int vh = v == null ? 0 : v.getHeight();

        // 贴边吸附：拖到屏幕左右边缘附近就吸附到边，避免框选内容被切掉
        int[] snapped = snapToEdges(selectionRect.left, selectionRect.top,
                selectionRect.right, selectionRect.bottom, vw, edgeSnapPx);
        selectionRect = new RectF(snapped[0], snapped[1], snapped[2], snapped[3]);

        if (selectionRect.width() < minSizePx || selectionRect.height() < minSizePx) {
            // 框太小：不退出，给提示让用户重选（旧逻辑是静默清空，用户以为没反应）
            selectionRect = null;
            hintText = "框太小了，重新拖一个大一点的框";
            invalidateOverlay();
            return;
        }

        Bitmap region = null;
        if (vw > 0 && vh > 0) {
            int[] m = mapToBitmap(vw, vh, screenshot.getWidth(), screenshot.getHeight(),
                    selectionRect.left, selectionRect.top,
                    selectionRect.right, selectionRect.bottom);
            if (m[2] - m[0] >= 8 && m[3] - m[1] >= 8) {
                try {
                    region = Bitmap.createBitmap(screenshot, m[0], m[1],
                            m[2] - m[0], m[3] - m[1]);
                } catch (Exception e) {
                    Log.e(TAG, "crop failed: " + e.getMessage());
                }
            }
        }
        // 裁剪之后再撤层
        dismiss();
        if (region == null) {
            callback.onCancelled();
            return;
        }
        callback.onRegionSelected(region);
    }

    /**
     * 视图坐标 → 截图位图坐标（等比缩放 + 规范化 + clamp）。
     *
     * <p>截图是等比缩放画满视图的，所以两者之间存在统一的比例因子；
     * 位图与视图尺寸不一致（行对齐、分辨率差异）也能精确换算。
     * 纯函数，可 JVM 单测。</p>
     *
     * @return {left, top, right, bottom}（位图像素坐标）
     */
    static int[] mapToBitmap(int viewW, int viewH, int bmpW, int bmpH,
                             float l, float t, float r, float b) {
        float sx = (viewW > 0 && bmpW > 0) ? bmpW / (float) viewW : 1f;
        float sy = (viewH > 0 && bmpH > 0) ? bmpH / (float) viewH : 1f;
        int left = Math.round(Math.min(l, r) * sx);
        int top = Math.round(Math.min(t, b) * sy);
        int right = Math.max(left, Math.round(Math.max(l, r) * sx));
        int bottom = Math.max(top, Math.round(Math.max(t, b) * sy));
        left = Math.max(0, Math.min(left, bmpW));
        top = Math.max(0, Math.min(top, bmpH));
        right = Math.max(left, Math.min(right, bmpW));
        bottom = Math.max(top, Math.min(bottom, bmpH));
        return new int[]{left, top, right, bottom};
    }

    /**
     * 贴边吸附（视图坐标）：拖到屏幕左右边缘附近就吸附到边。
     *
     * <p>为什么需要：系统「返回」手势占着左右边缘（每侧最多只能排除 200dp），
     * 用户从内侧起拖时无法把框拉到屏幕最边，导致<b>框选内容被切掉</b>。
     * 吸附后拖到边缘附近即可选到整行。纯函数，可 JVM 单测。</p>
     *
     * @return {left, top, right, bottom}
     */
    static int[] snapToEdges(float l, float t, float r, float b, int viewW, int edgeSnapPx) {
        float left = Math.min(l, r);
        float right = Math.max(l, r);
        if (left <= edgeSnapPx) left = 0f;
        if (viewW > 0 && viewW - right <= edgeSnapPx) right = viewW;
        return new int[]{Math.round(left), Math.round(t), Math.round(right), Math.round(b)};
    }

    // ── 收尾 ─────────────────────────────────

    private void invalidateOverlay() {
        if (overlayView != null) overlayView.invalidate();
    }

    /** 移除框选层。 */
    /** 移除框选层。可在任意线程调用（窗口操作会切到主线程）。 */
    public void dismiss() {
        final View v = overlayView;
        overlayView = null;
        if (v != null) {
            // 投影回调线程（releaseCapture）也会走到这里：
            // WindowManager 操作必须在主线程，否则 CalledFromWrongThreadException
            mainHandler.post(() -> {
                try {
                    if (v.getParent() != null) wm.removeView(v);
                } catch (Exception e) {
                    Log.w(TAG, "removeView failed: " + e.getMessage());
                }
            });
        }
        // ⚠️ 不 recycle 截图：窗口刚 remove 时 RenderThread 可能还持有该位图的
        // 引用，立即 recycle 会概率性闪退（Canvas: trying to use a recycled bitmap）。
        // Android 8+ 的位图由 GC 经 NativeAllocationRegistry 释放 native 内存。
        screenshot = null;
        selectionRect = null;
        cancelRect = null;
    }

    private int dp(int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
