package com.rd.englishcoach;

import android.content.Context;
import android.media.MediaPlayer;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WAV 音频播放器：写临时文件 → MediaPlayer 播放。
 * 同文本自动缓存（key → WAV 字节），重复点读不再发请求。
 *
 * <p>生命周期：与 CaptureService 同生共死。{@link #release()} 时清空缓存 + 删临时文件。</p>
 */
public final class AudioPlayer {

    private static final String TAG = "AudioPlayer";
    private static final int MAX_CACHE = 50; // 最多缓存 50 条

    private final Context ctx;
    private final Map<String, byte[]> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > MAX_CACHE;
        }
    };

    private MediaPlayer player;
    private String currentKey;

    public AudioPlayer(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /**
     * 播放 WAV 字节。同 key 再次调用 = 停止。
     *
     * @param key  唯一标识
     * @param wav  WAV 字节（含 RIFF 头）
     */
    public void play(String key, byte[] wav, Listener listener) {
        // 同 key 再点 = 停止
        if (key != null && key.equals(currentKey) && player != null && player.isPlaying()) {
            stop();
            if (listener != null) listener.onComplete(key);
            return;
        }

        stop();
        currentKey = key;

        // 缓存
        if (key != null && wav != null) {
            cache.put(key, wav);
        }

        // 写临时文件
        File tmp;
        try {
            tmp = writeTemp(wav != null ? wav : cache.get(key));
        } catch (IOException e) {
            Log.e(TAG, "write temp failed: " + e.getMessage());
            if (listener != null) listener.onError(key, e.getMessage());
            return;
        }

        // 播放
        try {
            player = new MediaPlayer();
            player.setDataSource(tmp.getAbsolutePath());
            player.setOnCompletionListener(mp -> {
                stop();
                if (listener != null) listener.onComplete(key);
            });
            player.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error: " + what + "/" + extra);
                stop();
                if (listener != null) listener.onError(key, "MediaPlayer error " + what);
                return true;
            });
            player.prepare();
            player.start();
            Log.i(TAG, "playing key=" + key + " size=" + (wav != null ? wav.length : "cached"));
        } catch (Exception e) {
            Log.e(TAG, "play failed: " + e.getMessage());
            stop();
            if (listener != null) listener.onError(key, e.getMessage());
        }
    }

    /** 停止当前播放。 */
    public void stop() {
        if (player != null) {
            try {
                if (player.isPlaying()) player.stop();
                player.release();
            } catch (Exception ignored) {}
            player = null;
        }
        currentKey = null;
    }

    /** 是否正在播放。 */
    public boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    /** 获取缓存的 WAV 字节（命中返回，未命中返回 null）。 */
    public byte[] getCached(String key) {
        return cache.get(key);
    }

    /** 释放所有资源，清空缓存。 */
    public void release() {
        stop();
        cache.clear();
        // 删临时文件
        File dir = ctx.getCacheDir();
        File[] files = dir.listFiles(f -> f.getName().startsWith("tts_"));
        if (files != null) {
            for (File f : files) {
                if (!f.delete()) Log.w(TAG, "failed to delete " + f.getName());
            }
        }
    }

    private File writeTemp(byte[] wav) throws IOException {
        File f = new File(ctx.getCacheDir(), "tts_" + System.nanoTime() + ".wav");
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(wav);
        }
        return f;
    }

    interface Listener {
        void onComplete(String key);
        void onError(String key, String error);
    }
}
