package com.rd.englishcoach;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Prefs 的单元测试：验证静态工具方法（clamp / nullSafe）。
 * SharedPreferences 读写需要 Android Context，无法在纯 JVM 测试，
 * 故只测纯逻辑部分。
 */
public class PrefsTest {

    @Test
    public void clamp_withinRange() {
        assertEquals(5, Prefs.clamp(5, 0, 10));
    }

    @Test
    public void clamp_belowMin() {
        assertEquals(0, Prefs.clamp(-3, 0, 10));
    }

    @Test
    public void clamp_aboveMax() {
        assertEquals(10, Prefs.clamp(99, 0, 10));
    }

    @Test
    public void clamp_atBoundaries() {
        assertEquals(0, Prefs.clamp(0, 0, 10));
        assertEquals(10, Prefs.clamp(10, 0, 10));
    }

    @Test
    public void clamp_equalMinMax() {
        assertEquals(5, Prefs.clamp(100, 5, 5));
    }

    @Test
    public void nullSafe_nonEmpty() {
        assertEquals("abc", Prefs.nullSafe("abc", "def"));
    }

    @Test
    public void nullSafe_null() {
        assertEquals("def", Prefs.nullSafe(null, "def"));
    }

    @Test
    public void nullSafe_empty() {
        assertEquals("def", Prefs.nullSafe("", "def"));
    }

    @Test
    public void nullSafe_blank() {
        // 空字符串也应回退到默认值（TextUtils.isEmpty 认为 "" 是 empty）
        assertEquals("fallback", Prefs.nullSafe("", "fallback"));
    }
}
