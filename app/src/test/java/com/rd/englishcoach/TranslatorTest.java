package com.rd.englishcoach;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Translator 纯函数单测：方向判定 + 200 假错误识别。
 */
public class TranslatorTest {

    // ── detectTarget ─────────────────────

    @Test
    public void detectTarget_chineseToEn() {
        assertEquals("en", Translator.detectTarget("今天天气真好"));
    }

    @Test
    public void detectTarget_englishToZh() {
        assertEquals("zh-CN", Translator.detectTarget("Nice to meet you"));
    }

    @Test
    public void detectTarget_emptyDefaultsToZh() {
        assertEquals("zh-CN", Translator.detectTarget(""));
    }

    @Test
    public void detectTarget_nullDefaultsToZh() {
        assertEquals("zh-CN", Translator.detectTarget(null));
    }

    @Test
    public void detectTarget_mixedChinese() {
        // 混合中英，只要有 CJK 就目标 en
        assertEquals("en", Translator.detectTarget("我喜欢 listening to music"));
    }

    @Test
    public void detectTarget_numbersOnly() {
        // 纯数字/标点，不含 CJK → 目标 zh-CN
        assertEquals("zh-CN", Translator.detectTarget("123 456"));
    }

    // ── isErrorText ─────────────────────

    @Test
    public void isErrorText_noQuerySpecified() {
        assertTrue(Translator.isErrorText("NO QUERY SPECIFIED. EXAMPLE REQUEST: GET?Q=HELLO&LANGPAIR=EN|IT"));
    }

    @Test
    public void isErrorText_invalidLanguage() {
        assertTrue(Translator.isErrorText("'XX' IS AN INVALID SOURCE LANGUAGE"));
    }

    @Test
    public void isErrorText_normalTranslation() {
        assertFalse(Translator.isErrorText("很高兴认识你。"));
    }

    @Test
    public void isErrorText_null() {
        assertTrue(Translator.isErrorText(null));
    }

    @Test
    public void isErrorText_empty() {
        assertTrue(Translator.isErrorText(""));
    }

    @Test
    public void isErrorText_caseInsensitive() {
        assertTrue(Translator.isErrorText("no query specified"));
    }
}
