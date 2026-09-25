package com.rd.englishcoach;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 离线模型清单 / 安装层的纯逻辑测试。
 *
 * <p>真机上的识别效果无法在这里验证（需要设备与模型包），但下面这些一旦错了，表现是
 * 「404 下不到」「随便导入个 zip 也提示成功」「静默掉准确率」，都极难查，所以必须钉住。</p>
 */
public class ModelCatalogTest {

    // ── 清单不变量 ────────────────────────────────────────

    @Test
    public void catalog_idsAreUniqueAndSizesPositive() {
        java.util.Set<String> seen = new HashSet<>();
        for (ModelCatalog.Spec s : ModelCatalog.all()) {
            assertTrue("id 重复：" + s.id, seen.add(s.id));
            assertTrue(s.id + " 体积必须为正（空间预检依赖它）", s.sizeBytes > 0);
            assertTrue(s.id + " 缺少显示名资源", s.labelRes != 0);
            assertTrue(s.id + " 缺少许可资源", s.licenseRes != 0);
            assertTrue(s.id + " 缺少下载相对路径",
                    s.relativePath != null && !s.relativePath.isEmpty());
        }
    }

    @Test
    public void catalog_constantsPointToExistingSpecs() {
        for (String id : new String[]{ModelCatalog.VAD, ModelCatalog.ASR_TURBO,
                ModelCatalog.ASR_SENSEVOICE, ModelCatalog.ASR_LARGE_V3, ModelCatalog.ASR_QWEN3_06B,
                ModelCatalog.TTS_PIPER_EN, ModelCatalog.TTS_PIPER_ZH}) {
            assertNotNull("常量指向了不存在的模型：" + id, ModelCatalog.byId(id));
        }
        assertNull(ModelCatalog.byId("nope"));
        assertNull(ModelCatalog.byId(null));
    }

    @Test
    public void catalog_everyKindHasEntries() {
        for (ModelCatalog.Kind kind : ModelCatalog.Kind.values()) {
            boolean found = false;
            for (ModelCatalog.Spec s : ModelCatalog.all()) {
                if (s.kind == kind) { found = true; break; }
            }
            assertTrue("分类 " + kind + " 没有任何模型", found);
        }
    }

    @Test
    public void catalog_asrDependsOnVad_andNothingElseDoes() {
        for (ModelCatalog.Spec s : ModelCatalog.all()) {
            for (String dep : s.dependsOn) {
                assertNotNull(s.id + " 依赖了不存在的模型：" + dep, ModelCatalog.byId(dep));
            }
            if (s.kind == ModelCatalog.Kind.ASR) {
                assertArrayEquals(s.id + " 必须依赖 VAD（切句质量直接决定识别准确率）",
                        new String[]{ModelCatalog.VAD}, s.dependsOn);
            } else {
                assertEquals(s.id + " 不该有依赖", 0, s.dependsOn.length);
            }
        }
    }

    @Test
    public void catalog_relativePathsMatchUpstreamFilenames() {
        // URL = 基址 + "/" + 文件名，所以文件名必须与上游一字不差，否则就是 404
        assertEquals("sherpa-onnx-whisper-turbo.tar.bz2",
                ModelCatalog.byId(ModelCatalog.ASR_TURBO).relativePath);
        assertEquals("sherpa-onnx-whisper-large-v3.tar.bz2",
                ModelCatalog.byId(ModelCatalog.ASR_LARGE_V3).relativePath);
        assertEquals("sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
                ModelCatalog.byId(ModelCatalog.ASR_SENSEVOICE).relativePath);
        assertEquals("vits-piper-en_US-lessac-medium.tar.bz2",
                ModelCatalog.byId(ModelCatalog.TTS_PIPER_EN).relativePath);
        assertEquals("vits-piper-zh_CN-huayan-medium.tar.bz2",
                ModelCatalog.byId(ModelCatalog.TTS_PIPER_ZH).relativePath);
        // Qwen3：官方 asr-models release 的资产名（838MB / 878702423 字节）
        assertEquals("sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2",
                ModelCatalog.byId(ModelCatalog.ASR_QWEN3_06B).relativePath);
    }

