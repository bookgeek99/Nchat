package com.nchat.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ScrollView;

/**
 * 带最大高度限制的 ScrollView。
 *
 * android:maxHeight 属性对 ScrollView 不生效（它不参与 onMeasure），
 * 所以这里重写 onMeasure，把自己的高度夹在 maxHeight 以内。
 * 用于通知卡片正文：短内容自适应，长内容在 150dp 内滚动，
 * 避免把卡片撑到接近整屏、把底部按钮挤走。
 */
public class MaxHeightScrollView extends ScrollView {

    private int maxHeight = Integer.MAX_VALUE;

    public MaxHeightScrollView(Context context) {
        super(context);
    }

    public MaxHeightScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        if (attrs != null) {
            int[] set = {android.R.attr.maxHeight};
            android.content.res.TypedArray a =
                    context.obtainStyledAttributes(attrs, set);
            maxHeight = a.getDimensionPixelSize(0, Integer.MAX_VALUE);
            a.recycle();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        try {
            int h = MeasureSpec.getSize(heightMeasureSpec);
            if (h > maxHeight) {
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(
                        maxHeight, MeasureSpec.AT_MOST);
            }
        } catch (Throwable ignore) {
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int measured = getMeasuredHeight();
        if (measured > maxHeight) {
            super.onMeasure(widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.EXACTLY));
        }
    }
}