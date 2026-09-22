package com.rd.englishcoach;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/**
 * 屏幕截图：基于常驻 VirtualDisplay + ImageReader。
 *
 * <p>⚠️ 设计约束（AOSP 源码实证，见 .local/MEDIAPROJECTION_FINDINGS.md）：
 * 同一 MediaProjection 只能成功 createVirtualDisplay 一次，第二次会抛 SecurityException
 * 并停掉整个投屏会话（连录音一起死）。所以必须在 startCapture() 里提前建好、常驻复用，
 * 取词时绝不重建。</p>
 *
 * <p><b>v3.1 取帧机制</b>（修复真机反馈「第二次取词失败 / 框选画面是旧的」）：
 * 常驻镜像只在屏幕内容变化时才产新帧；没人持续消费时，队列里滞留的是旧帧，
 * {@code acquireLatestImage} 要么拿到陈旧画面、要么等不到新帧。
 * 因此 {@link #grabFrame()} 每次先丢弃滞留帧，再用
 * {@code setSurface(null) → setSurface(reader)} 强制 SurfaceFlinger 立即重绘一帧
 * （探针 V1.1 在本机实测可行），并用 {@link ImageReader.OnImageAvailableListener}
 * 等这帧真正到位，超时才报错。</p>
 *
 * <p>生命周期：与 CaptureService 同生共死。取词逻辑不得 release 它。</p>
 */
public final class ScreenTextCapture {

    private static final String TAG = "ScreenCapture";

    /** 强制重绘后等新帧的最长时间。 */
    private static final long FRAME_WAIT_MS = 3000;
    /** 等新帧的重绘重试次数（第一次超时后再强制重绘一次）。 */
    private static final int SURFACE_CYCLE_RETRIES = 2;

    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private final WindowManager wm;
    private int screenWidth, screenHeight, screenDpi;
    private int readerFormat = -1;
    /** 最近一次失败的原因（null = 没失败过），供 UI 给用户可读提示。 */
    private String lastError;
    /** reader 回调线程（避免占用主线程）。 */
    private HandlerThread readerThread;
    private final Object frameLock = new Object();
    private boolean frameArrived;

    public ScreenTextCapture(WindowManager wm) {
        this.wm = wm;
        updateScreenSize();
    }

    /**
     * 更新屏幕尺寸（横竖屏变化时调用）。
     * 用 {@code getRealSize}：VirtualDisplay 镜的是整块屏幕，
     * {@code getMetrics} 会排除导航栏 → 截图与框选坐标错位。
     */
    public void updateScreenSize() {
        Point size = new Point();
        wm.getDefaultDisplay().getRealSize(size);
        screenWidth = size.x;
        screenHeight = size.y;
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(dm);
        screenDpi = dm.densityDpi;
    }

    /**
     * 初始化 VirtualDisplay（只调一次）。
     * 必须在 MediaProjection.registerCallback() 之后调用。
     *
     * <p>⚠️ ImageReader 的像素格式回退必须在 createVirtualDisplay <b>之前</b>完成；
     * 这里保证 createVirtualDisplay 只被调用一次。</p>
     *
     * @return true 如果创建成功
     */
    public boolean init(MediaProjection projection) {
        if (virtualDisplay != null) {
            Log.w(TAG, "VirtualDisplay already exists, skip");
            return true;
        }
        if (!createReader()) return false;
        try {
            readerThread = new HandlerThread("capture-reader");
            readerThread.start();
            imageReader.setOnImageAvailableListener(reader -> {
                synchronized (frameLock) {
                    frameArrived = true;
                    frameLock.notifyAll();
                }
            }, new Handler(readerThread.getLooper()));

            virtualDisplay = projection.createVirtualDisplay(
                    "ECoach-screenshot",
                    screenWidth, screenHeight, screenDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, null);
            if (virtualDisplay == null) {
                lastError = "createVirtualDisplay 返回 null";
                Log.e(TAG, lastError);
                closeReader();
                return false;
            }
            lastError = null;
            Log.i(TAG, "VirtualDisplay created: " + screenWidth + "x" + screenHeight
                    + " fmt=" + readerFormat);
            return true;
        } catch (Exception e) {
            lastError = "创建截屏通道失败: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? " " + e.getMessage() : "");
            Log.e(TAG, lastError, e);
            closeReader();
            return false;
        }
    }

