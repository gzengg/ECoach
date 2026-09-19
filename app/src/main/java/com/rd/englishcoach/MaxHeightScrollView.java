package com.rd.englishcoach;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ScrollView;

/**
 * 带 maxHeight 限制的 ScrollView。
 * 框架原生 ScrollView 没有 maxHeight 属性，悬浮窗里答案列表需要限高。
 */
public class MaxHeightScrollView extends ScrollView {

    private int maxHeightPx = Integer.MAX_VALUE;

    public MaxHeightScrollView(Context context) { super(context); }
    public MaxHeightScrollView(Context context, AttributeSet attrs) { super(context, attrs); }
    public MaxHeightScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /**
     * 设置最大高度（像素）。
     */
    public void setMaxHeight(int maxHeightPx) {
        this.maxHeightPx = maxHeightPx;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        if (heightMode != MeasureSpec.UNSPECIFIED) {
            heightSize = Math.min(heightSize, maxHeightPx);
        } else {
            heightSize = maxHeightPx;
        }

        int heightSpec = MeasureSpec.makeMeasureSpec(heightSize, MeasureSpec.AT_MOST);
        super.onMeasure(widthMeasureSpec, heightSpec);
    }
}
