package com.rd.englishcoach;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 取词状态机：协调截图 → 框选 → OCR → 翻译 → 显示结果。
 *
 * <p>状态流转：IDLE → CAPTURING → SELECTING → OCR → TRANSLATING → DONE</p>
 * <p>任何步骤失败 → abort → 回 IDLE。</p>
 *
 * <p>v3.x 修复：截图在后台线程执行（ImageReader 取帧不允许在主线程），
 * 首帧未到时短重试；框选完成后直接裁剪<b>展示的那张帧</b>，不再二次截屏
 * （二次截屏既可能取到 null，又会与用户框选的画面不一致）。</p>
 */
public final class GrabManager {

    private static final String TAG = "GrabManager";

    /** 等悬浮窗隐藏的延迟（面板先 hide，再截图，避免截到自己的文字）。 */
    private static final long PANEL_HIDE_DELAY_MS = 150;
    /** 首帧重试：次数 × 间隔。ImageReader 首帧可能晚于 VirtualDisplay 建立。 */
    private static final int FRAME_RETRIES = 8;
    private static final long FRAME_RETRY_INTERVAL_MS = 150;
    /** 看门狗：非交互阶段卡住时强制收尾，保证面板一定会回来。 */
    private static final long WATCHDOG_CAPTURE_MS = 8_000;
    private static final long WATCHDOG_PIPELINE_MS = 25_000;

    enum State { IDLE, CAPTURING, SELECTING, OCR, TRANSLATING, DONE }

    interface Callback {
        void onStateChanged(State state);
        void onCaptureFailed(String reason);
        void onOcrResult(String sourceText);
        void onTranslationResult(String sourceText, String translatedText);
        void onOcrFailed(String reason);
        void onTranslationFailed(String sourceText, String reason);
        void onAborted(String reason);
    }

    private State state = State.IDLE;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Callback callback;

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public State getState() { return state; }

    /** 启动取词流程：截图 → 等用户框选 → OCR → 翻译。 */
    public void startGrab(ScreenTextCapture capture, GrabOverlay overlay) {
        if (state != State.IDLE) {
            // 上一次流程卡住了（例如 OCR 抛异常/面板被杀）：强制复位，
            // 绝不能静默 return——那样面板会永远回不来，表现成「点取词没反应」。
            Log.w(TAG, "startGrab: 上次状态卡在 " + state + "，强制复位后重试");
            setState(State.IDLE);
        }
        setState(State.CAPTURING);
        armWatchdog(WATCHDOG_CAPTURE_MS);

        // 延迟 150ms 等面板隐藏后再截图；截图本身放后台线程（取帧非主线程操作）
        mainHandler.postDelayed(() -> new Thread(() -> {
            Bitmap frame;
            try {
                frame = captureWithRetry(capture);
            } catch (Throwable t) {
                Log.e(TAG, "capture thread crashed", t);
                frame = null;
            }
            if (frame == null) {
                captureFailed("截屏失败" + reasonSuffix(capture));
                return;
            }
            final Bitmap f = frame;
            mainHandler.post(() -> {
                if (state != State.CAPTURING) { // 已被取消/中断
                    f.recycle();
                    return;
                }
                setState(State.SELECTING);
                try {
                    overlay.show(f);
                } catch (Throwable t) {
                    Log.e(TAG, "overlay.show failed", t);
                    f.recycle();
                    failOnMain("无法显示框选层: " + t.getMessage());
                }
            });
        }, "grab-capture").start(), PANEL_HIDE_DELAY_MS);
    }

    /** 把截帧失败原因带上，方便用户/日志定位（例如「截屏通道未初始化」）。 */
    private String reasonSuffix(ScreenTextCapture capture) {
        String why = capture == null ? null : capture.lastError();
        return why == null || why.isEmpty() ? "" : "：" + why;
    }

