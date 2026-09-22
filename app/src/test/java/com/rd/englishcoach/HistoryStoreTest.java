package com.rd.englishcoach;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.Assert.*;

/**
 * HistoryStore 的单元测试：验证历史持久化逻辑。
 * 使用 TemporaryFolder + 反射绕过 Android Context 依赖。
 */
public class HistoryStoreTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private File historyFile;
    private HistoryStore store;

    @Before
    public void setUp() throws Exception {
        historyFile = new File(tempFolder.getRoot(), "transcript_history.json");
        store = createStoreWithFile(historyFile);
    }

    // ── appendTranscript ────────────────────

    @Test
    public void appendTranscript_savesTranscriptOnly() {
        store.appendTranscript("Hello world");
        List<HistoryStore.Entry> entries = store.getAll();
        assertEquals(1, entries.size());
        assertEquals("Hello world", entries.get(0).transcript);
        assertNull(entries.get(0).answer);
    }

    @Test
    public void appendTranscript_multipleEntries() {
        store.appendTranscript("First");
        store.appendTranscript("Second");
        store.appendTranscript("Third");
        List<HistoryStore.Entry> entries = store.getAll();
        assertEquals(3, entries.size());
        assertEquals("First", entries.get(0).transcript);
        assertEquals("Second", entries.get(1).transcript);
        assertEquals("Third", entries.get(2).transcript);
    }

    // ── append (with answer) ────────────────

    @Test
    public void append_withAnswer() {
        store.append("What time is it?", "It's 3 o'clock.");
        List<HistoryStore.Entry> entries = store.getAll();
        assertEquals(1, entries.size());
        assertEquals("What time is it?", entries.get(0).transcript);
        assertEquals("It's 3 o'clock.", entries.get(0).answer);
    }

    // ── updateLastAnswer ────────────────────

    @Test
    public void updateLastAnswer_addsAnswerToLastEntry() {
        store.appendTranscript("Hello");
        store.updateLastAnswer("Hi there!");
        List<HistoryStore.Entry> entries = store.getAll();
        assertEquals(1, entries.size());
        assertEquals("Hello", entries.get(0).transcript);
        assertEquals("Hi there!", entries.get(0).answer);
    }

    @Test
    public void updateLastAnswer_emptyHistory_noCrash() {
        // 不应该崩溃
        store.updateLastAnswer("answer");
        assertEquals(0, store.getAll().size());
    }

    @Test
    public void updateLastAnswer_multipleEntries_updatesLastOnly() {
        store.appendTranscript("First");
        store.appendTranscript("Second");
        store.updateLastAnswer("Second answer");
        List<HistoryStore.Entry> entries = store.getAll();
        assertEquals(2, entries.size());
        assertNull("第一条不应被修改", entries.get(0).answer);
        assertEquals("Second answer", entries.get(1).answer);
    }

    // ── deleteAt ──────────────────────────────

    @Test
    public void deleteAt_removesEntry() {
        store.appendTranscript("A");
        store.appendTranscript("B");
        store.appendTranscript("C");
        store.deleteAt(1); // 删 B
        List<HistoryStore.Entry> entries = store.getAll();
        assertEquals(2, entries.size());
        assertEquals("A", entries.get(0).transcript);
        assertEquals("C", entries.get(1).transcript);
    }

    @Test
    public void deleteAt_outOfBounds_noCrash() {
        store.appendTranscript("A");
        store.deleteAt(99);
        assertEquals(1, store.getAll().size());
    }

    @Test
    public void deleteAt_negative_noCrash() {
        store.appendTranscript("A");
        store.deleteAt(-1);
        assertEquals(1, store.getAll().size());
    }

    // ── clear ──────────────────────────────

    @Test
    public void clear_removesAll() {
        store.appendTranscript("A");
        store.appendTranscript("B");
        store.clear();
        assertEquals(0, store.getAll().size());
    }

    // ── size ──────────────────────────────

    @Test
    public void size_reflectsEntries() {
        assertEquals(0, store.size());
        store.appendTranscript("A");
        assertEquals(1, store.size());
        store.appendTranscript("B");
        assertEquals(2, store.size());
    }

    // ── getRecent ──────────────────────────

    @Test
    public void getRecent_returnsNewestFirst() {
        store.appendTranscript("A");
        store.appendTranscript("B");
        store.appendTranscript("C");
        List<HistoryStore.Entry> recent = store.getRecent(2);
        assertEquals(2, recent.size());
        assertEquals("C", recent.get(0).transcript);
        assertEquals("B", recent.get(1).transcript);
    }

    @Test
    public void getRecent_moreThanAvailable() {
        store.appendTranscript("A");
        List<HistoryStore.Entry> recent = store.getRecent(10);
        assertEquals(1, recent.size());
        assertEquals("A", recent.get(0).transcript);
    }

    // ── 持久化 ──────────────────────────────

    @Test
    public void entries_persistAcrossInstances() throws Exception {
        store.appendTranscript("Persistent");
        // 创建新的 HistoryStore 实例（模拟重启）
        HistoryStore store2 = createStoreWithFile(historyFile);
        List<HistoryStore.Entry> entries = store2.getAll();
        assertEquals(1, entries.size());
        assertEquals("Persistent", entries.get(0).transcript);
    }

    // ── 类型（转录 / 取词 分开，历史对话框分 Tab） ──

    @Test
    public void appendTranscript_typeIsTranscript() {
        store.appendTranscript("Hello");
        HistoryStore.Entry e = store.getAll().get(0);
        assertEquals(HistoryStore.TYPE_TRANSCRIPT, e.type);
        assertTrue(e.isTranscript());
        assertFalse(e.isGrab());
    }

    @Test
    public void appendGrab_typeIsGrab_andKeepsSourceAndTranslation() {
        store.appendGrab("Hello world", "你好世界");
        HistoryStore.Entry e = store.getAll().get(0);
        assertEquals(HistoryStore.TYPE_GRAB, e.type);
        assertTrue(e.isGrab());
        assertEquals("Hello world", e.transcript);
        assertEquals("你好世界", e.answer);
    }

    @Test
    public void grabAndTranscript_areSeparatedByType() {
        store.appendTranscript("listen text");
        store.appendGrab("grab src", "grab dst");
        store.appendTranscript("listen text 2");
        List<HistoryStore.Entry> all = store.getAll();
        assertEquals(3, all.size());
        assertEquals(2, HistoryStore.countByType(all, HistoryStore.TYPE_TRANSCRIPT));
        assertEquals(1, HistoryStore.countByType(all, HistoryStore.TYPE_GRAB));
        // 顺序不变（旧→新），删除仍可用原索引
        assertEquals("listen text", all.get(0).transcript);
        assertEquals("grab src", all.get(1).transcript);
        assertEquals("listen text 2", all.get(2).transcript);
    }

    @Test
    public void deleteAt_afterMixedAppend_removesRightEntry() {
        store.appendTranscript("listen");
        store.appendGrab("src", "dst");
        store.deleteAt(1); // 删取词那条
        List<HistoryStore.Entry> all = store.getAll();
        assertEquals(1, all.size());
        assertTrue("剩下的应是听力转录", all.get(0).isTranscript());
        assertEquals(0, HistoryStore.countByType(all, HistoryStore.TYPE_GRAB));
    }

    @Test
    public void type_persistsAcrossInstances() throws Exception {
        store.appendGrab("src", "dst");
        HistoryStore store2 = createStoreWithFile(historyFile);
        HistoryStore.Entry e = store2.getAll().get(0);
        assertEquals(HistoryStore.TYPE_GRAB, e.type);
        assertTrue(e.isGrab());
    }

    @Test
    public void legacyGrabEntry_withoutTypeField_isInferredAsGrab() throws Exception {
        // v3.0 之前取词记录只有 "[取词] … → …" 前缀，没有 type 字段
        writeRawJson("[{\"ts\":1,\"transcript\":\"[取词] hello → 你好\"}]");
        HistoryStore store2 = createStoreWithFile(historyFile);
        List<HistoryStore.Entry> all = store2.getAll();
        assertEquals(1, all.size());
        assertTrue("旧取词记录必须归到取词 Tab", all.get(0).isGrab());
        assertEquals(1, HistoryStore.countByType(all, HistoryStore.TYPE_GRAB));
    }

    @Test
    public void legacyTranscriptEntry_withoutTypeField_staysTranscript() throws Exception {
        writeRawJson("[{\"ts\":1,\"transcript\":\"Reading aloud part A\",\"answer\":\"Sure.\"}]");
        HistoryStore store2 = createStoreWithFile(historyFile);
        HistoryStore.Entry e = store2.getAll().get(0);
        assertTrue(e.isTranscript());
        assertEquals("Sure.", e.answer);
    }

    @Test
    public void entry_inferType_handlesNullAndPlainText() {
        assertEquals(HistoryStore.TYPE_TRANSCRIPT, HistoryStore.Entry.inferType(null));
        assertEquals(HistoryStore.TYPE_TRANSCRIPT, HistoryStore.Entry.inferType(""));
        assertEquals(HistoryStore.TYPE_TRANSCRIPT, HistoryStore.Entry.inferType("plain"));
        assertEquals(HistoryStore.TYPE_GRAB, HistoryStore.Entry.inferType("[取词] x → y"));
    }

    @Test
    public void countByType_nullTypeCountsZero() {
        store.appendTranscript("A");
        assertEquals(0, HistoryStore.countByType(store.getAll(), null));
        assertEquals(0, HistoryStore.countByType(store.getAll(), HistoryStore.TYPE_GRAB));
    }

    /** 直接写原始 JSON 到历史文件（模拟旧版本数据）。 */
    private void writeRawJson(String json) throws Exception {
        try (java.io.FileWriter fw = new java.io.FileWriter(historyFile)) {
            fw.write(json);
        }
    }

    // ── 工具方法 ──────────────────────────────

    /**
     * 通过 Unsafe 创建 HistoryStore 实例（绕过需要 Context 的构造函数），
     * 然后用反射设置 file 字段指向临时文件。
     */
    private HistoryStore createStoreWithFile(File file) throws Exception {
        // 使用 Unsafe 分配实例，不调用构造函数
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        java.lang.reflect.Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
        HistoryStore s = (HistoryStore) allocateInstance.invoke(unsafe, HistoryStore.class);

        // 设置 final File 字段
        Field fileField = HistoryStore.class.getDeclaredField("file");
        fileField.setAccessible(true);
        fileField.set(s, file);

        return s;
    }
}
