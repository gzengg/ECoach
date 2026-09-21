package com.rd.englishcoach;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * GrabManager 状态机约定测试。
 * GrabManager 构造函数依赖 Android Handler，无法在纯 JVM 上实例化，
 * 故只验证状态枚举和接口约定。
 */
public class GrabStateTest {

    @Test
    public void grabManager_stateEnum_hasAllValues() {
        GrabManager.State[] values = GrabManager.State.values();
        assertEquals(6, values.length);
        assertEquals(GrabManager.State.IDLE, GrabManager.State.valueOf("IDLE"));
        assertEquals(GrabManager.State.CAPTURING, GrabManager.State.valueOf("CAPTURING"));
        assertEquals(GrabManager.State.SELECTING, GrabManager.State.valueOf("SELECTING"));
        assertEquals(GrabManager.State.OCR, GrabManager.State.valueOf("OCR"));
        assertEquals(GrabManager.State.TRANSLATING, GrabManager.State.valueOf("TRANSLATING"));
        assertEquals(GrabManager.State.DONE, GrabManager.State.valueOf("DONE"));
    }

    @Test
    public void grabManager_callbackInterface_exists() {
        assertNotNull(GrabManager.Callback.class);
        java.lang.reflect.Method[] methods = GrabManager.Callback.class.getDeclaredMethods();
        assertTrue("必须有 onStateChanged", hasMethod(methods, "onStateChanged"));
        assertTrue("必须有 onCaptureFailed", hasMethod(methods, "onCaptureFailed"));
        assertTrue("必须有 onOcrResult", hasMethod(methods, "onOcrResult"));
        assertTrue("必须有 onTranslationResult", hasMethod(methods, "onTranslationResult"));
        assertTrue("必须有 onOcrFailed", hasMethod(methods, "onOcrFailed"));
        assertTrue("必须有 onTranslationFailed", hasMethod(methods, "onTranslationFailed"));
        assertTrue("必须有 onAborted", hasMethod(methods, "onAborted"));
    }

    @Test
    public void grabManager_isInterface() {
        assertTrue("GrabManager.Callback 必须是接口",
                GrabManager.Callback.class.isInterface());
    }

    private boolean hasMethod(java.lang.reflect.Method[] methods, String name) {
        for (java.lang.reflect.Method m : methods) {
            if (m.getName().equals(name)) return true;
        }
        return false;
    }
}
