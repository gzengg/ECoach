package com.rd.englishcoach;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.util.List;

import static org.junit.Assert.*;

/**
 * FileTranscriptStore 的单元测试：持久化、节流、上限与状态辅助。
 * 用 TemporaryFolder 直接构造（该 Store 有包内 File 构造器，不需要 Context）。
 */
public class FileTranscriptStoreTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private File jsonFile;
    private FileTranscriptStore store;

    @Before
    public void setUp() throws Exception {
        jsonFile = new File(tempFolder.getRoot(), "file_transcripts.json");
        store = new FileTranscriptStore(jsonFile);
    }

    private FileTranscriptStore.Entry entry(String id) {
        return new FileTranscriptStore.Entry(id, id + ".mp4", "content://media/" + id, 60_000, 1024);
    }

    // ── 基本往返 ────────────────────────────

    @Test
    public void save_thenGetAll_roundTripsAllFields() {
        FileTranscriptStore.Entry e = entry("a");
        e.status = FileTranscriptStore.STATUS_DONE;
        e.progressMs = 60_000;
        e.srtAvailable = true;
        e.engine = "离线 SenseVoice";
        e.segments.add(new FileTranscriptStore.Segment(0, 3000, "Hello"));
        e.segments.add(new FileTranscriptStore.Segment(3000, 6000, "world"));
        store.saveNow(e);

        List<FileTranscriptStore.Entry> all = store.getAll();
        assertEquals(1, all.size());
        FileTranscriptStore.Entry got = all.get(0);
        assertEquals("a", got.id);
        assertEquals("a.mp4", got.fileName);
        assertEquals("content://media/a", got.sourceUri);
        assertEquals(60_000, got.durationMs);
        assertEquals(1024, got.sizeBytes);
        assertEquals(FileTranscriptStore.STATUS_DONE, got.status);
        assertEquals(60_000, got.progressMs);
        assertTrue(got.srtAvailable);
        assertEquals("离线 SenseVoice", got.engine);
        assertEquals(2, got.segments.size());
        assertEquals("Hello", got.segments.get(0).text);
        assertEquals(3000, got.segments.get(1).startMs);
        assertEquals(6000, got.segments.get(1).endMs);
    }

    @Test
    public void save_sameIdTwice_replacesInsteadOfDuplicating() {
        FileTranscriptStore.Entry e = entry("a");
        e.segments.add(new FileTranscriptStore.Segment(0, 1000, "one"));
        store.saveNow(e);

        e.segments.add(new FileTranscriptStore.Segment(1000, 2000, "two"));
        e.progressMs = 2000;
        store.saveNow(e);

        List<FileTranscriptStore.Entry> all = store.getAll();
        assertEquals("同 id 必须替换而不是追加", 1, all.size());
        assertEquals(2, all.get(0).segments.size());
        assertEquals(2000, all.get(0).progressMs);
    }

    @Test
    public void save_newEntryKeepsInsertionOrder() {
        store.saveNow(entry("a"));
        store.saveNow(entry("b"));
        store.saveNow(entry("c"));
        List<FileTranscriptStore.Entry> all = store.getAll();
        assertEquals("a", all.get(0).id);
        assertEquals("b", all.get(1).id);
        assertEquals("c", all.get(2).id);
    }

    @Test
    public void getRecent_returnsNewestFirst() {
        store.saveNow(entry("a"));
        store.saveNow(entry("b"));
        List<FileTranscriptStore.Entry> recent = store.getRecent();
        assertEquals("b", recent.get(0).id);
        assertEquals("a", recent.get(1).id);
    }

    @Test
    public void find_returnsEntryOrNull() {
        store.saveNow(entry("a"));
        assertNotNull(store.find("a"));
        assertNull(store.find("nope"));
        assertNull(store.find(null));
    }

    // ── 节流 ────────────────────────────────

    @Test
    public void save_rapidSecondWrite_isThrottledOnDiskButFlushPersistsIt() throws Exception {
        store.saveNow(entry("a"));   // 立即写，并把节流起点设为 now
        store.save(entry("b"));      // 2 秒内 → 不落盘
        assertEquals("节流窗口内不应写盘", 1, diskEntryCount());
        assertEquals("内存镜像应已包含新条目", 2, store.getAll().size());

        store.flush();
        assertEquals("flush 必须把内存里的改动补写回去", 2, diskEntryCount());
    }

    /** 直接数落盘 JSON 里的条目数（节流只影响磁盘，看 getAll 看不出区别）。 */
    private int diskEntryCount() throws Exception {
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(jsonFile))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return new org.json.JSONArray(sb.toString()).length();
    }

    @Test
    public void saveNow_ignoresThrottle() {
        store.saveNow(entry("a"));
        store.saveNow(entry("b"));
        assertEquals(2, store.getAll().size());
    }

    // ── 删除 / 清空 ──────────────────────────

    @Test
    public void delete_removesOnlyThatId() {
        store.saveNow(entry("a"));
        store.saveNow(entry("b"));
        store.delete("a");
        List<FileTranscriptStore.Entry> all = store.getAll();
        assertEquals(1, all.size());
        assertEquals("b", all.get(0).id);
    }

    @Test
    public void delete_unknownId_leavesDataIntact() {
        store.saveNow(entry("a"));
        store.delete("nope");
        assertEquals(1, store.getAll().size());
    }

    @Test
    public void clear_emptiesEverything() {
        store.saveNow(entry("a"));
        store.saveNow(entry("b"));
        store.clear();
        assertEquals(0, store.size());
        assertTrue(store.getAll().isEmpty());
    }

    // ── 上限 ────────────────────────────────

    @Test
    public void save_overMaxEntries_dropsOldest() {
        int n = FileTranscriptStore.MAX_ENTRIES + 5;
        for (int i = 0; i < n; i++) store.saveNow(entry("id" + i));

        List<FileTranscriptStore.Entry> all = store.getAll();
        assertEquals(FileTranscriptStore.MAX_ENTRIES, all.size());
        assertEquals("最旧的应被丢弃", "id5", all.get(0).id);
        assertEquals("id" + (n - 1), all.get(all.size() - 1).id);
    }

    @Test
    public void size_matchesGetAll() {
        store.saveNow(entry("a"));
        store.saveNow(entry("b"));
        assertEquals(store.getAll().size(), store.size());
    }

    // ── 坏数据 ──────────────────────────────

    @Test
    public void getAll_corruptJson_returnsEmptyInsteadOfThrowing() throws Exception {
        try (FileWriter fw = new FileWriter(jsonFile)) {
            fw.write("{ this is not json");
        }
        assertTrue("坏文件必须降级成空列表，不能让历史页崩", store.getAll().isEmpty());
    }

    @Test
    public void save_afterCorruptFile_overwritesIt() throws Exception {
        try (FileWriter fw = new FileWriter(jsonFile)) {
            fw.write("{ this is not json");
        }
        store.saveNow(entry("a"));
        assertEquals("坏文件要能自愈", 1, new FileTranscriptStore(jsonFile).size());
    }

    @Test
    public void getAll_entryMissingOptionalFields_usesDefaults() throws Exception {
        try (FileWriter fw = new FileWriter(jsonFile)) {
            fw.write("[{\"id\":\"x\",\"fileName\":\"x.mp3\",\"status\":\"done\"}]");
        }
        FileTranscriptStore.Entry e = store.getAll().get(0);
        assertEquals("done", e.status);
        assertEquals(0, e.durationMs);
        assertFalse(e.srtAvailable);
        assertNull(e.error);
        assertTrue(e.segments.isEmpty());
    }

    @Test
    public void missingFile_getAllReturnsEmpty() {
        assertTrue(new FileTranscriptStore(new File(tempFolder.getRoot(), "nope.json"))
                .getAll().isEmpty());
    }

    // ── Entry 辅助 ───────────────────────────

    @Test
    public void fullText_joinsSegmentsWithNewline() {
        FileTranscriptStore.Entry e = entry("a");
        e.segments.add(new FileTranscriptStore.Segment(0, 1000, "第一句"));
        e.segments.add(new FileTranscriptStore.Segment(1000, 2000, "第二句"));
        assertEquals("第一句\n第二句", e.fullText());
    }

    @Test
    public void fullText_emptySegments_isEmptyString() {
        assertEquals("", entry("a").fullText());
    }

    @Test
    public void charCount_ignoresWhitespace() {
        FileTranscriptStore.Entry e = entry("a");
        e.segments.add(new FileTranscriptStore.Segment(0, 1000, "ab cd\nef"));
        assertEquals(6, e.charCount());
    }

    @Test
    public void progress_clampsToOneAndHandlesUnknownDuration() {
        FileTranscriptStore.Entry unknown = new FileTranscriptStore.Entry("a", "a.mp4", "u", 0, 0);
        assertEquals("时长未知且未完成为 0", 0f, unknown.progress(), 0.0001f);

        unknown.status = FileTranscriptStore.STATUS_DONE;
        assertEquals("时长未知但已完成按 1 算", 1f, unknown.progress(), 0.0001f);
    }

    @Test
    public void progress_overDuration_isClampedToOne() {
        FileTranscriptStore.Entry e = new FileTranscriptStore.Entry("a", "a.mp4", "u", 1000, 0);
        e.progressMs = 5000;
        // 超过时长也不能算出 >1 的进度
        assertEquals(1f, e.progress(), 0.0001f);
    }

    @Test
    public void progress_midway() {
        FileTranscriptStore.Entry e = new FileTranscriptStore.Entry("a", "a.mp4", "u", 10_000, 0);
        e.progressMs = 2500;
        assertEquals(0.25f, e.progress(), 0.0001f);
    }

    // ── 状态辅助 ─────────────────────────────

    @Test
    public void isTerminal_onlyDone() {
        assertTrue(FileTranscriptStore.isTerminal(FileTranscriptStore.STATUS_DONE));
        assertFalse(FileTranscriptStore.isTerminal(FileTranscriptStore.STATUS_RUNNING));
        assertFalse(FileTranscriptStore.isTerminal(FileTranscriptStore.STATUS_FAILED));
        assertFalse(FileTranscriptStore.isTerminal(FileTranscriptStore.STATUS_CANCELED));
        assertFalse(FileTranscriptStore.isTerminal(FileTranscriptStore.STATUS_INTERRUPTED));
        assertFalse(FileTranscriptStore.isTerminal(FileTranscriptStore.STATUS_PENDING));
    }

    @Test
    public void isResumable_coversIntermediateAndRetryableStates() {
        assertTrue(FileTranscriptStore.isResumable(FileTranscriptStore.STATUS_INTERRUPTED));
        assertTrue(FileTranscriptStore.isResumable(FileTranscriptStore.STATUS_CANCELED));
        assertTrue(FileTranscriptStore.isResumable(FileTranscriptStore.STATUS_FAILED));
        assertTrue(FileTranscriptStore.isResumable(FileTranscriptStore.STATUS_RUNNING));
        assertTrue(FileTranscriptStore.isResumable(FileTranscriptStore.STATUS_PENDING));
        assertFalse(FileTranscriptStore.isResumable(FileTranscriptStore.STATUS_DONE));
    }

    // ── 持久化跨实例 ─────────────────────────

    @Test
    public void data_survivesNewInstance() {
        FileTranscriptStore.Entry e = entry("a");
        e.segments.add(new FileTranscriptStore.Segment(0, 1000, "hi"));
        store.saveNow(e);

        FileTranscriptStore reopened = new FileTranscriptStore(jsonFile);
        List<FileTranscriptStore.Entry> all = reopened.getAll();
        assertEquals(1, all.size());
        assertEquals("hi", all.get(0).segments.get(0).text);
    }

    @Test
    public void save_replacesFileAtomically_noTempLeftBehind() {
        store.saveNow(entry("a"));
        assertFalse("临时文件必须被 rename 掉",
                new File(jsonFile.getAbsolutePath() + ".tmp").exists());
        assertTrue(jsonFile.exists());
    }
}
