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
import android.util.Log;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/**
 * 屏幕截图：基于常驻 VirtualDisplay + ImageReader。
 *
 * <p>⚠️ 设计约束（AOSP 源码实证）：同一 MediaProjection 只能建一次 VirtualDisplay。
 * 所以必须在 startCapture() 里提前建好，取词时只从 ImageReader 取帧。</p>
 *
 * <p>生命周期：与 CaptureService 同生共死。取词逻辑不得 release 它。</p>
 */
public final class ScreenTextCapture {

    private static final String TAG = "ScreenCapture";

    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private final WindowManager wm;
    private int screenWidth, screenHeight, screenDpi;
    private int readerFormat = -1;
    /** 最近一次初始化/截帧失败的原因（null = 没失败过），供 UI 给用户可读提示。 */
    private String lastError;

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
     * <p>⚠️ 同一 MediaProjection 只能成功 createVirtualDisplay 一次，第二次会抛
     * SecurityException <b>并停掉整个投屏会话</b>。所以：
     * ImageReader 的像素格式回退必须在调用 createVirtualDisplay <b>之前</b>完成，
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

    /**
     * 建 ImageReader，像素格式逐个回退（探针实测 RGBA_8888 最稳，FLEX_RGBA_8888 兜底）。
     * 只在这里回退，不碰 projection。
     */
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

    private void closeReader() {
        if (imageReader != null) {
            try { imageReader.close(); } catch (Exception ignored) {}
            imageReader = null;
        }
    }

    /**
     * 截取一帧屏幕。必须在后台线程调用。
     *
     * @return 截图 Bitmap，失败返回 null
     */
    public Bitmap captureFrame() {
        if (imageReader == null) {
            lastError = "截屏通道未初始化";
            return null;
        }
        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null) {
                Log.w(TAG, "acquireLatestImage returned null");
                return null;
            }
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * image.getWidth();

            Bitmap raw = Bitmap.createBitmap(
                    image.getWidth() + rowPadding / pixelStride, image.getHeight(),
                    Bitmap.Config.ARGB_8888);
            raw.copyPixelsFromBuffer(buffer);
            Bitmap cropped = Bitmap.createBitmap(raw, 0, 0, image.getWidth(), image.getHeight());
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
        lastError = "截屏会话已结束，请重新授权";
    }
}
