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
import java.util.List;

/**
 * 本地音视频文件转录的持久化。
 *
 * <p>为什么不复用 {@link HistoryStore}：文件转录一小时会产生上千段带时间轴的记录，
 * 体量与结构都和「听力转录（一段一句话）」不同；混在一个数组里既撑大文件，
 * 又会让「清空历史」误删另一侧数据。所以单独一个 JSON、单独一套状态流转。</p>
 *
 * <p>⚠️ 落盘节流 2 秒：转录过程中每识别完一段就调 {@link #save(Entry)}，
 * 一小时文件约 120 段，逐段写盘会让 UI 线程外的 IO 抖动；节流后崩溃最多丢 2 秒结果。
 * 状态发生终结性变化（done/failed/canceled）时必须走 {@link #saveNow(Entry)}，
 * 否则「已完成的记录」可能还躺在内存里没落盘。</p>
 *
 * <p>⚠️ 写盘先写 {@code .tmp} 再 rename：直接覆盖原文件，进程在写到一半时被杀会得到
 * 一个截断的 JSON，下次读出来就是「历史全丢」。rename 在同一文件系统内是原子的。</p>
 */
public final class FileTranscriptStore {

    private static final String TAG = "FileTranscriptStore";    private static final String FILE_NAME = "file_transcripts.json";
    /** 条目上限，超出删最旧的（一小时转录约 8KB，200 条约 1.6MB 上限）。 */
    static final int MAX_ENTRIES = 200;
    /** 落盘节流窗口。 */
    static final long SAVE_THROTTLE_MS = 2000;

    // ── 任务状态 ──────────────────────────

    /** 已入队、还没开始识别。 */
    public static final String STATUS_PENDING = "pending";
    /** 正在识别。 */
    public static final String STATUS_RUNNING = "running";
    /** 全部识别完成。 */
    public static final String STATUS_DONE = "done";
    /** 识别失败（原因在 {@link Entry#error}）。 */
    public static final String STATUS_FAILED = "failed";
    /** 用户主动取消（已完成的分段保留）。 */
    public static final String STATUS_CANCELED = "canceled";
    /** 进程被杀 / 前台服务超时被系统停掉（可「继续」）。 */
    public static final String STATUS_INTERRUPTED = "interrupted";

    /** 是否处于「可以继续」的中间态。 */
    public static boolean isResumable(String status) {
        return STATUS_INTERRUPTED.equals(status) || STATUS_CANCELED.equals(status)
                || STATUS_FAILED.equals(status) || STATUS_RUNNING.equals(status)
                || STATUS_PENDING.equals(status);
    }

    /** 是否为终结态（不会再自己变化）。 */
    public static boolean isTerminal(String status) {
        return STATUS_DONE.equals(status);
    }

    /** 一段识别结果，时间轴是相对源文件的毫秒。 */
    public static final class Segment {
        public final long startMs;
        public final long endMs;
        public final String text;

        public Segment(long startMs, long endMs, String text) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text == null ? "" : text;
        }

