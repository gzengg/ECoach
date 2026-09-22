package com.rd.englishcoach;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Scanner;
import org.junit.Test;

/**
 * 取词框选层 v3.1（重做）的行为与契约测试。
 *
 * <p>真机反馈的三个问题：</p>
 * <ol>
 *   <li>框选困难且范围不对应屏幕 → 截图改为等比缩放铺满视图 + 坐标映射；</li>\n *   <li>点空白处会退出应用 → 取消只通过右上角「✕ 取消」，单击空白只清框；</li>
 *   <li>框选完概率闪退 → dismiss 不再 recycle 显示位图（RenderThread 竞态）。</li>
 * </ol>
 * <p>{@link GrabOverlay#mapToBitmap} 是纯函数，直接做行为测试。</p>
 */
public class GrabOverlaySelectionTest {

    // ── mapToBitmap：坐标映射（框选不偏移的核心） ──────

    @Test
    public void mapToBitmap_identityWhenSameSize() {
        // 视图与位图同尺寸：坐标一一对应
        assertArrayEquals(new int[]{100, 200, 300, 400},
                GrabOverlay.mapToBitmap(1080, 2400, 1080, 2400, 100f, 200f, 300f, 400f));
    }

    @Test
    public void mapToBitmap_scalesWhenBitmapSmaller() {
        // 位图是视图的一半 → 换算后的坐标减半
        assertArrayEquals(new int[]{50, 100, 150, 200},
                GrabOverlay.mapToBitmap(1080, 2400, 540, 1200, 100f, 200f, 300f, 400f));
    }

    @Test
    public void mapToBitmap_scalesWhenBitmapLarger() {
        // 位图是视图的两倍 → 换算后的坐标翻倍
        assertArrayEquals(new int[]{200, 400, 600, 800},
                GrabOverlay.mapToBitmap(540, 1200, 1080, 2400, 100f, 200f, 300f, 400f));
    }

    @Test
    public void mapToBitmap_normalizesInvertedDrag() {
        // 用户从右下往左上拖：min/max 归一化
        assertArrayEquals(new int[]{100, 200, 300, 400},
                GrabOverlay.mapToBitmap(1080, 2400, 1080, 2400, 300f, 400f, 100f, 200f));
    }

    @Test
    public void mapToBitmap_clampsOutsideBounds() {
        // 拖到屏幕外（负坐标/越界）：clamp 到位图边界
        assertArrayEquals(new int[]{0, 0, 1080, 2400},
                GrabOverlay.mapToBitmap(1080, 2400, 1080, 2400, -50f, -50f, 2000f, 3000f));
    }

    @Test
    public void mapToBitmap_degenerateViewDoesNotCrash() {
        // 视图尺寸未知（0）时退化为 1:1，不抛异常
        assertArrayEquals(new int[]{10, 20, 30, 40},
                GrabOverlay.mapToBitmap(0, 0, 1080, 2400, 10f, 20f, 30f, 40f));
    }

    @Test
    public void mapToBitmap_resultAlwaysWithinBitmap() {
        int[] m = GrabOverlay.mapToBitmap(1000, 2000, 500, 1000, -10f, -10f, 999f, 1999f);
        assertTrue(m[0] >= 0 && m[1] >= 0);
        assertTrue("right 不得超位图宽", m[2] <= 500);
        assertTrue("bottom 不得超位图高", m[3] <= 1000);
        assertTrue("必须保证非空矩形（right>left, bottom>top）", m[2] > m[0] && m[3] > m[1]);
    }

    // ── snapToEdges：贴边吸附（修「框选内容受限」） ──────

    @Test
    public void snapToEdges_leftNearEdge_snapsToZero() {
        // 从靠近左边缘（10px，阈值 32px）起拖 → 吸附到 0，整行都能选上
        int[] s = GrabOverlay.snapToEdges(10f, 100f, 500f, 200f, 1080, 32);
        assertEquals(0, s[0]);
        assertEquals(500, s[2]);
    }

    @Test
    public void snapToEdges_rightNearEdge_snapsToViewWidth() {
        int[] s = GrabOverlay.snapToEdges(100f, 100f, 1070f, 200f, 1080, 32);
        assertEquals(1080, s[2]);
        assertEquals(100, s[0]);
    }

    @Test
    public void snapToEdges_bothEdgesNear_snapsBoth() {
        int[] s = GrabOverlay.snapToEdges(5f, 100f, 1075f, 200f, 1080, 32);
        assertEquals(0, s[0]);
        assertEquals(1080, s[2]);
    }

    @Test
    public void snapToEdges_middleDrag_unchanged() {
        int[] s = GrabOverlay.snapToEdges(200f, 100f, 800f, 300f, 1080, 32);
        assertEquals(200, s[0]);
        assertEquals(800, s[2]);
        assertEquals(100, s[1]);
        assertEquals(300, s[3]);
    }