    @Test
    public void catalog_qwen3EntryMatchesOfficialPackage() {
        ModelCatalog.Spec q = ModelCatalog.byId(ModelCatalog.ASR_QWEN3_06B);
        assertEquals(ModelCatalog.Kind.ASR, q.kind);
        assertEquals(ModelCatalog.Family.QWEN3, q.family);
        assertEquals("体积用官方资产实测字节数（空间预检要准）",
                878702423L, q.sizeBytes);
        assertFalse("Qwen3 慢（RTF 约为 turbo 十倍量级），不该占推荐位", q.recommended);
        assertArrayEquals("识别依赖 VAD", new String[]{ModelCatalog.VAD}, q.dependsOn);
        assertFalse("Qwen3 是多文件包，必须走解包分支", q.isSingleFilePayload());
    }

    @Test
    public void catalog_familiesCoverAsrSeriesForGrouping() {
        // 模型页按系列分组：少一个系列就少一个组标题，多一个就多一行
        assertEquals(ModelCatalog.Family.WHISPER,
                ModelCatalog.byId(ModelCatalog.ASR_TURBO).family);
        assertEquals(ModelCatalog.Family.WHISPER,
                ModelCatalog.byId(ModelCatalog.ASR_LARGE_V3).family);
        assertEquals(ModelCatalog.Family.SENSEVOICE,
                ModelCatalog.byId(ModelCatalog.ASR_SENSEVOICE).family);
        assertEquals(ModelCatalog.Family.QWEN3,
                ModelCatalog.byId(ModelCatalog.ASR_QWEN3_06B).family);
        // VAD 是共享依赖，不属任何识别系列（模型页固定放最前、不加组标题）
        assertEquals(ModelCatalog.Family.VAD, ModelCatalog.byId(ModelCatalog.VAD).family);
        for (ModelCatalog.Spec s : ModelCatalog.all()) {
            assertNotNull(s.id + " 缺少系列，模型页会漏掉这一行", s.family);
        }
    }

    @Test
    public void firstMissingDependency_reportsUnmetDependency() {
        ModelCatalog.Spec asr = ModelCatalog.byId(ModelCatalog.ASR_TURBO);
        assertEquals(ModelCatalog.VAD,
                ModelCatalog.firstMissingDependency(asr, new HashSet<>()));
        assertNull(ModelCatalog.firstMissingDependency(asr,
                new HashSet<>(Arrays.asList(ModelCatalog.VAD))));
    }

    // ── 下载源与 URL 拼接 ─────────────────────────────────

    @Test
    public void parseSources_handlesDefaultAndStripsNoise() {
        List<String> def = ModelManager.parseSources(Prefs.DEF_MODEL_BASE_URL);
        assertEquals("默认源：asr-models + tts-models 两条", 2, def.size());
        assertTrue(def.get(0).endsWith("/asr-models"));
        assertTrue(def.get(1).endsWith("/tts-models"));
        for (String b : def) {
            assertFalse("不该残留逗号或空格：" + b, b.contains(",") || b.contains(" "));
            assertTrue("必须是 https：" + b, b.startsWith("https://"));
            assertFalse("不内置任何第三方镜像：" + b,
                    b.contains("gh-proxy") || b.contains("hf-mirror") || b.contains("modelscope"));
        }
        assertTrue(ModelManager.parseSources(null).isEmpty());
        assertTrue(ModelManager.parseSources("  ,, ").isEmpty());
        assertEquals(Arrays.asList("https://a.com/m", "https://b.com/m"),
                ModelManager.parseSources(" https://a.com/m/ ,, https://a.com/m , https://b.com/m "));
    }

    @Test
    public void candidateUrls_produceExactOfficialUrls() {
        // 把「基址 + 文件名」拼出来的完整 URL 钉死：这类拼错的表现就是 404，
        // 而 404 很难一眼看出是哪个字符不对。
        assertEquals(
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
                        + "sherpa-onnx-whisper-turbo.tar.bz2",
                ModelManager.candidateUrls(Prefs.DEF_MODEL_BASE_URL,
                        ModelCatalog.byId(ModelCatalog.ASR_TURBO)).get(0));
        assertEquals(
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"
                        + "vits-piper-zh_CN-huayan-medium.tar.bz2",
                ModelManager.candidateUrls(Prefs.DEF_MODEL_BASE_URL,
                        ModelCatalog.byId(ModelCatalog.TTS_PIPER_ZH)).get(1));
        assertTrue(ModelManager.candidateUrls("", ModelCatalog.byId(ModelCatalog.VAD)).isEmpty());
    }

