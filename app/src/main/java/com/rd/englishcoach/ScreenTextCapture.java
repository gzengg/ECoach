package com.rd.englishcoach;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
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

    public ScreenTextCapture(WindowManager wm) {
        this.wm = wm;
        updateScreenSize();
    }

    /** 更新屏幕尺寸（横竖屏变化时调用）。 */
    public void updateScreenSize() {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(dm);
        screenWidth = dm.widthPixels;
        screenHeight = dm.heightPixels;
        screenDpi = dm.densityDpi;
    }

    /**
     * 初始化 VirtualDisplay（只调一次）。
     * 必须在 MediaProjection.registerCallback() 之后调用。
     *
     * @return true 如果创建成功
     */
    public boolean init(MediaProjection projection) {
        if (virtualDisplay != null) {
            Log.w(TAG, "VirtualDisplay already exists, skip");
            return true;
        }
        try {
            imageReader = ImageReader.newInstance(
                    screenWidth, screenHeight, ImageFormat.FLEX_RGBA_8888, 2);
            virtualDisplay = projection.createVirtualDisplay(
                    "ECoach-screenshot",
                    screenWidth, screenHeight, screenDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, null);
            if (virtualDisplay == null) {
                Log.e(TAG, "createVirtualDisplay returned null");
                return false;
            }
            Log.i(TAG, "VirtualDisplay created: " + screenWidth + "x" + screenHeight);
            return true;
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException: " + e.getMessage());
            return false;
        } catch (Exception e) {
            Log.e(TAG, "init failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 截取一帧屏幕。必须在后台线程调用。
     *
     * @return 截图 Bitmap，失败返回 null
     */
    public Bitmap captureFrame() {
        if (imageReader == null) return null;
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
            Log.e(TAG, "captureFrame failed: " + e.getMessage());
            return null;
        } finally {
            if (image != null) image.close();
        }
    }

    /** 是否已初始化。 */
    public boolean isReady() {
        return virtualDisplay != null && imageReader != null;
    }

    /** 释放资源（会话结束时调用，之后不能再截图）。 */
    public void release() {
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Exception ignored) {}
            virtualDisplay = null;
        }
        if (imageReader != null) {
            try { imageReader.close(); } catch (Exception ignored) {}
            imageReader = null;
        }
    }
}
