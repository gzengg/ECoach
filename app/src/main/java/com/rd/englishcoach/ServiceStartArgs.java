package com.rd.englishcoach;

/**
 * ACTION_START 入参的校验逻辑。
 *
 * <p><b>为什么单独抽一个类：</b>{@code Activity.RESULT_OK} 的值是 {@code -1}，
 * 如果用 {@code -1} 当作"结果码缺失"的哨兵值，就会出现
 * "授权成功（rc=-1）反而被判定为缺失 → 前台服务不启动" 的 bug
 * （表现为：没有通知栏、没有悬浮窗）。所以哨兵值必须避开 -1，
 * 并且优先用 {@code Intent.hasExtra()} 判断。</p>
 *
 * <p>这里是纯 Java，不依赖任何 Android 类，可以在 JVM 单元测试里直接验证。</p>
 */
public final class ServiceStartArgs {

    /** "结果码缺失"的哨兵值。刻意避开 -1（= Activity.RESULT_OK）与 0（= RESULT_CANCELED）。 */
    public static final int NO_RESULT_CODE = Integer.MIN_VALUE;

    private ServiceStartArgs() {}

    /**
     * 结果码是否真的存在。
     *
     * @param hasResultCodeExtra Intent 是否携带了 EXTRA_RESULT_CODE
     * @param resultCode         读出来的结果码
     */
    public static boolean isResultCodePresent(boolean hasResultCodeExtra, int resultCode) {
        return hasResultCodeExtra && resultCode != NO_RESULT_CODE;
    }

    /**
     * 是否具备启动采集的全部条件：结果码存在 + 投屏授权数据非空。
     */
    public static boolean canStart(boolean hasResultCodeExtra, int resultCode, Object projectionData) {
        return isResultCodePresent(hasResultCodeExtra, resultCode) && projectionData != null;
    }
}
