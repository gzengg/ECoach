package com.rd.englishcoach;

import java.io.ByteArrayOutputStream;

/**
 * 可增长的 PCM 缓冲区（替代 PcmRing 的环形截断方案）。
 * 用于"暂停时上传整段录音"的场景：从继续到暂停之间的所有音频都会保留。
 *
 * <p>线程安全：读写线程不同，所有公开方法 synchronized。</p>
 */
public final class PcmBuffer {

    /** 安全上限：10 分钟 × 16kHz × 2 字节 = 19,200,000 bytes ≈ 18.3MB */
    private static final int MAX_BYTES = 19_200_000;

    private final ByteArrayOutputStream buf = new ByteArrayOutputStream(256_000);
    private volatile boolean overflow = false;

    public synchronized void write(byte[] data, int offset, int len) {
        if (buf.size() + len > MAX_BYTES) {
            overflow = true;
            return; // 丢弃后续数据，避免 OOM
        }
        buf.write(data, offset, len);
    }

    /** 取出全部 PCM 数据并清空缓冲区。 */
    public synchronized byte[] snapshotAndClear() {
        byte[] result = buf.toByteArray();
        buf.reset();
        overflow = false;
        return result;
    }

    public synchronized int size() { return buf.size(); }
    public synchronized void clear() { buf.reset(); overflow = false; }
    public boolean isOverflow() { return overflow; }
}