    @Test
    public void snapToEdges_invertedDrag_stillNormalized() {
        // 从右往左拖：先归一化再吸附
        int[] s = GrabOverlay.snapToEdges(500f, 200f, 10f, 100f, 1080, 32);
        assertEquals(0, s[0]);
        assertEquals(500, s[2]);
    }

    @Test
    public void snapToEdges_unknownViewWidth_doesNotCrash() {
        int[] s = GrabOverlay.snapToEdges(10f, 10f, 20f, 20f, 0, 32);
        assertEquals(0, s[0]);
        assertEquals(20, s[2]);
    }

    // ── 框选层重做契约 ───────────────────────────

    private static String readFile(String path) throws Exception {
        File f = new File(path);
        assertTrue("文件不存在: " + f.getAbsolutePath(), f.exists());
        try (Scanner s = new Scanner(f, "UTF-8")) {
            s.useDelimiter("\\A");
            return s.hasNext() ? s.next() : "";
        }
    }

    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        while (i < src.length()) {
            if (i + 1 < src.length() && src.charAt(i) == '/' && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                i = end < 0 ? src.length() : end + 2;
            } else if (i + 1 < src.length() && src.charAt(i) == '/' && src.charAt(i + 1) == '/') {
                int end = src.indexOf('\n', i);
                i = end < 0 ? src.length() : end;
            } else {
                out.append(src.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    private static String methodBody(String src, String signature) {
        int start = src.indexOf(signature);
        if (start < 0) return null;
        int brace = src.indexOf('{', start);
        if (brace < 0) return null;
        int depth = 0;
        for (int i = brace; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return src.substring(brace + 1, i);
            }
        }
        return null;
    }

    private String overlay() throws Exception {
        return stripComments(readFile("src/main/java/com/rd/englishcoach/GrabOverlay.java"));
    }

    @Test
    public void overlay_drawsScreenshotScaledToView() throws Exception {
        String src = overlay();
        String body = methodBody(src, "private void drawOverlay(Canvas canvas, int vw, int vh)");
        assertNotNull("必须有 drawOverlay", body);
        assertTrue("截图必须等比缩放画满视图（drawBitmap(screenshot, null, dst)），"
                        + "否则位图与屏幕尺寸不一致时框选错位",
                body.contains("drawBitmap(screenshot, null,"));
        assertTrue("选中区必须重画未变暗的原始内容（不打洞）",
                body.contains("drawBitmap(screenshot, src, selectionRect"));
    }

    @Test
    public void overlay_cropUsesCoordinateMapping() throws Exception {
        String body = methodBody(overlay(), "private void onSelectionComplete()");
        assertNotNull(body);
        assertTrue("裁剪必须走 mapToBitmap（视图坐标 → 位图坐标）",
                body.contains("mapToBitmap(vw, vh, screenshot.getWidth(), screenshot.getHeight()"));
    }

    @Test
    public void overlay_neverRecyclesDisplayBitmap() throws Exception {
        String src = overlay();
        assertFalse("框选层不得 recycle 显示位图（窗口刚 remove 时 RenderThread "
                        + "可能仍持有引用 → 概率闪退）",
                src.contains("screenshot.recycle()"));
        String dismiss = methodBody(src, "public void dismiss()");
        assertNotNull(dismiss);
        assertFalse("dismiss 不得 recycle", dismiss.contains("recycle()"));
    }

    @Test
    public void overlay_dismissIsThreadSafe() throws Exception {
        String src = overlay();
        String dismiss = methodBody(src, "public void dismiss()");
        assertNotNull(dismiss);
        assertTrue("dismiss 可能从投影回调线程调用（releaseCapture），"
                        + "窗口操作必须 post 到主线程，否则 CalledFromWrongThreadException",
                dismiss.contains("mainHandler.post("));
    }

    @Test
    public void overlay_declaresSystemGestureExclusionRects() throws Exception {
        String src = overlay();
        assertTrue("必须声明系统手势排除区（否则从边缘起拖会触发系统返回，框选被截断）",
                src.contains("setSystemGestureExclusionRects"));
        String body = methodBody(src, "private void applyGestureExclusion(int vw, int vh)");
        assertNotNull("必须有 applyGestureExclusion", body);
        assertTrue("排除区必须在 onLayout/onDraw 里声明（官方要求）",
                src.contains("onLayout(boolean changed") && src.contains("applyGestureExclusion(getWidth(), getHeight())"));
        assertTrue("排除高度不得超过官方上限 200dp",
                src.contains("MAX_GESTURE_EXCLUSION_DP = 200"));
        assertTrue("排除区要全宽（左右边缘都受益）",
                body.contains("new Rect(0, 0, vw, bandH)"));
    }

    @Test
    public void overlay_snapsSelectionToEdges() throws Exception {
        String src = overlay();
        String body = methodBody(src, "private void onSelectionComplete()");
        assertNotNull(body);
        assertTrue("框选完成时必须做贴边吸附（修「内容被切掉」）",
                body.contains("snapToEdges("));
        assertTrue("吸附必须在裁剪之前（裁剪用吸附后的坐标）",
                body.indexOf("snapToEdges(") < body.indexOf("Bitmap.createBitmap(screenshot"));
    }

    @Test
    public void overlay_gestureCancel_doesNotCompleteSelection() throws Exception {
        String body = methodBody(overlay(), "private boolean onTouch(MotionEvent event)");
        assertNotNull(body);
        int cancelIdx = body.indexOf("case MotionEvent.ACTION_CANCEL:");
        assertTrue("CANCEL 必须有独立分支", cancelIdx >= 0);
        int upIdx = body.indexOf("case MotionEvent.ACTION_UP:");
        assertTrue("UP 与 CANCEL 必须分开处理", upIdx > cancelIdx);
        // CANCEL 分支（到 UP 之前）不得完成框选：否则会得到被系统截断的区域
        String cancelBranch = body.substring(cancelIdx, upIdx);
        assertFalse("被系统手势抢走时不能拿它去完成框选",
                cancelBranch.contains("onSelectionComplete()"));
        assertTrue("CANCEL 后要提示用户重新拖", cancelBranch.contains("hintText"));
    }

    @Test
    public void overlay_hintMentionsEdgeGesture() throws Exception {
        String src = overlay();
        assertTrue("提示文案要说明边缘手势问题", src.contains("边缘手势"));
    }

    @Test
    public void grabManager_neverRecyclesDisplayFrame() throws Exception {
        String src = stripComments(readFile("src/main/java/com/rd/englishcoach/GrabManager.java"));
        assertFalse("GrabManager 不得 recycle 展示帧（同类 RenderThread 竞态）",
                src.contains("f.recycle()"));
    }

    @Test
    public void overlay_cancelOnlyViaExplicitButton() throws Exception {
        String src = overlay();
        String body = methodBody(src, "private boolean onTouch(MotionEvent event)");
        assertNotNull("必须有 onTouch", body);
        assertFalse("不得再有「点框外=取消」逻辑（误触退出）",
                src.contains("!selectionRect.contains("));
        assertTrue("必须有显式「✕ 取消」按钮",
                src.contains("✕ 取消"));
        assertTrue("取消必须命中 cancelRect 才触发",
                body.contains("cancelRect.contains("));
        assertTrue("单击空白只清框不退出：必须区分「拖动」与「单击」（movedFar）",
                body.contains("movedFar"));
    }

    @Test
    public void overlay_smallSelectionGetsHintInsteadOfSilentClear() throws Exception {
        String body = methodBody(overlay(), "private void onSelectionComplete()");
        assertNotNull(body);
        assertTrue("框太小必须给提示文案（旧逻辑静默清空，像没反应）",
                body.contains("框太小"));
    }

    @Test
    public void overlay_showDismissesPreviousInstance() throws Exception {
        String body = methodBody(overlay(), "public void show(Bitmap frame)");
        assertNotNull(body);
        assertTrue("show 必须先 dismiss 上一张（防重入叠加两层）",
                body.contains("dismiss();"));
    }

    // ── 截屏通道取新帧契约 ───────────────────────

    @Test
    public void screenCapture_forcesFreshFrameViaSurfaceCycle() throws Exception {
        String src = stripComments(
                readFile("src/main/java/com/rd/englishcoach/ScreenTextCapture.java"));
        String body = methodBody(src, "public Bitmap grabFrame()");
        assertNotNull("必须有 grabFrame", body);
        assertTrue("必须先丢弃滞留旧帧（内容可能是几分钟前的画面）",
                body.contains("drainStaleFrames()"));
        assertTrue("必须 setSurface(null) 断开表面",
                body.contains("virtualDisplay.setSurface(null)"));
        assertTrue("必须重新 setSurface(reader) 强制 SurfaceFlinger 重绘一帧当下画面",
                body.contains("virtualDisplay.setSurface(imageReader.getSurface())"));
        assertTrue("必须用 OnImageAvailableListener 等帧到位（而非盲轮询）",
                src.contains("setOnImageAvailableListener"));
        assertTrue("等帧必须有超时（不能永远卡住）",
                src.contains("FRAME_WAIT_MS"));
    }

    @Test
    public void screenCapture_readsBufferFromStart() throws Exception {
        String src = stripComments(
                readFile("src/main/java/com/rd/englishcoach/ScreenTextCapture.java"));
        assertTrue("读帧前必须 buffer.rewind()（buffer 位置不保证是 0，否则画面错乱）",
                src.contains("buffer.rewind()"));
    }
}