    @Test
    public void hasSpace_keepsTwentyPercentMargin() {
        ModelCatalog.Spec vad = ModelCatalog.byId(ModelCatalog.VAD);
        assertFalse("刚好等于体积不算够（还要留余量）",
                ModelManager.hasSpace(vad.sizeBytes, vad));
        assertTrue(ModelManager.hasSpace(vad.sizeBytes * 2, vad));
    }

    @Test
    public void formatSize_switchesUnitsAtRightBoundaries() {
        assertEquals("512 B", ModelManager.formatSize(512));
        assertEquals("1 KB", ModelManager.formatSize(1024));
        assertEquals("240 MB", ModelManager.formatSize(240L * 1024 * 1024));
        assertEquals("1.5 GB", ModelManager.formatSize(1536L * 1024 * 1024));
    }

    // ── 安装校验（不能“随便导入个 zip 就提示成功”） ─────────

    @Test
    public void missingRequirement_rejectsJunkPackages() throws IOException {
        ModelCatalog.Spec asr = ModelCatalog.byId(ModelCatalog.ASR_TURBO);
        ModelCatalog.Spec vad = ModelCatalog.byId(ModelCatalog.VAD);
        ModelCatalog.Spec tts = ModelCatalog.byId(ModelCatalog.TTS_PIPER_EN);

        File dir = Files.createTempDirectory("junk").toFile();
        assertTrue(new File(dir, "readme.txt").createNewFile());
        assertEquals("ASR 缺 onnx 必须被拦下", "*.onnx", ModelManager.missingRequirement(dir, asr));
        assertEquals("TTS 缺 onnx 必须被拦下", "*.onnx", ModelManager.missingRequirement(dir, tts));
        assertEquals("VAD 缺 onnx 必须被拦下", "*.onnx", ModelManager.missingRequirement(dir, vad));

        assertTrue(new File(dir, "model.onnx").createNewFile());
        assertEquals("识别缺 tokens 必须被拦下", "tokens.txt 或 *-tokens.txt",
                ModelManager.missingRequirement(dir, asr));
        assertNull("VAD 只要 onnx（silero 没有 tokens.txt）",
                ModelManager.missingRequirement(dir, vad));

        assertTrue(new File(dir, "tokens.txt").createNewFile());
        assertNull("onnx + tokens.txt 才算合格", ModelManager.missingRequirement(dir, asr));
    }

    @Test
    public void findOnnx_prefersModelOnnxThenFirstOnnx() throws IOException {
        File dir = Files.createTempDirectory("onnx").toFile();
        assertNull(ModelManager.findOnnx(dir));
        assertTrue(new File(dir, "turbo-encoder.int8.onnx").createNewFile());
        assertEquals("turbo-encoder.int8.onnx", ModelManager.findOnnx(dir).getName());
        assertTrue(new File(dir, "model.onnx").createNewFile());
        assertEquals("model.onnx 优先级更高（约定名）",
                "model.onnx", ModelManager.findOnnx(dir).getName());
    }

    @Test
    public void findTokens_acceptsWhisperPrefixedName() throws IOException {
        // 回归：Whisper 包的 tokens 叫 <名字>-tokens.txt（turbo-tokens.txt / large-v3-tokens.txt），
        // 只认精确的 tokens.txt 会让三个 Whisper 档位全部装不上（真机报「缺少 tokens.txt」）。
        ModelCatalog.Spec turbo = ModelCatalog.byId(ModelCatalog.ASR_TURBO);
        File dir = Files.createTempDirectory("whisper").toFile();
        assertTrue(new File(dir, "turbo-encoder.int8.onnx").createNewFile());
        assertTrue(new File(dir, "turbo-decoder.int8.onnx").createNewFile());
        assertTrue(new File(dir, "turbo-tokens.txt").createNewFile());
        assertEquals("turbo-tokens.txt", ModelManager.findTokens(dir).getName());
        assertNull("Whisper 包必须能通过安装校验", ModelManager.missingRequirement(dir, turbo));

        // 精确名优先（SenseVoice / Piper 的 tokens.txt）
        File dir2 = Files.createTempDirectory("sense").toFile();
        assertTrue(new File(dir2, "model.int8.onnx").createNewFile());
        assertTrue(new File(dir2, "tokens.txt").createNewFile());
        assertEquals("tokens.txt", ModelManager.findTokens(dir2).getName());
    }

