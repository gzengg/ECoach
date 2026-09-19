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
    private final File file;

    /** 一条历史记录 */
    public static class Entry {
        public final long timestamp;
        public final String transcript;
        public final String answer;  // may be null

        public Entry(long timestamp, String transcript, String answer) {
            this.timestamp = timestamp;
            this.transcript = transcript;
            this.answer = answer;
        }

        public JSONObject toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("ts", timestamp);
                o.put("transcript", transcript);
                if (answer != null) o.put("answer", answer);
                return o;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        public static Entry fromJson(JSONObject o) {
            return new Entry(
                    o.optLong("ts", 0),
                    o.optString("transcript", ""),
                    o.has("answer") ? o.optString("answer", null) : null
            );
        }
    }

    public HistoryStore(Context ctx) {
        this.file = new File(ctx.getFilesDir(), FILE_NAME);
    }

    /** 追加一条转录记录 */
    public synchronized void append(String transcript, String answer) {
        try {
            JSONArray arr = readArray();
            JSONObject entry = new JSONObject();
            entry.put("ts", System.currentTimeMillis());
            entry.put("transcript", transcript);
            if (answer != null) entry.put("answer", answer);
            arr.put(entry);
            writeArray(arr);
        } catch (Exception e) {
            Log.e(TAG, "append failed", e);
        }
    }

    /** 追加一条转录记录（无答案） */
    public synchronized void appendTranscript(String transcript) {
        append(transcript, null);
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
