package com.rd.englishcoach;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

/**
 * 取词框选层：全屏覆盖定格截图 + 拖动选择矩形区域。
 *
 * <p>交互：ACTION_DOWN 记起点 → MOVE 画实线框 → UP 结束。</p>
 * <p>最小尺寸校验：< 32dp 视为误触，提示重选。</p>
 * <p>取消出口：点框外 / 一个取消按钮。</p>
 */
public final class GrabOverlay {

    private static final String TAG = "GrabOverlay";

    public interface Callback {
        /** 框选完成：region 已从展示帧裁好（回调方负责 recycle）。 */
        void onRegionSelected(Bitmap region);
        void onCancelled();
    }

    private final Context ctx;
    private final WindowManager wm;
    private final Callback callback;
    private View overlayView;
    private Bitmap screenshot;
    private float startX, startY;
    private RectF selectionRect;
    private boolean dragging;
    private int minSizePx;

    public GrabOverlay(Context ctx, Callback callback) {
        this.ctx = ctx;
        this.wm = ctx.getSystemService(WindowManager.class);
        this.callback = callback;
        this.minSizePx = Math.round(32 * ctx.getResources().getDisplayMetrics().density);
    }

    /** 显示框选层，背景为定格截图。 */
    public void show(Bitmap screenshot) {
        if (screenshot == null) {
            callback.onCancelled();
            return;
        }
        this.screenshot = screenshot;
        selectionRect = null;
        dragging = false;

        overlayView = new View(ctx) {
            @Override
            protected void onDraw(Canvas canvas) {
                // 画截图
                canvas.drawBitmap(screenshot, 0, 0, null);
                // 画半透明遮罩
                canvas.drawColor(Color.argb(120, 0, 0, 0));
                // 画选择框（清空框内遮罩）
                if (selectionRect != null && !selectionRect.isEmpty()) {
                    Paint clearPaint = new Paint();
                    clearPaint.setXfermode(new android.graphics.PorterDuffXfermode(
                            android.graphics.PorterDuff.Mode.CLEAR));
                    canvas.drawRect(selectionRect, clearPaint);
                    // 画框边框
                    Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    borderPaint.setColor(Color.WHITE);
                    borderPaint.setStyle(Paint.Style.STROKE);
                    borderPaint.setStrokeWidth(3f);
                    canvas.drawRect(selectionRect, borderPaint);
                }
            }

            @Override
            public boolean onTouchEvent(MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = event.getX();
                        startY = event.getY();
                        dragging = true;
                        selectionRect = new RectF(startX, startY, startX, startY);
                        invalidate();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (dragging) {
                            selectionRect.set(startX, startY, event.getX(), event.getY());
                            invalidate();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (dragging) {
                            dragging = false;
                            onSelectionComplete();
                        }
                        return true;
                }
                return false;
            }
        };

        overlayView.setOnTouchListener((v, event) -> {
            // 点击框外 = 取消（只在非拖动状态时）
            if (event.getAction() == MotionEvent.ACTION_DOWN && !dragging) {
                // 如果有选择框且点击在框外 = 取消
                if (selectionRect != null && !selectionRect.contains(event.getX(), event.getY())) {
                    dismiss();
                    callback.onCancelled();
                    return true;
                }
            }
            return overlayView.onTouchEvent(event);
        });

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

    /** 显示「取消」按钮（叠加在框选层上）。 */
    public void showCancelButton() {
        // 简化：不加额外按钮，点框外即取消
    }

    private void onSelectionComplete() {
        if (selectionRect == null || screenshot == null) {
            dismiss();
            callback.onCancelled();
            return;
        }
        int left = (int) Math.min(selectionRect.left, selectionRect.right);
        int top = (int) Math.min(selectionRect.top, selectionRect.bottom);
        int right = (int) Math.max(selectionRect.left, selectionRect.right);
        int bottom = (int) Math.max(selectionRect.top, selectionRect.bottom);
        int width = right - left;
        int height = bottom - top;

        if (width < minSizePx || height < minSizePx) {
            // 框太小，提示重选
            selectionRect = null;
            invalidateOverlay();
            return;
        }

        // 裁剪「展示帧」（与用户看到的画面严格一致），再销毁层
        Bitmap region = null;
        try {
            int l = Math.max(0, left);
            int t = Math.max(0, top);
            int r = Math.min(screenshot.getWidth(), right);
            int b = Math.min(screenshot.getHeight(), bottom);
            if (r > l && b > t) {
                region = Bitmap.createBitmap(screenshot, l, t, r - l, b - t);
            }
        } catch (Exception e) {
            Log.e(TAG, "crop failed: " + e.getMessage());
        }
        dismiss(); // 内部会 recycle 展示帧，必须在裁剪之后
        if (region == null) {
            callback.onCancelled();
            return;
        }
        callback.onRegionSelected(region);
    }

    private void invalidateOverlay() {
        if (overlayView != null) overlayView.invalidate();
    }

    /** 移除框选层。 */
    public void dismiss() {
        if (overlayView != null && overlayView.getParent() != null) {
            wm.removeView(overlayView);
        }
        overlayView = null;
        if (screenshot != null) {
            screenshot.recycle();
            screenshot = null;
        }
    }
}
