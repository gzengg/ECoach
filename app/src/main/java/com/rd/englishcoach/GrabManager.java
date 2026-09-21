package com.rd.englishcoach;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 取词状态机：协调截图 → 框选 → OCR → 翻译 → 显示结果。
 *
 * <p>状态流转：IDLE → CAPTURING → SELECTING → OCR → TRANSLATING → DONE</p>
 * <p>任何步骤失败 → abort → 回到 IDLE。</p>
 */
public final class GrabManager {

    private static final String TAG = "GrabManager";

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
            Log.w(TAG, "startGrab ignored, state=" + state);
            return;
        }
        setState(State.CAPTURING);

        // 延迟 150ms 等面板隐藏后再截图
        mainHandler.postDelayed(() -> {
            Bitmap frame = capture.captureFrame();
            if (frame == null) {
                abort("截屏失败");
                return;
            }
            setState(State.SELECTING);
            overlay.show(frame);
        }, 150);
    }

    /** 用户框选完成，启动 OCR + 翻译。 */
    public void onRegionSelected(int left, int top, int right, int bottom,
                                  ScreenTextCapture capture) {
        if (state != State.SELECTING) return;

        setState(State.OCR);

        // 后台线程：裁剪 → OCR → 翻译
        new Thread(() -> {
            try {
                Bitmap cropped = capture.captureRegion(left, top, right, bottom);
                if (cropped == null) {
                    failOnMain("裁剪失败");
                    return;
                }

                // OCR
                final String[] sourceText = {null};
                final boolean[] ocrDone = {false};
                OcrEngine.recognize(cropped, new OcrEngine.Callback() {
                    @Override public void onResult(String text) {
                        sourceText[0] = text;
                        ocrDone[0] = true;
                        synchronized (ocrDone) { ocrDone.notifyAll(); }
                    }
                    @Override public void onError(String error) {
                        sourceText[0] = error;
                        ocrDone[0] = true;
                        synchronized (ocrDone) { ocrDone.notifyAll(); }
                    }
                });

                // 等 OCR 完成
                synchronized (ocrDone) {
                    while (!ocrDone[0]) ocrDone.wait(10000);
                }

                if (sourceText[0] == null || sourceText[0].isEmpty()) {
                    failOnMain("没认到文字，把框拉大一点");
                    return;
                }
                if (sourceText[0].startsWith("OCR")) {
                    // OCR 错误
                    failOnMain(sourceText[0]);
                    return;
                }

                mainHandler.post(() -> callback.onOcrResult(sourceText[0]));

                // 翻译
                setState(State.TRANSLATING);
                try {
                    String translated = Translator.translate(sourceText[0]);
                    final String src = sourceText[0];
                    final String dst = translated;
                    mainHandler.post(() -> callback.onTranslationResult(src, dst));
                } catch (Exception e) {
                    final String src = sourceText[0];
                    final String err = e.getMessage();
                    mainHandler.post(() -> callback.onTranslationFailed(src, err));
                }

                setState(State.DONE);
                // 自动回 IDLE
                mainHandler.postDelayed(() -> setState(State.IDLE), 100);

            } catch (InterruptedException e) {
                failOnMain("被中断");
            }
        }).start();
    }

    /** 用户取消框选。 */
    public void onCancelled() {
        if (state == State.SELECTING) {
            setState(State.IDLE);
            if (callback != null) mainHandler.post(() -> callback.onAborted("取消"));
        }
    }

    /** 统一失败处理：回 IDLE + 通知。 */
    private void abort(String reason) {
        setState(State.IDLE);
        if (callback != null) mainHandler.post(() -> callback.onAborted(reason));
    }

    private void failOnMain(String reason) {
        setState(State.IDLE);
        if (callback != null) mainHandler.post(() -> {
            if (state == State.IDLE) { // 已回 IDLE
                callback.onAborted(reason);
            }
        });
    }

    private void setState(State newState) {
        state = newState;
        Log.i(TAG, "state → " + newState);
        if (callback != null) mainHandler.post(() -> callback.onStateChanged(newState));
    }
}
