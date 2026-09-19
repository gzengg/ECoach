package com.rd.englishcoach;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * PcmRing 的单元测试：验证环形缓冲区的读写、截断、清空行为。
 */
public class PcmRingTest {

    // ── 基本读写 ──────────────────────────────

    @Test
    public void writeAndSnapshot() {
        PcmRing ring = new PcmRing(100);
        byte[] data = {1, 2, 3, 4, 5};
        ring.write(data, 0, 5);

        assertEquals(5, ring.size());
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, ring.snapshot());
    }

    @Test
    public void emptyRing() {
        PcmRing ring = new PcmRing(100);
        assertEquals(0, ring.size());
        assertArrayEquals(new byte[0], ring.snapshot());
    }

    @Test
    public void writeExactCapacity() {
        PcmRing ring = new PcmRing(10);
        byte[] data = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        ring.write(data, 0, 10);
        assertEquals(10, ring.size());
        assertArrayEquals(data, ring.snapshot());
    }

    // ── 环绕写入 ──────────────────────────────

    @Test
    public void wrapAround() {
        PcmRing ring = new PcmRing(10);
        // 先填满
        byte[] first = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        ring.write(first, 0, 10);

        // 再追加 3 字节，最旧的 3 字节被丢弃
        byte[] second = {11, 12, 13};
        ring.write(second, 0, 3);

        assertEquals(10, ring.size());
        assertArrayEquals(new byte[]{4, 5, 6, 7, 8, 9, 10, 11, 12, 13}, ring.snapshot());
    }

    @Test
    public void overwriteMoreThanCapacity() {
        PcmRing ring = new PcmRing(5);
        // 写入 3 字节
        ring.write(new byte[]{1, 2, 3}, 0, 3);
        // 写入 8 字节（超过总容量），只保留最后 5 字节
        ring.write(new byte[]{10, 11, 12, 13, 14, 15, 16, 17}, 0, 8);

        assertEquals(5, ring.size());
        assertArrayEquals(new byte[]{13, 14, 15, 16, 17}, ring.snapshot());
    }

    @Test
    public void singleByteFillsCapacity() {
        PcmRing ring = new PcmRing(3);
        // 逐字节写入
        for (int i = 0; i < 6; i++) {
            ring.write(new byte[]{(byte)(i + 1)}, 0, 1);
        }
        assertEquals(3, ring.size());
        assertArrayEquals(new byte[]{4, 5, 6}, ring.snapshot());
    }

    // ── snapshotAndClear ────────────────────

    @Test
    public void snapshotAndClear() {
        PcmRing ring = new PcmRing(100);
        ring.write(new byte[]{10, 20, 30}, 0, 3);

        byte[] snap = ring.snapshotAndClear();
        assertArrayEquals(new byte[]{10, 20, 30}, snap);
        assertEquals(0, ring.size());
        assertArrayEquals(new byte[0], ring.snapshot());
    }

    @Test
    public void snapshotAndClearEmpty() {
        PcmRing ring = new PcmRing(100);
        byte[] snap = ring.snapshotAndClear();
        assertEquals(0, snap.length);
        assertEquals(0, ring.size());
    }

    // ── clear ──────────────────────────────

    @Test
    public void clearResetsState() {
        PcmRing ring = new PcmRing(10);
        ring.write(new byte[]{1, 2, 3, 4, 5}, 0, 5);
        ring.clear();

        assertEquals(0, ring.size());

        // 清空后重新写入，snapshot 正确
        ring.write(new byte[]{6, 7}, 0, 2);
        assertEquals(2, ring.size());
        assertArrayEquals(new byte[]{6, 7}, ring.snapshot());
    }

    // ── 边界条件 ──────────────────────────────

    @Test
    public void writeNull() {
        PcmRing ring = new PcmRing(10);
        ring.write(null, 0, 5);
        assertEquals(0, ring.size());
    }

    @Test
    public void writeZeroLength() {
        PcmRing ring = new PcmRing(10);
        ring.write(new byte[]{1, 2, 3}, 0, 0);
        assertEquals(0, ring.size());
    }

    @Test
    public void writeWithOffset() {
        PcmRing ring = new PcmRing(10);
        byte[] data = {0, 0, 0, 7, 8, 9, 0};
        ring.write(data, 3, 3); // 取 data[3..5] = {7, 8, 9}
        assertEquals(3, ring.size());
        assertArrayEquals(new byte[]{7, 8, 9}, ring.snapshot());
    }

    @Test
    public void capacityReturnsConfiguredValue() {
        assertEquals(500, new PcmRing(500).capacity());
        assertEquals(1, new PcmRing(1).capacity());
    }

    // ── 并发安全（简单冒烟） ──────────────────

    @Test
    public void concurrentWriteAndSnapshot() throws Exception {
        PcmRing ring = new PcmRing(10000);
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 1000; i++) {
                ring.write(new byte[]{(byte)i}, 0, 1);
            }
        });
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 500; i++) {
                ring.snapshot();
                ring.size();
            }
        });
        writer.start();
        reader.start();
        writer.join(2000);
        reader.join(2000);

        // writer 应该已经完成（1000 次写入不会超过 2 秒）
        assertFalse("writer thread stuck", writer.isAlive());
        assertFalse("reader thread stuck", reader.isAlive());
        // ring 不崩溃且有数据
        assertTrue(ring.size() > 0);
        assertTrue(ring.size() <= 10000);
    }
}