    /** 后台线程取帧：首帧未到时按间隔重试。 */
    private Bitmap captureWithRetry(ScreenTextCapture capture) {
        for (int i = 0; i < FRAME_RETRIES; i++) {
            Bitmap frame = capture.captureFrame();
            if (frame != null) return frame;
            Log.w(TAG, "frame not ready, retry " + (i + 1) + "/" + FRAME_RETRIES);
            try { Thread.sleep(FRAME_RETRY_INTERVAL_MS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        }
        return null;
    }

    /**
     * 用户框选完成，对**展示帧裁剪**后的区域跑 OCR + 翻译。
     *
     * @param region 框选区域已从展示帧裁好的 Bitmap（本方法负责回收）
     */
    public void onRegionSelected(Bitmap region) {
        if (state != State.SELECTING) {
            if (region != null) region.recycle();
            return;
        }
        if (region == null) {
            failOnMain("裁剪失败");
            return;
        }
        setState(State.OCR);
        armWatchdog(WATCHDOG_PIPELINE_MS);

        // 后台线程：OCR → 翻译
        new Thread(() -> {
            // OCR 错误信息（null = 无错误）——必须是局部变量：
            // 做成实例字段会被上一次失败污染，导致后续取词全部直接报旧错。
            final String[] ocrError = {null};
            try {
                final String[] sourceText = {null};
                final boolean[] ocrDone = {false};
                OcrEngine.recognize(region, new OcrEngine.Callback() {
                    @Override public void onResult(String text) {
                        sourceText[0] = text;
                        ocrDone[0] = true;
                        synchronized (ocrDone) { ocrDone.notifyAll(); }
                    }
                    @Override public void onError(String error) {
                        sourceText[0] = null;
                        ocrError[0] = error;
                        ocrDone[0] = true;
                        synchronized (ocrDone) { ocrDone.notifyAll(); }
                    }
                });

                // 等 OCR 完成（不能在这里 recycle：ML Kit 的 InputImage.fromBitmap
                // 不拷贝像素，异步识别期间位图必须存活，否则 OCR 必失败）
                synchronized (ocrDone) {
                    while (!ocrDone[0]) ocrDone.wait(10000);
                }

                if (ocrError[0] != null) {
                    failOnMain(ocrError[0]);
                    return;
                }
                if (sourceText[0] == null || sourceText[0].isEmpty()) {
                    failOnMain("没认到文字，把框拉大一点");
                    return;
                }

                final String src = sourceText[0];
                mainHandler.post(() -> {
                    if (callback != null) callback.onOcrResult(src);
                });

                // 翻译
                setState(State.TRANSLATING);
                try {
                    String translated = Translator.translate(src);
                    mainHandler.post(() -> {
                        if (callback != null) callback.onTranslationResult(src, translated);
                    });
                } catch (Exception e) {
                    final String err = e.getMessage() != null ? e.getMessage() : "未知错误";
                    mainHandler.post(() -> {
                        if (callback != null) callback.onTranslationFailed(src, err);
                    });
                }

                setState(State.DONE);
                // 自动回 IDLE
                mainHandler.postDelayed(() -> setState(State.IDLE), 100);

            } catch (InterruptedException e) {
                failOnMain("被中断");
            } catch (Throwable t) {
                // 任何非预期异常/Error 都不能让状态机卡在 OCR/TRANSLATING
                Log.e(TAG, "ocr pipeline crashed", t);
                failOnMain("取词失败: " + t);
            } finally {
                region.recycle();
            }
        }, "grab-ocr").start();
    }

    /** 用户取消框选。 */
    public void onCancelled() {
        if (state == State.SELECTING) {
            setState(State.IDLE);
            if (callback != null) mainHandler.post(() -> callback.onAborted("取消"));
        }
    }

    private void failOnMain(String reason) {
        setState(State.IDLE);
        if (callback != null) mainHandler.post(() -> callback.onAborted(reason));
    }

    /** 截帧失败单独走 onCaptureFailed（与「用户取消/其他失败」区分）。 */
    private void captureFailed(String reason) {
        setState(State.IDLE);
        if (callback != null) mainHandler.post(() -> callback.onCaptureFailed(reason));
    }

    /** 武装看门狗：非交互阶段卡住 → 强制收尾，面板一定会回来。 */
    private void armWatchdog(long delayMs) {
        mainHandler.removeCallbacks(watchdog);
        mainHandler.postDelayed(watchdog, delayMs);
    }

    private final Runnable watchdog = () -> {
        if (state == State.CAPTURING || state == State.OCR || state == State.TRANSLATING) {
            Log.w(TAG, "watchdog fired, state=" + state);
            failOnMain("取词超时，请重试");
        }
    };

    private void setState(State newState) {
        state = newState;
        Log.i(TAG, "state → " + newState);
        if (newState == State.IDLE) mainHandler.removeCallbacks(watchdog);
        final State s = newState;
        if (callback != null) mainHandler.post(() -> callback.onStateChanged(s));
    }
}
