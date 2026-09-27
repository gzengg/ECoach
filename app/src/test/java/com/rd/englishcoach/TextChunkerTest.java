package com.rd.englishcoach;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * 文本分段测试。
 *
 * <p>回归背景：翻译（MyMemory 单次 500 字符）与朗读（mimo 长文本失败）
 * 都有单次长度限制，整段发送必失败。</p>
 */
public class TextChunkerTest {

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    /** 1000 词左右的英文回答（约 5500 字符）——真机上就是这种长度朗读失败。 */
    private static String longAnswer() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append("Everything has its place in the grand order. ");
        return sb.toString().trim();
    }

    // ── 长度上限不变量 ────────────────────────────────────

    @Test
    public void ttsChunkLimit_isWellUnderFailingLength() {
        // 真机：1000 词（~5000 字符）整段朗读必失败 → 单次上限必须远小于它
        assertTrue("TTS 单次上限必须远小于失败长度（5000 字符），实际 " + OnlineTtsEngine.MAX_TTS_CHARS,
                OnlineTtsEngine.MAX_TTS_CHARS <= 500);
        // 翻译侧：MyMemory 硬限制 500，本地上限必须留余量
        assertTrue("翻译单次上限必须 < 500，实际 " + Translator.MAX_QUERY_CHARS,
                Translator.MAX_QUERY_CHARS < 500);
    }

    @Test
    public void split_ttsLongAnswer_yieldsManyChunksWithinLimit() {
        String text = longAnswer();
        assertTrue("测试文本要够长，实际 " + text.length(), text.length() > 4000);

        List<String> chunks = TextChunker.split(text, OnlineTtsEngine.MAX_TTS_CHARS);
        assertTrue("长文本必须切多段，实际 " + chunks.size(), chunks.size() > 10);
        StringBuilder joined = new StringBuilder();
        for (String c : chunks) {
            assertTrue("每段不得超过上限：" + c.length(), c.length() <= OnlineTtsEngine.MAX_TTS_CHARS);
            assertFalse("不得出现空段", c.trim().isEmpty());
            joined.append(c);
        }
        assertEquals("切段必须可无损还原（否则朗读会漏字）", text, joined.toString());
    }

    // ── 句子边界 ──────────────────────────────────────────

    @Test
    public void split_prefersSentenceBoundaries() {
        String text = "One two three. Four five six. Seven eight nine.";
        List<String> chunks = TextChunker.split(text, 20);
        assertEquals(3, chunks.size());
        // 每段以句末标点（+空格）结尾，不在句中硬切
        for (int i = 0; i < chunks.size() - 1; i++) {
            assertTrue("应以句末标点结尾，实际「" + chunks.get(i) + "」",
                    chunks.get(i).trim().endsWith("."));
        }
    }

    @Test
    public void split_hardSplitsSentenceWithoutPunctuation() {
        // 无标点的长串（OCR/模型偶发）必须硬切，不能整段超限
        List<String> chunks = TextChunker.split(repeat('a', 1000), 300);
        assertEquals(4, chunks.size());
        assertEquals(300, chunks.get(0).length());
        assertEquals(100, chunks.get(3).length());
    }

    @Test
    public void split_shortTextStaysOneChunk() {
        List<String> one = TextChunker.split("Hello world.", 300);
        assertEquals(1, one.size());
        assertEquals("Hello world.", one.get(0));
        assertTrue("空文本不产生分段", TextChunker.split("   ", 300).isEmpty());
        assertTrue("null 不产生分段", TextChunker.split(null, 300).isEmpty());
    }

    @Test
    public void split_handlesChinesePunctuation() {
        String text = "第一句。第二句！第三句？";
        List<String> chunks = TextChunker.split(text, 5);
        assertEquals(3, chunks.size());
        assertEquals("第一句。", chunks.get(0));
        assertEquals("第二句！", chunks.get(1));
        assertEquals("第三句？", chunks.get(2));
    }

    @Test
    public void split_preservesNewlines() {
        String text = "line one\nline two\nline three";
        List<String> chunks = TextChunker.split(text, 10);
        StringBuilder joined = new StringBuilder();
        for (String c : chunks) joined.append(c);
        assertEquals("换行必须保留（否则朗读与翻译的排版都乱）", text, joined.toString());
    }

    // ── 翻译侧委托 ────────────────────────────────────────

    @Test
    public void translatorSplitChunks_delegatesToTextChunker() {
        String text = longAnswer();
        assertEquals(TextChunker.split(text, 450), Translator.splitChunks(text, 450));
    }
}
