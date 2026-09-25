package com.rd.englishcoach;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 底部导航栏（4 Tab）：监听 / 历史 / 模型 / 设置。
 *
 * <p><b>为什么自绘：</b>依赖白名单不许引 Material 库，BottomNavigationView 用不了；
 * 用 LinearLayout + 4 个等分热区（屏宽/4 x 56dp，≥44px）手写，视觉全走既有 token。</p>
 *
 * <p><b>角标（导航随上下文动态变化）：</b>监听中 → 「监听」Tab 呼吸点（复用 dot_active）；
 * 历史有新记录 → 「历史」Tab 点；自动模式下识别模型缺失 → 「模型」Tab warn 点。
 * 由 MainActivity 按事件调用 {@link #setBadge}。</p>
 *
 * <p>⚠️ 从 XML 实例化的自定义 View 必须是 public（LayoutInflater 反射创建），
 * 否则 setContentView 直接 InflateException 闪退（真机踩过）。</p>
 */
public final class BottomBar extends LinearLayout {

    /** Tab 序号（与 view_bottom_bar.xml 里的顺序一致）。 */
    static final int TAB_LISTEN = 0;
    static final int TAB_HISTORY = 1;
    static final int TAB_MODELS = 2;
    static final int TAB_SETTINGS = 3;

    private static final int[] TAB_IDS = {
            R.id.tabListen, R.id.tabHistory, R.id.tabModels, R.id.tabSettings};
    /** 没有角标的 Tab 填 0。 */
    private static final int[] BADGE_IDS = {
            R.id.badgeListen, R.id.badgeHistory, R.id.badgeModels, 0};

    private final View[] tabs = new View[4];
    private final View[] badges = new View[4];
    private Listener listener;
    private int selected = TAB_LISTEN;

    interface Listener { void onTabSelected(int tab); }

    public BottomBar(Context ctx) { this(ctx, null); }

    /**
     * XML 实例化走这个构造器。
     * ⚠️ 必须 {@code super(ctx, attrs)} 把 XML 属性（含 android:id）交给父类：
     * 真机踩过——签名是 public 但内部 {@code this(ctx)} 丢弃了 attrs，
     * 视图能创建却没有 id，MainActivity 里 {@code findViewById(R.id.bottomBar)} 返回 null，
     * 第一次 setListener 就 NPE 闪退。
     */
    public BottomBar(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        setOrientation(VERTICAL);
        LayoutInflater.from(ctx).inflate(R.layout.view_bottom_bar, this, true);
        for (int i = 0; i < TAB_IDS.length; i++) {
            final int tab = i;
            tabs[i] = findViewById(TAB_IDS[i]);
            tabs[i].setOnClickListener(v -> {
                if (tab != selected && listener != null) listener.onTabSelected(tab);
            });
            if (BADGE_IDS[i] != 0) badges[i] = findViewById(BADGE_IDS[i]);
        }
        style();
    }

    void setListener(Listener l) { listener = l; }

    int selected() { return selected; }

    /** 切换选中态：选中 Tab 文字用强调色 + 加粗，其余用占位色。 */
    void select(int tab) {
        selected = tab;
        style();
    }

    /** 显示/隐藏某个 Tab 的角标点。 */
    void setBadge(int tab, boolean show) {
        if (BADGE_IDS[tab] == 0 || badges[tab] == null) return;
        badges[tab].setVisibility(show ? VISIBLE : GONE);
    }

    private void style() {
        for (int i = 0; i < TAB_IDS.length; i++) {
            TextView label = (TextView) ((FrameLayout) tabs[i]).getChildAt(0);
            boolean on = i == selected;
            label.setTextColor(getContext().getColor(on
                    ? R.color.accent_solid : R.color.text_tertiary));
            label.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        }
    }
}
