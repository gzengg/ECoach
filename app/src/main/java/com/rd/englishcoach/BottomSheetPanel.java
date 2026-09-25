package com.rd.englishcoach;

import android.app.Dialog;
import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;

/**
 * 底部弹窗（Bottom Sheet）：页面内操作的统一容器，减少页面跳转。
 *
 * <p><b>为什么不用 BottomSheetDialog：</b>它来自 Material Components 库，
 * 不在依赖白名单里。这里用框架 {@link Dialog} 自绘：底部对齐 + 入场上滑动画
 * （§4.5 只做进入动画，关闭直接消失）+ 遮罩点击关闭 + 系统返回关闭
 * （右滑返回交给系统手势，不拦截）。</p>
 *
 * <p>用法：调用方自己构建内容 View 后 {@link #show(View)}；
 * 把手是装饰，下拉关闭走系统返回即可，不做拖拽跟手（保持简洁）。</p>
 */
final class BottomSheetPanel {

    private final Activity activity;
    private Dialog dialog;

    BottomSheetPanel(Activity activity) {
        this.activity = activity;
    }

    /** 弹出内容（内容顶部会自动带一条拖拽把手）。重复调用会先关掉上一个。 */
    void show(View content) {
        dismiss();

        LinearLayout sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackgroundResource(R.drawable.bg_sheet);
        int pad = activity.getResources().getDimensionPixelSize(R.dimen.space_lg);
        sheet.setPadding(pad, pad / 2, pad, pad);

        // 把手：32x4dp 胶囊，纯装饰
        View handle = new View(activity);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(32), dp(4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = pad / 2;
        handle.setBackgroundResource(R.drawable.sheet_handle);
        sheet.addView(handle, hp);
        sheet.addView(content);

        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        // 背景透明 + 遮罩色，窗口动画只有入场（styles.xml SheetAnimation）
        dialog.getWindow().setBackgroundDrawableResource(R.color.scrim);
        dialog.getWindow().setWindowAnimations(R.style.SheetAnimation);
        dialog.setContentView(sheet);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnCancelListener(d -> dialog = null);
        dialog.show();
    }

    boolean showing() { return dialog != null; }

    void dismiss() {
        if (dialog != null) {
            Dialog d = dialog;
            dialog = null;
            d.dismiss();
        }
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
