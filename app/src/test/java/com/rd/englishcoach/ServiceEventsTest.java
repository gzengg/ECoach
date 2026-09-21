package com.rd.englishcoach;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * ServiceEvents 的单测：验证常量约定，确保广播 action / extras key 不会意外改名。
 */
public class ServiceEventsTest {

    @Test
    public void actionStateChanged_isCorrect() {
        assertEquals("com.rd.englishcoach.STATE_CHANGED", ServiceEvents.ACTION_STATE_CHANGED);
    }

    @Test
    public void extras_keyNames() {
        assertEquals("running", ServiceEvents.EXTRA_RUNNING);
        assertEquals("reason", ServiceEvents.EXTRA_REASON);
    }

    @Test
    public void reasonConstants_notEmpty() {
        assertFalse(ServiceEvents.REASON_STARTED.isEmpty());
        assertFalse(ServiceEvents.REASON_STOPPED.isEmpty());
        assertFalse(ServiceEvents.REASON_PROJECTION_KILLED.isEmpty());
        assertFalse(ServiceEvents.REASON_NOTIFICATION_STOP.isEmpty());
    }

    @Test
    public void reasonConstants_areAllDistinct() {
        String[] reasons = {
            ServiceEvents.REASON_STARTED,
            ServiceEvents.REASON_STOPPED,
            ServiceEvents.REASON_PROJECTION_KILLED,
            ServiceEvents.REASON_NOTIFICATION_STOP
        };
        java.util.Set<String> set = new java.util.HashSet<>(java.util.Arrays.asList(reasons));
        assertEquals("reason 常量两两不同", 4, set.size());
    }

    // 注意：buildStateBroadcast 依赖 android.content.Intent，
    // 测试 JVM 里 Intent.getAction() 未 mock 会抛 RuntimeException。
    // 该方法逻辑极简（new Intent + putExtra + setPackage），不需要单独测试。
}
