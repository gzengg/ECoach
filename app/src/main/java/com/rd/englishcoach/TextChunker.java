package com.rd.englishcoach;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本分段：把长文本切成「每段不超过上限」的片段，**优先在句末标点/换行处下刀**。
 *
 * <p><b>为什么需要：</b>两个下游接口都有单次长度限制——</p>
 * <ul>
 *   <li>MyMemory 翻译单次查询上限 500 字符（真机：取词 580 字符整段发送必失败）；</li>
 *   <li>mimo 在线朗读长文本会失败（真机：1000 词直接报错，兜底到系统朗读也失败）。</li>
 * </ul>
 *
 * <p>切出来的片段**拼起来 == 原文**（无损）：翻译要按原文排版拼回去，朗读要按顺序连播。</p>
 *
 * <p>纯函数，无 Android 依赖，可直接单测。</p>
 */
final class TextChunker {

    private TextChunker() {}

    /**
     * 切段。每段 ≤ {@code maxChars}；不超长时原样返回单段。
     * 单句自身超长（无标点的长串）才硬切。
     */
    static List<String> split(String text, int maxChars) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return chunks;
        if (maxChars <= 0 || text.length() <= maxChars) {
            chunks.add(text);
            return chunks;
        }
        StringBuilder cur = new StringBuilder();
        for (String sentence : sentences(text)) {
            if (sentence.length() > maxChars) {
                if (cur.length() > 0) {
                    chunks.add(cur.toString());
                    cur.setLength(0);
                }
                for (int i = 0; i < sentence.length(); i += maxChars) {
                    chunks.add(sentence.substring(i, Math.min(sentence.length(), i + maxChars)));
                }
                continue;
            }
            if (cur.length() > 0 && cur.length() + sentence.length() > maxChars) {
                chunks.add(cur.toString());
                cur.setLength(0);
            }
            cur.append(sentence);
        }
        if (cur.length() > 0) chunks.add(cur.toString());
        return chunks;
    }

    /**
     * 按句末标点 / 换行切分，**分隔符跟在前一段尾部**（拼接可还原原文）。
     *
     * <p>英文句点后是数字（{@code 3.5}）或缩写时会多切一刀，只是多一次请求，不影响正确性。</p>
     */
    static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '.' || c == '!' || c == '?' || c == ';'
                    || c == '。' || c == '！' || c == '？' || c == '；') {
                out.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length()) out.add(text.substring(start));
        return out;
    }
}
