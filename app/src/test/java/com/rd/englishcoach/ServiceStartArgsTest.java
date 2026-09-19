package com.rd.englishcoach;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * ServiceStartArgs 的回归测试。
 *
 * <p>核心场景：授权成功时 {@code resultCode == Activity.RESULT_OK == -1}，
 * 必须被判定为"有效"。修复前的实现用 -1 当哨兵值，会把成功判成缺失，
 * 导致前台服务不启动（无通知、无悬浮窗）。</p>
 */
public class ServiceStartArgsTest {

    /** Activity.RESULT_OK 的真实取值。这里写死字面量，避免测试依赖 Android 类。 */
    private static final int RESULT_OK = -1;
    /** Activity.RESULT_CANCELED 的真实取值。 */
    private static final int RESULT_CANCELED = 0;

    // ── 回归：RESULT_OK(-1) 必须有效 ──────────────

    @Test
    public void resultOk_isValid_regression() {
        assertTrue("RESULT_OK(-1) 必须被判为有效，否则授权成功却启动不了服务",
                ServiceStartArgs.isResultCodePresent(true, RESULT_OK));
    }

    @Test
    public void resultOk_canStart_regression() {
        assertTrue(ServiceStartArgs.canStart(true, RESULT_OK, new Object()));
    }

    @Test
    public void resultCanceled_isStillPresent() {
        // 0 也带着 extra，只是值不同；是否放行由 canStart 之外的业务决定
        assertTrue(ServiceStartArgs.isResultCodePresent(true, RESULT_CANCELED));
    }

    // ── 真正缺失的情形 ────────────────────────────

    @Test
    public void noExtra_isInvalid() {
        assertFalse(ServiceStartArgs.isResultCodePresent(false, RESULT_OK));
    }

    @Test
    public void noExtra_cannotStart() {
        assertFalse(ServiceStartArgs.canStart(false, RESULT_OK, new Object()));
    }

    @Test
    public void sentinelValue_isInvalid() {
        assertFalse(ServiceStartArgs.isResultCodePresent(true, ServiceStartArgs.NO_RESULT_CODE));
    }

    @Test
    public void nullProjectionData_cannotStart() {
        assertFalse(ServiceStartArgs.canStart(true, RESULT_OK, null));
    }

    // ── 哨兵值本身必须避开 RESULT_OK / RESULT_CANCELED ──

    @Test
    public void sentinelMustNotCollideWithActivityResultConstants() {
        assertNotEquals("哨兵值不能等于 RESULT_OK", RESULT_OK, ServiceStartArgs.NO_RESULT_CODE);
        assertNotEquals("哨兵值不能等于 RESULT_CANCELED", RESULT_CANCELED, ServiceStartArgs.NO_RESULT_CODE);
    }

    // ── 组合矩阵 ─────────────────────────────────

    @Test
    public void matrix() {
        Object d = new Object();
        assertTrue(ServiceStartArgs.canStart(true, RESULT_OK, d));
        assertTrue(ServiceStartArgs.canStart(true, RESULT_CANCELED, d));
        assertTrue(ServiceStartArgs.canStart(true, 7, d));
        assertFalse(ServiceStartArgs.canStart(false, RESULT_OK, d));
        assertFalse(ServiceStartArgs.canStart(true, ServiceStartArgs.NO_RESULT_CODE, d));
        assertFalse(ServiceStartArgs.canStart(true, RESULT_OK, null));
        assertFalse(ServiceStartArgs.canStart(false, ServiceStartArgs.NO_RESULT_CODE, null));
    }
}
