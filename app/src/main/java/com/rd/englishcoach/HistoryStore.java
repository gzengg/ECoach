package com.rd.englishcoach;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 转录历史持久化。
 * 每次暂停 ASR 后自动追加保存到 JSON 文件。
 * 主界面可读取全部历史记录。
 */
public final class HistoryStore {

    private static final String TAG = "HistoryStore";
    private static final String FILE_NAME = "transcript_history.json";

    /** 条目类型：听力转录。 */
    public static final String TYPE_TRANSCRIPT = "transcript";
    /** 条目类型：取词翻译。 */
    public static final String TYPE_GRAB = "grab";
    /** 旧版取词记录靠这个前缀区分（v3.0 把类型写进了 transcript）。 */
    static final String LEGACY_GRAB_PREFIX = "[取词]";

    private final File file;

    /** 一条历史记录 */
    public static class Entry {
        public final long timestamp;
        public final String transcript;
        public final String answer;  // may be null
        /** {@link #TYPE_TRANSCRIPT} 或 {@link #TYPE_GRAB}。 */
        public final String type;

        public Entry(long timestamp, String transcript, String answer, String type) {
            this.timestamp = timestamp;
            this.transcript = transcript;
            this.answer = answer;
            this.type = type == null ? inferType(transcript) : type;
        }

        /** 兼容旧调用（按内容推断类型）。 */
        public Entry(long timestamp, String transcript, String answer) {
            this(timestamp, transcript, answer, inferType(transcript));
        }

        /** 是否取词记录。 */
        public boolean isGrab() { return TYPE_GRAB.equals(type); }

        /** 是否听力转录记录。 */
        public boolean isTranscript() { return TYPE_TRANSCRIPT.equals(type); }

        /**
         * 推断类型：v3.0 之前取词记录没有 type 字段，只能看
         * {@code [取词] } 前缀；否则算听力转录。
         */
        static String inferType(String transcript) {
            return (transcript != null && transcript.startsWith(LEGACY_GRAB_PREFIX))
                    ? TYPE_GRAB : TYPE_TRANSCRIPT;
        }

        public JSONObject toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("ts", timestamp);
                o.put("transcript", transcript);
                o.put("type", type);
                if (answer != null) o.put("answer", answer);
                return o;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        public static Entry fromJson(JSONObject o) {
            String transcript = o.optString("transcript", "");
            String type = o.has("type") ? o.optString("type", null) : null;
            return new Entry(
                    o.optLong("ts", 0),
                    transcript,
                    o.has("answer") ? o.optString("answer", null) : null,
                    type
            );
        }
    }

    public HistoryStore(Context ctx) {
        this.file = new File(ctx.getFilesDir(), FILE_NAME);
    }

    /** 追加一条转录记录 */
    public synchronized void append(String transcript, String answer) {
        appendEntry(transcript, answer, TYPE_TRANSCRIPT);
    }

    /** 追加一条转录记录（无答案） */
    public synchronized void appendTranscript(String transcript) {
        appendEntry(transcript, null, TYPE_TRANSCRIPT);
    }

    /**
     * 追加一条取词记录（原文 + 译文）。
     * 与听力转录分开存 type，历史对话框按类型分 Tab 展示。
     */
    public synchronized void appendGrab(String source, String translated) {
        appendEntry(source, translated, TYPE_GRAB);
    }

    private synchronized void appendEntry(String transcript, String answer, String type) {
        try {
            JSONArray arr = readArray();
            JSONObject entry = new JSONObject();
            entry.put("ts", System.currentTimeMillis());
            entry.put("transcript", transcript);
            entry.put("type", type);
            if (answer != null) entry.put("answer", answer);
            arr.put(entry);
            writeArray(arr);
        } catch (Exception e) {
            Log.e(TAG, "append failed", e);
        }
    }

    /** 更新最后一条记录的答案（ASR 后再获取 AI 回答时调用） */
    public synchronized void updateLastAnswer(String answer) {
        try {
            JSONArray arr = readArray();
            if (arr.length() > 0) {
                JSONObject last = arr.getJSONObject(arr.length() - 1);
                last.put("answer", answer);
                writeArray(arr);
            }
        } catch (Exception e) {
            Log.e(TAG, "updateLastAnswer failed", e);
        }
    }

    /** 获取全部历史（从旧到新） */
    public synchronized List<Entry> getAll() {
        List<Entry> result = new ArrayList<>();
        try {
            JSONArray arr = readArray();
            for (int i = 0; i < arr.length(); i++) {
                result.add(Entry.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception e) {
            Log.e(TAG, "getAll failed", e);
        }
        return result;
    }

    /** 统计某类型的条数（历史对话框 Tab 上的计数用）。 */
    public static int countByType(List<Entry> all, String type) {
        int n = 0;
        for (Entry e : all) {
            if (type == null ? e.type == null : type.equals(e.type)) n++;
        }
        return n;
    }

    /** 获取最近 N 条（从新到旧） */
    public synchronized List<Entry> getRecent(int count) {
        List<Entry> all = getAll();
        if (all.size() <= count) {
            Collections.reverse(all);
            return all;
        }
        List<Entry> recent = new ArrayList<>(all.subList(all.size() - count, all.size()));
        Collections.reverse(recent);
        return recent;
    }

    /** 清空全部历史 */
    public synchronized void clear() {
        writeArray(new JSONArray());
    }

    /** 删除指定索引的条目 */
    public synchronized void deleteAt(int index) {
        try {
            JSONArray arr = readArray();
            if (index >= 0 && index < arr.length()) {
                arr.remove(index);
                writeArray(arr);
            }
        } catch (Exception e) {
            Log.e(TAG, "deleteAt failed", e);
        }
    }

    /** 获取历史条数 */
    public synchronized int size() {
        return readArray().length();
    }

    // ── 文件读写 ──────────────────────────

    private JSONArray readArray() {
        if (!file.exists()) return new JSONArray();
        try {
            BufferedReader br = new BufferedReader(new FileReader(file));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return new JSONArray(sb.toString());
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void writeArray(JSONArray arr) {
        try {
            FileWriter fw = new FileWriter(file);
            fw.write(arr.toString());
            fw.close();
        } catch (IOException e) {
            Log.e(TAG, "writeArray failed", e);
        }
    }
}
