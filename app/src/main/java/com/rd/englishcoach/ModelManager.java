package com.rd.englishcoach;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 离线模型的安装层：下载 / 校验 / 删除 / 空间预检 / 本地导入。
 *
 * <p><b>状态从文件系统推导，不另存一份</b>：装没装看模型目录里有没有 {@link #MARKER}
 * 与载荷文件。这样用户清数据、换手机、手动拷目录都不会出现「设置里说装了、其实文件没了」的假状态。</p>
 *
 * <p><b>下载链路必须健壮</b>：模型 150MB~1GB，官方源在墙内时好时坏（实测同一 URL 时而 206/2s、时而超时）。
 * 所以：多源按顺序试 + 断点续传 + 单源失败不中断 + 本地导入兜底。</p>
 *
 * <p><b>解包支持 zip 与 tar.bz2</b>（按魔数判，不看扩展名）：上游 sherpa-onnx 只发 {@code .tar.bz2}，
 * 而 JDK 只有 zip/gzip，所以用 commons-compress 解 bzip2。两者都做路径校验防 Zip Slip。</p>
 */
final class ModelManager {

    private static final String TAG = "ModelManager";

    /** 安装状态。DOWNLOADING 只存在于本次进程内（磁盘上无法表示"正在下"）。 */
    enum State { NOT_INSTALLED, DOWNLOADING, INSTALLED }

    /** 安装标记文件；存在且目录里还有载荷文件 = 已安装。 */
    static final String MARKER = ".installed";
    /** 下载中的临时文件后缀，用于断点续传。 */
    private static final String PART = ".part";
    /** 临时载荷名：下载完成后先落地到这里，再按内容分流。 */
    private static final String STAGING = ".staging";
    /** 空间余量：模型体积之外还要留 20%，避免下载中途写满。 */
    private static final double SPACE_MARGIN = 1.2;
    /** 单源建连超时：源是多条按顺序试，单源卡太久会把总时长拖到不可接受。 */
    private static final int CONNECT_TIMEOUT_MS = 8_000;

    interface Listener {
        /** 状态变化（<b>主线程</b>回调）。 */
        void onStateChanged(ModelCatalog.Spec spec);
        /** 进度（<b>主线程</b>回调），total &lt;= 0 表示服务端没给长度。 */
        void onProgress(ModelCatalog.Spec spec, long done, long total);
        /** 失败（<b>主线程</b>回调），message 已是可读文案（含每个源各自的原因）。 */
        void onError(ModelCatalog.Spec spec, String message);
    }

    /** 下载串行化：避免几个大文件抢带宽。 */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** 本次进程内正在下载的模型 id。 */
    private final Set<String> inFlight = new HashSet<>();

    ModelManager(Context c) {
        this.appContext = c.getApplicationContext();
    }

    // ── 目录与路径（纯函数，可脱离 Android 单测） ──────────────

    /** 模型根目录：优先外部私有目录（用户可自行拷入导出），不可用则退回内部私有目录。 */
    File baseDir() {
        File ext = appContext.getExternalFilesDir("models");
        return ext != null ? ext : new File(appContext.getFilesDir(), "models");
    }

    /** 单个模型的目录。 */
    static File dirFor(File base, ModelCatalog.Spec spec) {
        return new File(base, spec.id);
    }

    /** 载荷文件名（相对路径的最后一段）。 */
    static String payloadName(ModelCatalog.Spec spec) {
        String rel = spec.relativePath;
        int slash = rel.lastIndexOf('/');
        return slash >= 0 ? rel.substring(slash + 1) : rel;
    }

    /** 载荷文件（单文件模型用；多文件模型解包后不保留原包）。 */
    static File payloadFile(File base, ModelCatalog.Spec spec) {
        return new File(dirFor(base, spec), payloadName(spec));
    }

    /** 是否已安装：标记 + 目录里至少一个非标记文件。不依赖具体文件名（多文件模型也能认）。 */
    static boolean isInstalled(File base, ModelCatalog.Spec spec) {
        File dir = dirFor(base, spec);
        if (!new File(dir, MARKER).isFile()) return false;
        return hasAnyPayload(dir);
    }

    /** 目录里除标记/临时文件外是否还有东西。 */
    static boolean hasAnyPayload(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            String n = f.getName();
            if (!MARKER.equals(n) && !n.endsWith(PART) && !n.endsWith(".tmp")) return true;
        }
        return false;
    }

    /**
     * 在模型目录里找引擎要加载的 ONNX 文件。
     *
     * <p>为什么不写死文件名：上游各模型的命名不统一（Whisper 是 {@code turbo-encoder.int8.onnx}，
     * SenseVoice 是 {@code model.int8.onnx}）。约定「优先 model.onnx，否则第一个 *.onnx」比逼用户改名可靠。</p>
     */
    static File findOnnx(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        File first = null;
        for (File f : files) {
            if (!f.isFile() || !f.getName().endsWith(".onnx")) continue;
            if ("model.onnx".equals(f.getName())) return f;
            if (first == null) first = f;
        }
        return first;
    }

    /**
     * 目录里第一个文件名含 {@code keyword} 的 onnx（Whisper 的 encoder/decoder 靠它找）。
     *
     * <p><b>优先 int8</b>：上游 whisper 包里同时带 fp32 与 int8 两份，
     * 按目录顺序先撞上 fp32 的话体积与耗时都差好几倍。</p>
     */
    static File findByName(File dir, String keyword) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        String key = keyword.toLowerCase(Locale.US);
        File first = null;
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.US);
            if (!f.isFile() || !n.endsWith(".onnx") || !n.contains(key)) continue;
            if (n.contains(".int8.")) return f;
            if (first == null) first = f;
        }
        return first;
    }

    /**
     * 找 tokens 文件。
     *
     * <p>⚠️ 上游命名不统一：SenseVoice / Piper 叫 {@code tokens.txt}，
     * **Whisper 叫 {@code <名字>-tokens.txt}**（如 {@code turbo-tokens.txt} / {@code large-v3-tokens.txt}）。
     * 只认精确的 {@code tokens.txt} 会让**三个 Whisper 档位全部装不上**
     * （真机报「模型不完整：包里缺少 tokens.txt」，下得下来、装不上）。
     * 所以按后缀认，并优先精确名。</p>
     *
     * @return tokens 文件，找不到返回 null
     */
    static File findTokens(File dir) {
        File exact = new File(dir, "tokens.txt");
        if (exact.isFile()) return exact;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase(Locale.US).endsWith("tokens.txt")) return f;
        }
        return null;
    }

    /**
     * 校验模型目录里是否真有引擎需要的文件；返回缺少的东西（可读名称），合格返回 null。
     *
     * <p>VAD 只要 {@code *.onnx}（silero 没有 tokens）；识别与朗读要 {@code *.onnx} + tokens。
     * 没这道校验的话「随便导入个 zip」也会提示成功，变成装上看不了、还得先手动删的坏状态。</p>
     */
    static String missingRequirement(File dir, ModelCatalog.Spec spec) {
        if (findOnnx(dir) == null) return "*.onnx";
        // 识别与朗读都需要 tokens（Piper 也有）；VAD（silero）没有
        if (spec.kind != ModelCatalog.Kind.VAD && findTokens(dir) == null) {
            return "tokens.txt 或 *-tokens.txt";
        }
        return null;
    }

    /**
     * 解包后如果是「只有一层子目录」的结构（如 {@code sherpa-onnx-whisper-turbo/encoder.onnx}），
     * 把这一层的内容提到模型目录根下，让引擎按统一路径读取。
     *
     * @return 是否拍平过
     */
    static boolean flattenSingleRoot(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length != 1) return false;
        File only = files[0];
        if (!only.isDirectory()) return false;
        File[] inner = only.listFiles();
        if (inner == null) return false;
        for (File f : inner) {
            if (!f.renameTo(new File(dir, f.getName()))) return false;
        }
        return only.delete();
    }

    /** 把多源设置拆成候选基址（逗号 / 空白分隔，去空、去尾部斜杠、去重保序）。 */
    static List<String> parseSources(String setting) {
        List<String> out = new ArrayList<>();
        if (setting == null) return out;
        for (String raw : setting.split("[,\\s]+")) {
            String s = raw.trim();
            while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
            if (!s.isEmpty() && !out.contains(s)) out.add(s);
        }
        return out;
    }

    /** 候选下载地址（按基址顺序，路径为 {@code 基址/相对路径}）。 */
    static List<String> candidateUrls(String setting, ModelCatalog.Spec spec) {
        List<String> out = new ArrayList<>();
        for (String base : parseSources(setting)) {
            out.add(base + "/" + spec.relativePath);
        }
        return out;
    }

    /** 剩余空间是否够装（含 20% 余量）。 */
    static boolean hasSpace(long usableBytes, ModelCatalog.Spec spec) {
        return usableBytes > (long) (spec.sizeBytes * SPACE_MARGIN);
    }

    /** 人类可读体积（UI 与日志共用，避免各处各写一套换算）。 */
    static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.0f MB", bytes / 1048576.0);
        }
        return String.format(Locale.US, "%.1f GB", bytes / 1073741824.0);
    }

    /** 是否 zip（看魔数 PK，不靠文件名——用户导入的文件名往往已被改过）。 */
    static boolean isZip(File f) {
        try (InputStream in = new BufferedInputStream(new java.io.FileInputStream(f))) {
            return in.read() == 'P' && in.read() == 'K';
        } catch (IOException e) {
            return false;
        }
    }

    /** 是否 bzip2（魔数 BZh，不看扩展名）。 */
    static boolean isTarBz2(File f) {
        try (InputStream in = new BufferedInputStream(new java.io.FileInputStream(f))) {
            return in.read() == 'B' && in.read() == 'Z' && in.read() == 'h';
        } catch (IOException e) {
            return false;
        }
    }

    // ── 查询 ──────────────────────────────────────────────

    State stateOf(ModelCatalog.Spec spec) {
        if (inFlight.contains(spec.id)) return State.DOWNLOADING;
        return isInstalled(baseDir(), spec) ? State.INSTALLED : State.NOT_INSTALLED;
    }

    /** 模型目录（引擎从这里加载；不存在时返回路径但不创建）。 */
    File modelDir(ModelCatalog.Spec spec) {
        return dirFor(baseDir(), spec);
    }

    /** 已安装模型的总体积（UI 显示占用用）。 */
    long installedBytes() {
        long sum = 0;
        for (ModelCatalog.Spec s : ModelCatalog.all()) {
            if (isInstalled(baseDir(), s)) sum += dirSize(modelDir(s));
        }
        return sum;
    }

    long usableBytes() {
        return baseDir().getUsableSpace();
    }

    /** 缺哪个依赖没装；都装了返回 null。 */
    String missingDependency(ModelCatalog.Spec spec) {
        Set<String> installed = new HashSet<>();
        for (ModelCatalog.Spec s : ModelCatalog.all()) {
            if (isInstalled(baseDir(), s)) installed.add(s.id);
        }
        return ModelCatalog.firstMissingDependency(spec, installed);
    }

    private static long dirSize(File dir) {
        long sum = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) sum += f.isDirectory() ? dirSize(f) : f.length();
        return sum;
    }

    // ── 安装 / 删除 ────────────────────────────────────────

    /** 下载安装。前置校验（依赖、空间、下载源）同步做完，失败立刻回调，不进 IO 线程。 */
    void install(final ModelCatalog.Spec spec, final String sourceSetting, final Listener l) {
        String missing = missingDependency(spec);
        if (missing != null) {
            ModelCatalog.Spec dep = ModelCatalog.byId(missing);
            l.onError(spec, appContext.getString(R.string.model_err_need_dep,
                    dep != null ? appContext.getString(dep.labelRes) : missing));
            return;
        }
        if (!hasSpace(usableBytes(), spec)) {
            l.onError(spec, appContext.getString(R.string.model_err_no_space,
                    formatSize(spec.sizeBytes), formatSize(usableBytes())));
            return;
        }
        final List<String> urls = candidateUrls(sourceSetting, spec);
        if (urls.isEmpty()) {
            l.onError(spec, appContext.getString(R.string.model_err_no_source));
            return;
        }

        inFlight.add(spec.id);
        l.onStateChanged(spec);
        IO.execute(() -> {
            // 逐个源尝试，并记下每个源失败的原因——只报最后一个的话，
            // 用户（和排查的人）根本不知道是哪个源、因为什么挂的。
            List<String> failures = new ArrayList<>();
            boolean anyNotFound = false;
            boolean ok = false;
            for (String url : urls) {
                try {
                    downloadTo(url, spec, l);
                    ok = true;
                    break;
                } catch (IOException | RuntimeException e) {
                    String reason = e.getMessage() != null ? e.getMessage() : e.toString();
                    if (reason.contains("404")) anyNotFound = true;
                    failures.add(url + "\n   → " + reason);
                    Log.w(TAG, "源失败 " + url + " : " + reason);
                }
            }
            final boolean success = ok;
            final boolean sawNotFound = anyNotFound;
            final String detail = String.join("\n", failures);
            inFlight.remove(spec.id);
            main.post(() -> {
                l.onStateChanged(spec);
                if (!success) {
                    l.onError(spec, appContext.getString(
                            R.string.model_err_download_all, failures.size(), detail)
                            + (sawNotFound ? appContext.getString(R.string.model_err_404_note) : ""));
                }
            });
        });
    }

    /** 从本地文件导入（content:// 或 file://）：zip / tar.bz2 自动解包，单文件直接当载荷。 */
    void installFromFile(final ModelCatalog.Spec spec, final Uri src, final Listener l) {
        String missing = missingDependency(spec);
        if (missing != null) {
            ModelCatalog.Spec dep = ModelCatalog.byId(missing);
            l.onError(spec, appContext.getString(R.string.model_err_need_dep,
                    dep != null ? appContext.getString(dep.labelRes) : missing));
            return;
        }
        if (!hasSpace(usableBytes(), spec)) {
            l.onError(spec, appContext.getString(R.string.model_err_no_space,
                    formatSize(spec.sizeBytes), formatSize(usableBytes())));
            return;
        }
        inFlight.add(spec.id);
        l.onStateChanged(spec);
        IO.execute(() -> {
            String err = null;
            try {
                File dir = dirFor(baseDir(), spec);
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建模型目录");
                File staged = new File(dir, STAGING + PART);
                try (InputStream in = appContext.getContentResolver().openInputStream(src);
                     OutputStream out = new FileOutputStream(staged)) {
                    if (in == null) throw new IOException("无法读取所选文件");
                    copy(in, out, null);
                }
                finalizeInstall(spec, staged);
            } catch (IOException | RuntimeException e) {
                err = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final String message = err;
            inFlight.remove(spec.id);
            main.post(() -> {
                l.onStateChanged(spec);
                if (message != null) {
                    l.onError(spec, appContext.getString(R.string.model_err_import, message));
                }
            });
        });
    }

    /** 删除单个模型（整个目录一起删，含残留的 .part）。 */
    void delete(ModelCatalog.Spec spec) {
        deleteRecursively(dirFor(baseDir(), spec));
    }

    // ── 内部实现 ──────────────────────────────────────────

    private void downloadTo(String url, ModelCatalog.Spec spec, Listener l) throws IOException {
        File dir = dirFor(baseDir(), spec);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建模型目录");
        File part = new File(dir, STAGING + PART);

        long existing = part.isFile() ? part.length() : 0;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(30_000);
            if (existing > 0) conn.setRequestProperty("Range", "bytes=" + existing + "-");

            int code = conn.getResponseCode();
            // 带 Range 被拒（416/404 等）：多半是残留的 .part 与当前源对不上
            // （上次从另一个源下的半截文件）。删掉重下一次，而不是直接报错。
            if (code >= 400 && existing > 0) {
                conn.disconnect();
                if (!part.delete()) part.deleteOnExit();
                existing = 0;
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(30_000);
                code = conn.getResponseCode();
            }
            if (code >= 400) throw new IOException("HTTP " + code);
            // 服务端不支持断点（200 而非 206）→ 从头下，避免拼出坏文件
            boolean append = existing > 0 && code == 206;
            if (!append) existing = 0;

            long total = existing + conn.getContentLength();
            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 OutputStream out = new FileOutputStream(part, append)) {
                copy(in, out, new Progress(l, spec, existing, total, main));
            }

            if (spec.needsVerify() && !spec.sha256.equalsIgnoreCase(sha256(part))) {
                // 摘要不符：删掉坏文件，别让引擎加载到半个模型
                if (!part.delete()) part.deleteOnExit();
                throw new IOException("校验失败（文件损坏或源不对）");
            }
            if (part.length() <= 0) throw new IOException("下载内容为空");

            finalizeInstall(spec, part);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 把落地的临时文件变成可用的模型目录。
     *
     * <p><b>先解到临时目录、校验通过再替换正式目录</b>：直接往正式目录解会 ① 留垃圾文件，
     * ② 导入失败把原来装好的模型一起弄坏。</p>
     */
    private void finalizeInstall(ModelCatalog.Spec spec, File staged) throws IOException {
        File dir = dirFor(baseDir(), spec);
        File tmp = new File(baseDir(), spec.id + ".tmp");
        deleteRecursively(tmp);
        if (!tmp.mkdirs()) throw new IOException("无法创建临时目录");
        try {
            if (spec.isSingleFilePayload()) {
                // 单文件载荷（VAD 的 silero_vad.onnx）：**原样落盘**，绝不能当压缩包解。
                // 先放到 tmp 里，这样下面「校验 → 替换」的公共流程照常用得上。
                if (staged.length() <= 0) throw new IOException("文件是空的");
                if (!staged.renameTo(new File(tmp, payloadName(spec)))) {
                    throw new IOException("无法写入模型文件");
                }
            } else if (isZip(staged)) {
                try (InputStream in = new BufferedInputStream(new java.io.FileInputStream(staged))) {
                    extractZip(in, tmp);
                }
            } else if (isTarBz2(staged)) {
                try (InputStream in = new BufferedInputStream(new java.io.FileInputStream(staged))) {
                    extractTarBz2(in, tmp);
                }
            } else {
                // 不是归档：多文件模型拿到单文件会变成「装上看不了」，当场拦下
                throw new IOException(appContext.getString(R.string.model_err_need_archive,
                        spec.relativePath));
            }
            flattenSingleRoot(tmp);

            String missing = missingRequirement(tmp, spec);
            if (missing != null) {
                throw new IOException(appContext.getString(R.string.model_err_incomplete, missing));
            }
            deleteRecursively(dir);
            if (!tmp.renameTo(dir)) throw new IOException("无法替换模型目录");
        } finally {
            deleteRecursively(tmp);   // 成功后已改名，这里是无操作；失败时清干净
        }
        if (!staged.delete()) staged.deleteOnExit();
        writeMarker(spec);
    }

    /** 解包 zip 到目录。条目名可能越界（Zip Slip），用 {@link #safeChild} 拦。 */
    static void extractZip(InputStream in, File dir) throws IOException {
        try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(in)) {
            java.util.zip.ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                File out = safeChild(dir, e.getName());
                if (out == null) throw new IOException("压缩包含非法路径：" + e.getName());
                if (e.isDirectory()) {
                    if (!out.isDirectory() && !out.mkdirs()) throw new IOException("无法创建目录");
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("无法创建目录");
                }
                try (OutputStream os = new FileOutputStream(out)) {
                    copy(zin, os, null);
                }
            }
        }
    }

    /** 解 tar.bz2 到目录（上游 sherpa-onnx 的实际格式）。同样做路径校验。 */
    static void extractTarBz2(InputStream in, File dir) throws IOException {
        try (org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream bz =
                     new org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(in);
             org.apache.commons.compress.archivers.tar.TarArchiveInputStream tar =
                     new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(bz)) {
            org.apache.commons.compress.archivers.tar.TarArchiveEntry e;
            while ((e = tar.getNextEntry()) != null) {
                File out = safeChild(dir, e.getName());
                if (out == null) throw new IOException("压缩包含非法路径：" + e.getName());
                if (e.isDirectory()) {
                    if (!out.isDirectory() && !out.mkdirs()) throw new IOException("无法创建目录");
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("无法创建目录");
                }
                try (OutputStream os = new FileOutputStream(out)) {
                    copy(tar, os, null);
                }
            }
        }
    }

    /** 把压缩包条目名映射到目录内；越界（{@code ../} 或规范化后跑到外面）返回 null。 */
    static File safeChild(File dir, String entryName) throws IOException {
        String name = entryName.replace('\\', '/');
        File out = new File(dir, name);
        String base = dir.getCanonicalPath();
        String path = out.getCanonicalPath();
        if (path.equals(base) || path.startsWith(base + File.separator)) return out;
        return null;
    }

    /** 进度节流：每 512KB 回调一次，避免主线程被刷爆。 */
    private static final class Progress {
        final Listener l; final ModelCatalog.Spec spec; final long total;
        final Handler main;
        long lastNotified;

        Progress(Listener l, ModelCatalog.Spec spec, long start, long total, Handler main) {
            this.l = l; this.spec = spec; this.total = total; this.main = main;
            this.lastNotified = start;
        }

        void maybe(long done) {
            if (done - lastNotified < 512 * 1024 && done != total) return;
            lastNotified = done;
            final long d = done;
            // ⚠️ 必须回主线程：进度是在 IO 线程里算出来的，而接收方要改 ProgressBar/TextView。
            // 直接回调会 CalledFromWrongThreadException——下载一开始就挂（真机实测踩过）。
            main.post(() -> l.onProgress(spec, d, total));
        }
    }

    private static void copy(InputStream in, OutputStream out, Progress p) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long done = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            done += n;
            if (p != null) p.maybe(done);
        }
        out.flush();
        if (p != null) p.maybe(done);
    }

    private static String sha256(File f) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("设备不支持 SHA-256");
        }
        try (InputStream in = new BufferedInputStream(new java.io.FileInputStream(f))) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private void writeMarker(ModelCatalog.Spec spec) throws IOException {
        File dir = dirFor(baseDir(), spec);
        File marker = new File(dir, MARKER);
        String content = spec.id + "\n" + spec.sha256 + "\n" + dirSize(dir) + "\n";
        try (FileOutputStream out = new FileOutputStream(marker)) {
            out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursively(c);
        }
        if (!f.delete()) f.deleteOnExit();
    }
}