    @Test
    public void findByName_prefersInt8OverFp32() throws IOException {
        // 上游 whisper 包里 fp32 与 int8 同时存在，必须优先 int8（体积/耗时差几倍）
        File dir = Files.createTempDirectory("int8").toFile();
        assertTrue(new File(dir, "turbo-encoder.onnx").createNewFile());
        assertTrue(new File(dir, "turbo-encoder.int8.onnx").createNewFile());
        assertTrue(new File(dir, "turbo-decoder.int8.onnx").createNewFile());
        assertEquals("turbo-encoder.int8.onnx",
                ModelManager.findByName(dir, "encoder").getName());
        assertEquals("turbo-decoder.int8.onnx",
                ModelManager.findByName(dir, "decoder").getName());
    }

    @Test
    public void missingRequirement_acceptsQwen3PackageWithTokenizerDir() throws IOException {
        // 回归：Qwen3 包没有 tokens.txt，tokenizer 是目录（merges.txt + vocab.json）。
        // 按 tokens 规则校验会让它「下得下来、装不上」（与当年 Whisper turbo-tokens.txt 同款坑）。
        ModelCatalog.Spec qwen = ModelCatalog.byId(ModelCatalog.ASR_QWEN3_06B);
        File dir = Files.createTempDirectory("qwen3").toFile();
        assertTrue(new File(dir, "conv_frontend.onnx").createNewFile());
        assertEquals("缺 encoder/decoder 要拦下", "*encoder*.onnx + *decoder*.onnx",
                ModelManager.missingRequirement(dir, qwen));
        assertTrue(new File(dir, "encoder.int8.onnx").createNewFile());
        assertTrue(new File(dir, "decoder.int8.onnx").createNewFile());
        assertEquals("缺 tokenizer 目录要拦下", "tokenizer/（含 merges.txt）",
                ModelManager.missingRequirement(dir, qwen));

        File tok = new File(dir, "tokenizer");
        assertTrue(tok.mkdirs());
        assertTrue(new File(tok, "merges.txt").createNewFile());
        assertNull("conv_frontend + encoder/decoder + tokenizer 才算合格",
                ModelManager.missingRequirement(dir, qwen));
        assertEquals("tokenizer", ModelManager.findTokenizerDir(dir).getName());
        assertNull("非 Qwen3 目录不该被误判",
                ModelManager.findTokenizerDir(Files.createTempDirectory("plain").toFile()));
    }

    @Test
    public void stripPromptPrefix_removesQwen3TemplateArtifacts() {
        // 上游 PR #3399 实测样例：Qwen3 输出带语言标签前缀
        assertEquals("开放时间：早上九点至下午五点。",
                OfflineAsrEngine.stripPromptPrefix(
                        "language Chinese<asr_text>开放时间：早上九点至下午五点。"));
        assertEquals("Hello world.", OfflineAsrEngine.stripPromptPrefix("  Hello world.  "));
        assertEquals("", OfflineAsrEngine.stripPromptPrefix(null));
        assertEquals("你好",
                OfflineAsrEngine.stripPromptPrefix("<|im_start|>你好<|im_end|>"));
    }

    @Test
    public void isInstalled_requiresMarkerAndSomePayload() throws IOException {
        File base = Files.createTempDirectory("base").toFile();
        ModelCatalog.Spec vad = ModelCatalog.byId(ModelCatalog.VAD);
        File dir = ModelManager.dirFor(base, vad);
        assertTrue(dir.mkdirs());
        assertFalse("空目录 = 未安装", ModelManager.isInstalled(base, vad));
        assertTrue(new File(dir, ModelManager.MARKER).createNewFile());
        assertFalse("只有标记（文件被删了）不算装好", ModelManager.isInstalled(base, vad));
        assertTrue(new File(dir, "silero_vad.onnx").createNewFile());
        assertTrue("标记 + 任意载荷文件 = 已安装", ModelManager.isInstalled(base, vad));
    }

    // ── 解包（含路径安全） ────────────────────────────────

