package com.rd.englishcoach;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * PcmBuffer 的回归测试（替代 PcmRing 的环形截断方案）。
 */
public class PcmBufferTest {

    @Test
    public void writeAndSnapshot() {
        PcmBuffer buf = new PcmBuffer();
        byte[] d = {1, 2, 3, 4, 5};
        buf.write(d, 0, 5);
        assertArrayEquals(d, buf.snapshotAndClear());
        assertEquals(0, buf.size());
    }

    @Test
    public void growingBuffer_noCap() {
        PcmBuffer buf = new PcmBuffer();
        for (int i = 0; i < 1000; i++) buf.write(new byte[]{(byte) i}, 0, 1);
        assertEquals(1000, buf.size());
        byte[] snap = buf.snapshotAndClear();
        assertEquals(1000, snap.length);
    }

    @Test
    public void clear_resets() {
        PcmBuffer buf = new PcmBuffer();
        buf.write(new byte[]{1, 2, 3}, 0, 3);
        buf.clear();
        assertEquals(0, buf.size());
        assertArrayEquals(new byte[0], buf.snapshotAndClear());
    }

    @Test
    public void snapshotAndClear_clearsBuffer() {
        PcmBuffer buf = new PcmBuffer();
        buf.write(new byte[]{10}, 0, 1);
        buf.snapshotAndClear();
        assertEquals(0, buf.size());
    }

    @Test
    public void overflow_stopsGrowing() {
        PcmBuffer buf = new PcmBuffer();
        // Write more than MAX_BYTES (19.2MB)
        byte[] chunk = new byte[1_000_000]; // 1MB
        for (int i = 0; i < 25; i++) buf.write(chunk, 0, chunk.length);
        assertTrue(buf.isOverflow());
        // Buffer should be capped
        assertTrue(buf.size() <= 20_000_000);
    }
}
