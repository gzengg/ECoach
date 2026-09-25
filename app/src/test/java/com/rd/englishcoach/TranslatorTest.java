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

    // ── 长文本分段（回归：取词整屏 OCR 超 500 字符翻译必失败） ──

    @Test
    public void splitChunks_splitsLongTextAtSentenceBoundaries() {
        // 真机场景：取词识别到 580 字符，MyMemory 单次上限 500 → 必须切段
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) sb.append("Everything has its place. ");
        String text = sb.toString().trim();
        assertTrue("测试文本要超过单次上限", text.length() > 500);

        java.util.List<String> chunks = Translator.splitChunks(text, Translator.MAX_QUERY_CHARS);
        assertTrue("必须切成多段，实际 " + chunks.size(), chunks.size() > 1);
        StringBuilder joined = new StringBuilder();
        for (String c : chunks) {
            assertTrue("每段不得超过上限：" + c.length(), c.length() <= Translator.MAX_QUERY_CHARS);
            assertTrue("每段不得为空", !c.isEmpty());
            joined.append(c);
        }
        assertEquals("切段必须可无损还原原文（否则译文会丢字）", text, joined.toString());
    }

    @Test
    public void splitChunks_hardSplitsSentenceWithoutPunctuation() {
        // 没有标点的长串（OCR 偶发）必须硬切，不能整段超限
        String text = new String(new char[1000]).replace('\0', 'a');
        java.util.List<String> chunks = Translator.splitChunks(text, 450);
        assertEquals(3, chunks.size());
        assertEquals(450, chunks.get(0).length());
        assertEquals(100, chunks.get(2).length());
    }

    @Test
    public void splitChunks_shortTextStaysOneChunk() {
        java.util.List<String> chunks = Translator.splitChunks("Hello world.", 450);
        assertEquals(1, chunks.size());
        assertEquals("Hello world.", chunks.get(0));
        assertTrue("空文本不产生分段", Translator.splitChunks("   ", 450).isEmpty());
        assertTrue("null 不产生分段", Translator.splitChunks(null, 450).isEmpty());
    }

    @Test
    public void errorMarkers_includeQueryLengthLimit() {
        // 真机报的就是这句；必须被识别成错误，而不是当译文显示出来
        assertTrue(Translator.isErrorText(
                "QUERY LENGTH LIMIT EXCEEDED. MAX ALLOWED QUERY : 500 CHARS"));
    }


    @Test
    public void errorRes_mapsToReadableChinese() {
        // 真机报的英文原文必须映射成可读中文，不能直接抛给用户
        assertEquals(com.rd.englishcoach.R.string.grab_translate_too_long,
                Translator.errorRes("translation error: QUERY LENGTH LIMIT EXCEEDED. MAX ALLOWED QUERY : 500 CHARS"));
        assertEquals(com.rd.englishcoach.R.string.grab_translate_empty,
                Translator.errorRes("empty text"));
        assertEquals(com.rd.englishcoach.R.string.grab_translate_network,
                Translator.errorRes("HTTP 502: bad gateway"));
        assertEquals(com.rd.englishcoach.R.string.grab_translate_network,
                Translator.errorRes("java.net.SocketTimeoutException: timeout"));
        assertEquals(com.rd.englishcoach.R.string.grab_translate_other,
                Translator.errorRes("something weird"));
    }

}