    @Test
    public void extractTarBz2_unpacksAndGuardsTraversal() throws IOException {
        File dir = Files.createTempDirectory("tb").toFile();
        byte[] tb = tarBz2Of("model.onnx", "weights", "sub/tokens.txt", "toks");
        assertTrue("按魔数 BZh 识别（不看扩展名）", ModelManager.isTarBz2(bytesToFile(tb)));
        ModelManager.extractTarBz2(new ByteArrayInputStream(tb), dir);
        assertTrue(new File(dir, "model.onnx").isFile());
        assertTrue("子目录要能建", new File(new File(dir, "sub"), "tokens.txt").isFile());

        try {
            ModelManager.extractTarBz2(new ByteArrayInputStream(tarBz2Of("../evil.txt", "x")),
                    Files.createTempDirectory("evil").toFile());
            fail("越界 tar 条目必须被拒绝（Zip Slip）");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("非法路径"));
        }
    }

    @Test
    public void extractZip_unpacksAndGuardsTraversal() throws IOException {
        File dir = Files.createTempDirectory("zip").toFile();
        ModelManager.extractZip(new ByteArrayInputStream(zipOf("a/b.txt", "x")), dir);
        assertTrue(new File(new File(dir, "a"), "b.txt").isFile());
        try {
            ModelManager.extractZip(new ByteArrayInputStream(zipOf("../evil.txt", "x")), dir);
            fail("越界 zip 条目必须被拒绝");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("非法路径"));
        }
    }

    @Test
    public void flattenSingleRoot_hoistsSingleTopLevelDirectory() throws IOException {
        File dir = Files.createTempDirectory("flat").toFile();
        File nested = new File(dir, "sherpa-onnx-whisper-turbo");
        assertTrue(nested.mkdirs());
        assertTrue(new File(nested, "model.onnx").createNewFile());
        assertTrue("单层根目录应被拍平", ModelManager.flattenSingleRoot(dir));
        assertTrue(new File(dir, "model.onnx").isFile());
        assertFalse(nested.exists());
    }

    private static File bytesToFile(byte[] data) throws IOException {
        File f = new File(Files.createTempDirectory("bytes").toFile(), "payload.bin");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
        return f;
    }

    private static byte[] zipOf(String... nameThenContent) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(bos)) {
            for (int i = 0; i < nameThenContent.length; i += 2) {
                zos.putNextEntry(new java.util.zip.ZipEntry(nameThenContent[i]));
                zos.write(nameThenContent[i + 1].getBytes("UTF-8"));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    /** 现场造一个最小 tar.bz2（自包含，不依赖开发机的模型缓存）。 */
    private static byte[] tarBz2Of(String... nameThenContent) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream bz =
                     new org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream(bos);
             org.apache.commons.compress.archivers.tar.TarArchiveOutputStream tar =
                     new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(bz)) {
            for (int i = 0; i < nameThenContent.length; i += 2) {
                byte[] body = nameThenContent[i + 1].getBytes("UTF-8");
                org.apache.commons.compress.archivers.tar.TarArchiveEntry e =
                        new org.apache.commons.compress.archivers.tar.TarArchiveEntry(nameThenContent[i]);
                e.setSize(body.length);
                tar.putArchiveEntry(e);
                tar.write(body);
                tar.closeArchiveEntry();
            }
        }
        return bos.toByteArray();
    }

    // ── 识别链与音频处理 ──────────────────────────────────

    @Test
    public void asrOrder_autoTriesOfflineFirstThenOnline() {
        AsrEngine offline = new FakeEngine("offline");
        AsrEngine online = new FakeEngine("online");
        assertEquals(Arrays.asList(offline, online),
                AsrChain.order(Prefs.MODE_AUTO, offline, online));
        assertEquals("只选在线时不该去加载离线模型",
                Arrays.asList(online), AsrChain.order(Prefs.MODE_ONLINE, offline, online));
        assertEquals("只选离线时不该发网络请求",
                Arrays.asList(offline), AsrChain.order(Prefs.MODE_OFFLINE, offline, online));
    }

    @Test
    public void resample_downsamplesToExpectedLength() {
        assertEquals(1600, OfflineAsrEngine.resample(new float[4800], 48000, 16000).length);
        float[] same = {0.1f, 0.2f};
        assertSame("采样率一致时不该拷贝一遍", same,
                OfflineAsrEngine.resample(same, 16000, 16000));
        assertEquals(0, OfflineAsrEngine.resample(new float[0], 48000, 16000).length);
    }

    @Test
    public void resample_keepsConstantSignalConstant() {
        float[] in = new float[1000];
        Arrays.fill(in, 0.5f);
        for (float v : OfflineAsrEngine.resample(in, 44100, 16000)) {
            assertEquals("直流信号重采样后仍应是同一个值", 0.5f, v, 1e-4f);
        }
    }

    @Test
    public void pcm16ToFloat_mapsFullScaleAndIsLittleEndian() {
        byte[] pcm = {(byte) 0xFF, 0x7F, 0x00, (byte) 0x80, 0x00, 0x00};
        float[] out = OfflineAsrEngine.pcm16ToFloat(pcm);
        assertEquals(3, out.length);
        assertEquals(32767f / 32768f, out[0], 1e-6f);
        assertEquals(-1.0f, out[1], 1e-6f);
        assertEquals(0f, out[2], 1e-6f);
    }

    @Test
    public void toPcm16_clipsAndConvertsLittleEndian() {
        byte[] pcm = SherpaTtsEngine.toPcm16(new float[]{0f, 1f, -1f, 2f, -2f});
        assertEquals(10, pcm.length);
        assertEquals((byte) 0xFF, pcm[2]);
        assertEquals((byte) 0x7F, pcm[3]);
        assertEquals((byte) 0x01, pcm[4]);
        assertEquals((byte) 0x80, pcm[5]);
        assertEquals("超过 1 要削顶", (byte) 0x7F, pcm[7]);
        assertEquals("低于 -1 要削底", (byte) 0x80, pcm[9]);
    }

    @Test
    public void ttsVoiceLists_coverBothLanguagesAndAvoidDuplicates() {
        // 接口实测只有这几个音色，传其它值报 Unknown voice
        assertTrue(Prefs.TTS_VOICES_ZH.length >= 5);
        assertTrue(Prefs.TTS_VOICES_EN.length >= 4);
        for (String v : Prefs.TTS_VOICES_ZH) {
            assertFalse("中文音色里不该混英文声：" + v, Arrays.asList(Prefs.TTS_VOICES_EN).contains(v));
        }
        assertTrue("默认英文声必须在候选里",
                Arrays.asList(Prefs.TTS_VOICES_EN).contains(Prefs.DEF_TTS_VOICE_EN));
        assertTrue("默认中文声必须在候选里",
                Arrays.asList(Prefs.TTS_VOICES_ZH).contains(Prefs.DEF_TTS_VOICE_ZH));
    }

    private static final class FakeEngine implements AsrEngine {
        private final String name;
        FakeEngine(String name) { this.name = name; }
        @Override public boolean isAvailable() { return true; }
        @Override public String transcribe(byte[] pcm, int sampleRate) { return name; }
    }

    // ── 单文件载荷（VAD）回归 ──────────────────────────────

    @Test
    public void vad_isSingleFilePayload_othersAreArchives() {
        // 回归：VAD 是单文件（silero_vad.onnx），收窄范围时漏掉了这条判定，
        // 真机表现是「下得下来、装不上：需要 zip 或 tar.bz2 包」。
        assertTrue("VAD 必须识别为单文件载荷",
                ModelCatalog.byId(ModelCatalog.VAD).isSingleFilePayload());
        for (String id : new String[]{ModelCatalog.ASR_TURBO, ModelCatalog.ASR_SENSEVOICE,
                ModelCatalog.ASR_LARGE_V3, ModelCatalog.ASR_QWEN3_06B,
                ModelCatalog.TTS_PIPER_EN, ModelCatalog.TTS_PIPER_ZH}) {
            assertFalse(id + " 是多文件包，必须走解包分支",
                    ModelCatalog.byId(id).isSingleFilePayload());
        }
    }

    @Test
    public void payloadName_isLastPathSegment() {
        assertEquals("silero_vad.onnx",
                ModelManager.payloadName(ModelCatalog.byId(ModelCatalog.VAD)));
        assertEquals("sherpa-onnx-whisper-turbo.tar.bz2",
                ModelManager.payloadName(ModelCatalog.byId(ModelCatalog.ASR_TURBO)));
    }

}
