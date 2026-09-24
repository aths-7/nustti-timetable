package edu.nustti.timetable.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

/**
 * 顶部悬浮玻璃标题栏（与右上角三点容器、底部 Dock 统一为同一圆润悬浮玻璃语言）。
 *
 * <p>不再绘制整条玻璃背景：标题栏整体透明，露出沉浸式壁纸；仅保留两个<strong>独立悬浮
 * 玻璃容器</strong>——左侧「南泰科课表」标题容器（浅灰半透明、柔和圆角、无边框、柔和阴影、
 * 弱主题色氛围 tint）与右侧三点圆形玻璃按钮，二者与底部 Dock 观感完全一致。</p>
 *
 * <p>内部布局：左侧标题独立容器（白字 + 柔和投影 + 主题色氛围），右侧独立的<strong>圆形</strong>
 * 三点菜单按钮，点击行为由外部绑定。</p>
 */
public class GlassToolbarView extends FrameLayout {

    private static final float TITLE_CORNER_DP = 18f;
    private static final float TITLE_SIZE_SP = 18f;
    private static final float MENU_BTN_DP = 40f;
    private static final float MENU_CORNER_DP = 20f; // 40dp 容器圆角 20dp = 圆形

    /** 三处容器统一使用的浅灰半透明玻璃底色（与 Dock GlassLayerView 同族）。 */
    private static final int GLASS_BASE = 0x59D8DCE0;
    private static final int GLASS_BASE_STRONG = 0x66D8DCE0;

    private final float density;

    private TextView titleView;
    private FrameLayout titleContainer;
    private FrameLayout menuButton;
    private int themeColor = 0xFF1D4ED8;

    public GlassToolbarView(Context context) {
        this(context, null);
    }

    public GlassToolbarView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public GlassToolbarView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = getResources().getDisplayMetrics().density;
        buildContent(context);
    }

    private float dp(float v) {
        return v * density;
    }

    private void buildContent(Context context) {
        // 左侧「南泰科课表」独立悬浮玻璃容器
        titleContainer = new FrameLayout(context);
        titleContainer.setElevation(dp(3f));
        titleContainer.setOutlineProvider(roundOutline(dp(TITLE_CORNER_DP)));
        titleContainer.setClipToOutline(true);
        titleContainer.setBackground(glassBackground(TITLE_CORNER_DP, 0x26));
        FrameLayout.LayoutParams tlpc = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        tlpc.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        tlpc.setMarginStart((int) dp(16f));
        addView(titleContainer, tlpc);

        titleView = new TextView(context);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SIZE_SP);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        // 柔和投影提升玻璃底上的文字清晰度
        titleView.setShadowLayer(dp(2f), 0f, dp(1f), 0x4D000000);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        tlp.setMargins((int) dp(16f), (int) dp(6f), (int) dp(16f), (int) dp(6f));
        titleContainer.addView(titleView, tlp);

        // 右侧独立圆形悬浮玻璃按钮（三个点菜单），与底部 Dock 同一玻璃语言
        menuButton = new FrameLayout(context);
        menuButton.setClickable(true);
        menuButton.setFocusable(true);
        menuButton.setElevation(dp(3f));
        menuButton.setOutlineProvider(roundOutline(dp(MENU_CORNER_DP)));
        menuButton.setClipToOutline(true);
        menuButton.setBackground(glassBackground(MENU_CORNER_DP, 0x1F));
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(
                (int) dp(MENU_BTN_DP), (int) dp(MENU_BTN_DP));
        mlp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
        mlp.setMarginEnd((int) dp(16f));

        MenuDotsView dots = new MenuDotsView(context);
        menuButton.addView(dots, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        addView(menuButton, mlp);
    }

    /** 圆角 Outline（elevation 柔和阴影跟随圆角形状）。 */
    private ViewOutlineProvider roundOutline(final float radius) {
        return new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        };
    }

    /** 浅灰半透明玻璃容器背景：无边框，可选叠加主题色氛围 tint（保持主题色兼容）。 */
    private Drawable glassBackground(float cornerDp, int themeTintAlpha) {
        GradientDrawable base = new GradientDrawable();
        base.setCornerRadius(dp(cornerDp));
        base.setColor(GLASS_BASE);
        if (themeTintAlpha > 0) {
            GradientDrawable tint = new GradientDrawable();
            tint.setCornerRadius(dp(cornerDp));
            tint.setColor(ColorUtils.setAlphaComponent(themeColor, themeTintAlpha));
            return new LayerDrawable(new Drawable[]{base, tint});
        }
        return base;
    }

    // ------------------------------------------------------------------ //
    // 对外接口
    // ------------------------------------------------------------------ //

    public void setTitle(CharSequence title) {
        titleView.setText(title);
    }

    /** 设置主题色（默认品牌蓝），设置页修改后调用即时刷新两个容器的主题色氛围层。 */
    public void setThemeColor(int color) {
        themeColor = color;
        if (titleContainer != null) {
            titleContainer.setBackground(glassBackground(TITLE_CORNER_DP, 0x26));
        }
        if (menuButton != null) {
            menuButton.setBackground(glassBackground(MENU_CORNER_DP, 0x1F));
        }
    }

    /** 三个点菜单按钮（独立圆形悬浮玻璃容器），点击行为由外部绑定。 */
    public View getMenuButton() {
        return menuButton;
    }

    // ------------------------------------------------------------------ //
    // 三个点图标
    // ------------------------------------------------------------------ //

    /** 三个白色圆点，带微弱投影，水平居中排列。 */
    private static class MenuDotsView extends View {

        private static final float DOT_R_DP = 1.7f;
        private static final float DOT_GAP_DP = 5f;

        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        MenuDotsView(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            dotPaint.setColor(0xF0FFFFFF);
            dotPaint.setShadowLayer(dp(1.5f), 0f, dp(0.5f), 0x55000000);
        }

        private float dp(float v) {
            return v * density;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cy = getHeight() / 2f;
            float r = dp(DOT_R_DP);
            float gap = dp(DOT_GAP_DP);
            float total = 3f * r * 2f + 2f * gap;
            float cx0 = (getWidth() - total) / 2f + r;
            for (int i = 0; i < 3; i++) {
                canvas.drawCircle(cx0 + i * (r * 2f + gap), cy, r, dotPaint);
            }
        }
    }
}