    /** 建 ImageReader，像素格式逐个回退（探针实测 RGBA_8888 最稳，FLEX_RGBA_8888 兜底）。 */
    private boolean createReader() {
        int[] formats = { PixelFormat.RGBA_8888, ImageFormat.FLEX_RGBA_8888 };
        for (int fmt : formats) {
            try {
                imageReader = ImageReader.newInstance(screenWidth, screenHeight, fmt, 2);
                readerFormat = fmt;
                return true;
            } catch (Exception e) {
                lastError = "ImageReader 不支持格式 " + fmt + ": " + e.getMessage();
                Log.w(TAG, lastError);
                imageReader = null;
            }
        }
        return false;
    }

    // ── 取帧（v3.1） ─────────────────────────────

    /**
     * 取一帧「当下屏幕」的截图。阻塞调用，必须在后台线程。
     *
     * @return 全屏 Bitmap；失败返回 null（原因见 {@link #lastError()}）
     */
    public Bitmap grabFrame() {
        if (!isReady()) {
            lastError = "截屏通道未初始化";
            return null;
        }
        try {
            for (int attempt = 0; attempt < SURFACE_CYCLE_RETRIES; attempt++) {
                // 1) 先复位标志再丢弃滞留帧：丢弃期间若恰好有新帧到达也不丢信号
                synchronized (frameLock) { frameArrived = false; }
                drainStaleFrames();

                // 2) 断开再接上表面 → SurfaceFlinger 立即重绘一帧当前镜像内容
                virtualDisplay.setSurface(null);
                virtualDisplay.setSurface(imageReader.getSurface());

                // 3) 等这一帧到位
                long deadline = System.currentTimeMillis() + FRAME_WAIT_MS;
                synchronized (frameLock) {
                    while (!frameArrived && System.currentTimeMillis() < deadline) {
                        try {
                            frameLock.wait(50);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            lastError = "等待屏幕帧被中断";
                            return null;
                        }
                    }
                    if (frameArrived) break;
                    Log.w(TAG, "no frame after surface cycle, attempt " + (attempt + 1));
                }
            }
            synchronized (frameLock) {
                if (!frameArrived) {
                    lastError = "屏幕没有产出新帧（画面静止或已锁屏）";
                    return null;
                }
            }

            // 4) 取帧转 Bitmap
            Bitmap bmp = acquireBitmap();
            if (bmp == null && (lastError == null || lastError.isEmpty())) {
                lastError = "取帧失败";
            }
            return bmp;
        } catch (Exception e) {
            lastError = "截帧异常: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? " " + e.getMessage() : "");
            Log.e(TAG, lastError, e);
            return null;
        }
    }

    /** 丢弃滞留在队列里的旧帧（内容可能是几分钟前的画面）。 */
    private void drainStaleFrames() {
        try {
            Image img;
            while ((img = imageReader.acquireLatestImage()) != null) {
                img.close();
            }
        } catch (Exception ignored) {
        }
    }

    /** 从 reader 取最新帧并转成 Bitmap（处理行对齐 padding）。 */
    private Bitmap acquireBitmap() {
        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null) {
                lastError = "帧在获取前已被消费";
                Log.w(TAG, lastError);
                return null;
            }
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            buffer.rewind(); // 必须从 0 开始读
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int w = image.getWidth();
            int h = image.getHeight();
            int rowPadding = rowStride - pixelStride * w;

            Bitmap raw = Bitmap.createBitmap(w + rowPadding / pixelStride, h,
                    Bitmap.Config.ARGB_8888);
            raw.copyPixelsFromBuffer(buffer);
            Bitmap cropped = Bitmap.createBitmap(raw, 0, 0, w, h);
            if (cropped != raw) raw.recycle();
            return cropped;
        } catch (Exception e) {
            lastError = "截帧失败: " + e.getMessage();
            Log.e(TAG, lastError, e);
            return null;
        } finally {
            if (image != null) image.close();
        }
    }

    /** 是否已初始化。 */
    public boolean isReady() {
        return virtualDisplay != null && imageReader != null;
    }

    /** 最近一次失败原因（null = 没失败过）。UI 用它给用户可读提示。 */
    public String lastError() {
        return lastError;
    }

    /** 释放资源（会话结束时调用，之后不能再截图）。 */
    public void release() {
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Exception ignored) {}
            virtualDisplay = null;
        }
        closeReader();
        if (readerThread != null) {
            readerThread.quitSafely();
            readerThread = null;
        }
        lastError = "截屏会话已结束，请重新授权";
    }

    private void closeReader() {
        if (imageReader != null) {
            try { imageReader.close(); } catch (Exception ignored) {}
            imageReader = null;
        }
    }
}
