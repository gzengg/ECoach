package com.rd.englishcoach;

/**
 * 固定容量的环形 PCM 缓冲区，只保留最近 {@code maxBytes} 字节。
 * 用于在"这句完了"时取最近 N 秒的音频快照。
 *
 * <p>线程安全：写入线程（AudioRecord 读取线程）和读取/清空线程（UI / 网络线程）
 * 可以并发操作，通过 synchronized 保证一致性。</p>
 */
public final class PcmRing {

    private final byte[] buf;
    private final int capacity;
    private int readPos;   // 最旧有效字节的位置
    private int totalLen;  // 有效总字节数（≤ capacity）

    /**
     * @param maxBytes 最大保留字节数。示例：25 秒 × 16000 Hz × 2 字节 = 800000。
     */
    public PcmRing(int maxBytes) {
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be > 0");
        this.capacity = maxBytes;
        this.buf = new byte[maxBytes];
    }

    /**
     * 追加 PCM 字节。若超过容量，最旧的数据被覆盖。
     */
    public synchronized void write(byte[] data, int offset, int len) {
        if (len <= 0 || data == null) return;

        if (len >= capacity) {
            // 新数据超过或等于容量 → 只保留最后 capacity 字节
            readPos = 0;
            totalLen = 0;
            offset = offset + len - capacity;
            len = capacity;
        } else {
            int space = capacity - totalLen;
            if (len > space) {
                // 丢弃最旧的 (len - space) 字节
                int drop = len - space;
                readPos = (readPos + drop) % capacity;
                totalLen -= drop;
            }
        }

        int writeIdx = (readPos + totalLen) % capacity;
        int first = Math.min(len, capacity - writeIdx);
        System.arraycopy(data, offset, buf, writeIdx, first);
        if (first < len) {
            System.arraycopy(data, offset + first, buf, 0, len - first);
        }
        totalLen += len;
    }

    /**
     * 快照：返回缓冲区中所有有效字节（按时间顺序，最旧→最新）。
     * 调用后缓冲区不清空。
     */
    public synchronized byte[] snapshot() {
        if (totalLen == 0) return new byte[0];
        byte[] out = new byte[totalLen];
        int start = readPos;
        int first = Math.min(totalLen, capacity - start);
        System.arraycopy(buf, start, out, 0, first);
        if (first < totalLen) {
            System.arraycopy(buf, 0, out, first, totalLen - first);
        }
        return out;
    }

    /**
     * 取快照并清空缓冲区（原子操作）。
     */
    public synchronized byte[] snapshotAndClear() {
        byte[] s = snapshot();
        clear();
        return s;
    }

    /**
     * 清空缓冲区。
     */
    public synchronized void clear() {
        readPos = 0;
        totalLen = 0;
    }

    /**
     * 当前有效字节数。
     */
    public synchronized int size() {
        return totalLen;
    }

    /**
     * 缓冲区最大容量（字节）。
     */
    public int capacity() {
        return capacity;
    }
}