        public JSONObject toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("s", startMs);
                o.put("e", endMs);
                o.put("t", text);
                return o;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        static Segment fromJson(JSONObject o) {
            return new Segment(o.optLong("s", 0), o.optLong("e", 0), o.optString("t", ""));
        }
    }

    /** 一条文件转录记录。字段可变：转录过程中会不断更新进度与追加分段。 */
    public static final class Entry {
        public final String id;
        public final String fileName;
        /** 源文件的 content URI（已 takePersistableUriPermission，重启后仍可「继续」）。 */
        public final String sourceUri;
        /** 音频总时长，未知时为 0；解码器打开后才能拿到，所以不是 final。 */
        public long durationMs;
        public final long sizeBytes;
        public final long createdAt;
        public long updatedAt;
        public String status = STATUS_PENDING;
        /** 已识别到的源文件时间位置（用于断点续转）。 */
        public long progressMs;
        /** 失败原因（面向用户的可读文案）。 */
        public String error;
        /** 实际生效的引擎描述（如「离线 SenseVoice」/「在线 dashscope」），仅用于展示。 */
        public String engine;
        /** 本次识别是否带时间轴（在线协议只有纯文本 → false，此时不提供 SRT 导出）。 */
        public boolean srtAvailable;
        public final List<Segment> segments = new ArrayList<>();

        public Entry(String id, String fileName, String sourceUri, long durationMs, long sizeBytes) {
            this.id = id;
            this.fileName = fileName == null ? "" : fileName;
            this.sourceUri = sourceUri == null ? "" : sourceUri;
            this.durationMs = durationMs;
            this.sizeBytes = sizeBytes;
            this.createdAt = System.currentTimeMillis();
            this.updatedAt = this.createdAt;
        }

        /** 拼接全部分段的文本（分段之间按原有的换行语义连接）。 */
        public String fullText() {
            StringBuilder sb = new StringBuilder();
            for (Segment s : segments) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(s.text);
            }
            return sb.toString();
        }

        /** 文本字数（不含空白），列表页展示用。 */
        public int charCount() {
            int n = 0;
            for (Segment s : segments) {
                for (int i = 0; i < s.text.length(); i++) {
                    if (!Character.isWhitespace(s.text.charAt(i))) n++;
                }
            }
            return n;
        }

        /** 已出字概率进度（0~1）；时长未知时退回 0。 */
        public float progress() {
            if (durationMs <= 0) return STATUS_DONE.equals(status) ? 1f : 0f;
            float p = (float) progressMs / (float) durationMs;
            return Math.max(0f, Math.min(1f, p));
        }

        public JSONObject toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("id", id);
                o.put("fileName", fileName);
                o.put("sourceUri", sourceUri);
                o.put("durationMs", durationMs);
                o.put("sizeBytes", sizeBytes);
                o.put("createdAt", createdAt);
                o.put("updatedAt", updatedAt);
                o.put("status", status);
                o.put("progressMs", progressMs);
                o.put("srtAvailable", srtAvailable);
                if (error != null) o.put("error", error);
                if (engine != null) o.put("engine", engine);
                JSONArray segs = new JSONArray();
                for (Segment s : segments) segs.put(s.toJson());
                o.put("segments", segs);
                return o;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        static Entry fromJson(JSONObject o) {
            Entry e = new Entry(
                    o.optString("id", ""),
                    o.optString("fileName", ""),
                    o.optString("sourceUri", ""),
                    o.optLong("durationMs", 0),
                    o.optLong("sizeBytes", 0));
            e.updatedAt = o.optLong("updatedAt", e.createdAt);
            e.status = o.optString("status", STATUS_PENDING);
            e.progressMs = o.optLong("progressMs", 0);
            e.srtAvailable = o.optBoolean("srtAvailable", false);
            e.error = o.has("error") ? o.optString("error", null) : null;
            e.engine = o.has("engine") ? o.optString("engine", null) : null;
            JSONArray segs = o.optJSONArray("segments");
            if (segs != null) {
                for (int i = 0; i < segs.length(); i++) {
                    JSONObject so = segs.optJSONObject(i);
                    if (so != null) e.segments.add(Segment.fromJson(so));
                }
            }
            return e;
        }
    }

    private final File file;
    private long lastWriteMs;
    /** 内存镜像；null = 尚未从文件加载。所有读写都走它，节流只影响「何时落盘」。 */
    private List<Entry> cache;
    /** 上次加载/落盘时的文件快照（mtime + 长度），用来发现「别的实例写了盘」。 */
    private long stampMtime = -1;
    private long stampLen = -1;

    public FileTranscriptStore(Context ctx) {
        this(new File(ctx.getFilesDir(), FILE_NAME));
    }

    /** 测试用（纯 JVM 下没有 Context）。 */
    FileTranscriptStore(File file) {
        this.file = file;
    }

    /**
     * 出错日志的兜底。
     * ⚠️ 单元测试跑在纯 JVM 上，`android.util.Log` 是未实现的桩，调用会抛
     * RuntimeException("not mocked")——直接调会把「坏文件降级」这类正确行为测成崩溃。
     */
    private static void logE(String msg, Throwable t) {
        try {
            Log.e(TAG, msg, t);
        } catch (Throwable ignored) {
            // 纯 JVM 单元测试环境，无需日志
        }
    }

    private synchronized List<Entry> cache() {
        if (cache == null || diskChanged()) reload();
        return cache;
    }

    /**
     * 盘上文件是否被别的实例改过 —— 服务写转录结果、界面读列表是两个 {@link FileTranscriptStore} 实例。
     *
     * <p>⚠️ 不比对就会出真机踩过的 bug：历史页的文件 Tab 在 App 启动时取了快照，
     * 之后服务转完写盘，界面拿着旧快照永远显示「文件（0）」、列表空白，
     * 而通知栏已经报了「转录完成」。</p>
     */
    private boolean diskChanged() {
        if (file == null) return false;
        return file.lastModified() != stampMtime || file.length() != stampLen;
    }

    /** 从文件重建内存镜像（坏文件就当空的，下次写入自愈）。 */
    private void reload() {
        List<Entry> loaded = new ArrayList<>();
        JSONArray arr = rawArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            try {
                loaded.add(Entry.fromJson(o));
            } catch (Exception e) {
                logE("skip bad entry", e);
            }
        }
        cache = loaded;
        markLoaded();
    }

    /** 记住当前盘上文件的快照，作为「有没有被别人改过」的基准。 */
    private void markLoaded() {
        if (file == null) return;
        stampMtime = file.lastModified();
        stampLen = file.length();
    }

    /** 全部记录（从旧到新）。返回副本，调用方改动不会影响内存镜像。 */
    public synchronized List<Entry> getAll() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : cache()) out.add(Entry.fromJson(e.toJson()));
        return out;
    }

    /** 从新到旧。 */
    public synchronized List<Entry> getRecent() {
        List<Entry> all = getAll();
        List<Entry> out = new ArrayList<>(all.size());
        for (int i = all.size() - 1; i >= 0; i--) out.add(all.get(i));
        return out;
    }

    /** 按 id 查找，找不到返回 null。返回副本。 */
    public synchronized Entry find(String id) {
        if (id == null) return null;
        for (Entry e : cache()) {
            if (id.equals(e.id)) return Entry.fromJson(e.toJson());
        }
        return null;
    }

    /** 新增或更新一条记录（节流落盘）。 */
    public synchronized void save(Entry entry) {
        upsert(entry);
        writeArray(false);
    }

    /** 新增或更新并立即落盘（终结态/退出前用）。 */
    public synchronized void saveNow(Entry entry) {
        upsert(entry);
        writeArray(true);
    }

    /** 忽略节流立刻落盘（服务 onDestroy / onTimeout 前调用）。 */
    public synchronized void flush() {
        writeArray(true);
    }

    /** 删除指定 id 的记录。 */
    public synchronized void delete(String id) {
        if (id == null) return;
        List<Entry> list = cache();
        for (int i = 0; i < list.size(); i++) {
            if (id.equals(list.get(i).id)) {
                list.remove(i);
                writeArray(true);
                return;
            }
        }
    }

    /** 清空全部文件转录记录。 */
    public synchronized void clear() {
        cache = new ArrayList<>();
        writeArray(true);
    }

    /** 记录条数。 */
    public synchronized int size() {
        return cache().size();
    }

    private void upsert(Entry entry) {
        if (entry == null) return;
        entry.updatedAt = System.currentTimeMillis();
        // 存深拷：写方（转录线程）会持续往同一个对象里追加分段，
        // 直接存引用的话读方（UI 线程）序列化时会撞上并发修改
        Entry copy = Entry.fromJson(entry.toJson());
        List<Entry> list = cache();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(copy.id)) {
                list.set(i, copy);
                return;
            }
        }
        list.add(copy);
        // 超出上限删最旧的
        while (list.size() > MAX_ENTRIES) list.remove(0);
    }

    // ── 文件读写 ──────────────────────────

    private static JSONArray toArray(List<Entry> all) {
        JSONArray arr = new JSONArray();
        for (Entry e : all) arr.put(e.toJson());
        return arr;
    }

    private JSONArray rawArray() {
        if (file == null || !file.exists()) return new JSONArray();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return new JSONArray(sb.toString());
        } catch (Exception e) {
            // 坏文件自愈：返回空列表，下次 save 会把文件整个覆盖掉
            logE("readArray failed", e);
            return new JSONArray();
        }
    }

    /** @param force true = 忽略节流立刻写 */
    private void writeArray(boolean force) {
        if (file == null) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastWriteMs < SAVE_THROTTLE_MS) return;
        lastWriteMs = now;
        File tmp = new File(file.getAbsolutePath() + ".tmp");
        try (FileWriter fw = new FileWriter(tmp)) {
            fw.write(toArray(cache()).toString());
        } catch (IOException e) {
            logE("write tmp failed", e);
            return;
        }
        if (!tmp.renameTo(file)) {
            // 目标已存在时 rename 在部分文件系统上会失败，退回「删除后重命名」
            if (file.delete() && tmp.renameTo(file)) {
                markLoaded();
                return;
            }
            logE("rename failed", null);
            return;
        }
        markLoaded();   // 自己刚写的快照：下次 cache() 不必把自己重读一遍
    }
}
