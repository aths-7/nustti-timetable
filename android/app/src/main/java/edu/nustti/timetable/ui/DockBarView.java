package edu.nustti.timetable.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import edu.nustti.timetable.R;

/**
 * 苹果 Dock 风格液态玻璃导航栏。
 *
 * <p>悬浮于内容之上的胶囊形导航条：水平居中、左右留边距，含整周 / 今日 / 设置三个图标。</p>
 *
 * <p>液态玻璃实现：半透明白渐变 + 顶部高光 + 1dp 细边框叠加在胶囊形圆角之上，
 * 模拟液态玻璃折射高光（不使用实时背景模糊，避免与硬件加速渲染管线冲突）。</p>
 *
 * <p>交互：点击图标放大上浮（OvershootInterpolator 弹性）并切换页面，当前选中项高亮。</p>
 */
public class DockBarView extends FrameLayout {

    /** Dock 图标点击回调（position：0 整周 / 1 今日 / 2 设置）。 */
    public interface OnDockItemSelectedListener {
        void onDockItemSelected(int position);
    }

    private static final int ITEM_COUNT = 3;
    private static final float CORNER_DP = 30f;
    private static final float HEIGHT_DP = 60f;

    private View glassLayer;
    private LinearLayout contentRow;
    private final View[] itemViews = new View[ITEM_COUNT];
    private final ImageView[] itemIcons = new ImageView[ITEM_COUNT];
    private final TextView[] itemLabels = new TextView[ITEM_COUNT];
    private final View[] itemDots = new View[ITEM_COUNT];

    private int selectedIndex = 0;
    private OnDockItemSelectedListener listener;

    public DockBarView(Context context) {
        this(context, null);
    }

    public DockBarView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DockBarView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        buildLayers(context);
    }

    // ------------------------------------------------------------------ //
    // 构建
    // ------------------------------------------------------------------ //

    private void buildLayers(Context context) {
        float corner = dp(CORNER_DP);

        // 玻璃层：半透明白渐变 + 顶部高光 + 1dp 细边框，模拟液态玻璃折射高光
        glassLayer = new View(context);
        glassLayer.setClipToOutline(true);
        glassLayer.setOutlineProvider(roundOutline(corner));
        GradientDrawable glassBase = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xCCFFFFFF, 0x66FFFFFF});
        glassBase.setCornerRadius(corner);
        glassBase.setStroke((int) dp(1f), 0x73FFFFFF);
        GradientDrawable highlight = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x80FFFFFF, 0x00FFFFFF});
        highlight.setCornerRadii(new float[]{corner, corner, corner, corner, 0f, 0f, 0f, 0f});
        glassLayer.setBackground(new LayerDrawable(new Drawable[]{glassBase, highlight}));
        // 固定高度覆盖内容行区域（宽铺满，高 = HEIGHT_DP + 内容行 topMargin），
        // 严禁 MATCH_PARENT×MATCH_PARENT：DockBarView 高度为 wrap_content 时会被撑成全屏
        addView(glassLayer, fixedGlassParams());

        // 内容行：三个图标（整周 / 今日 / 设置）
        contentRow = new LinearLayout(context);
        contentRow.setOrientation(LinearLayout.HORIZONTAL);
        contentRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) dp(HEIGHT_DP));
        rowLp.topMargin = (int) dp(2);
        contentRow.setLayoutParams(rowLp);

        int[] iconRes = {R.drawable.ic_dock_week, R.drawable.ic_dock_today, R.drawable.ic_dock_settings};
        String[] labels = {"整周", "今日", "设置"};
        for (int i = 0; i < ITEM_COUNT; i++) {
            View item = buildItem(context, i, iconRes[i], labels[i]);
            itemViews[i] = item;
            contentRow.addView(item);
        }
        addView(contentRow);

        // 阴影：胶囊形轮廓 + elevation，悬浮在内容之上
        setElevation(dp(16));
        setOutlineProvider(roundOutline(corner));
        setClipToOutline(false);

        updateItems(false);
    }

    private View buildItem(Context context, int index, int iconRes, String labelText) {
        LinearLayout item = new LinearLayout(context);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setPadding((int) dp(6), (int) dp(6), (int) dp(6), (int) dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        item.setLayoutParams(lp);
        item.setClickable(true);
        item.setFocusable(true);

        ImageView icon = new ImageView(context);
        icon.setImageResource(iconRes);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams((int) dp(26), (int) dp(26));
        icon.setLayoutParams(ilp);
        icon.setColorFilter(Color.parseColor("#475569"));
        itemIcons[index] = icon;

        TextView label = new TextView(context);
        label.setText(labelText);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        label.setTextColor(Color.parseColor("#475569"));
        label.setGravity(Gravity.CENTER);
        itemLabels[index] = label;

        View dot = new View(context);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(Color.parseColor("#1D4ED8"));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams((int) dp(6), (int) dp(3));
        dlp.topMargin = (int) dp(3);
        dot.setLayoutParams(dlp);
        dot.setBackground(dotBg);
        dot.setVisibility(View.INVISIBLE);
        itemDots[index] = dot;

        item.addView(icon);
        item.addView(label);
        item.addView(dot);

        final int position = index;
        item.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                select(position, true);
            }
        });
        return item;
    }

    private ViewOutlineProvider roundOutline(final float radius) {
        return new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        };
    }

    private FrameLayout.LayoutParams fixedGlassParams() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (int) dp(HEIGHT_DP) + (int) dp(2));
    }

    // ------------------------------------------------------------------ //
    // 对外接口
    // ------------------------------------------------------------------ //

    public void setOnDockItemSelectedListener(OnDockItemSelectedListener listener) {
        this.listener = listener;
    }

    /** ViewPager 页面切换后同步选中态（不触发回调，避免循环）。 */
    public void setSelectedIndex(int index) {
        if (index == selectedIndex) {
            return;
        }
        selectedIndex = index;
        updateItems(false);
    }

    /** 点击 Dock 图标：弹性放大上浮并切换页面。 */
    public void select(int index, boolean animate) {
        if (index < 0 || index >= ITEM_COUNT) {
            return;
        }
        boolean changed = index != selectedIndex;
        selectedIndex = index;
        if (changed && listener != null) {
            listener.onDockItemSelected(index);
        }
        updateItems(animate);
    }

    // ------------------------------------------------------------------ //
    // 状态与动画
    // ------------------------------------------------------------------ //

    private void updateItems(boolean animate) {
        for (int i = 0; i < ITEM_COUNT; i++) {
            final View item = itemViews[i];
            final ImageView icon = itemIcons[i];
            final TextView label = itemLabels[i];
            final View dot = itemDots[i];
            boolean selected = i == selectedIndex;
            if (selected) {
                icon.setColorFilter(Color.parseColor("#1D4ED8"));
                label.setTextColor(Color.parseColor("#1D4ED8"));
                dot.setVisibility(View.VISIBLE);
                if (animate) {
                    // 点击瞬间放大上浮（scale 1.0 -> 1.25），再以弹性回落至选中态 1.12
                    item.animate().cancel();
                    item.setScaleX(1f);
                    item.setScaleY(1f);
                    item.setTranslationY(0f);
                    item.animate().scaleX(1.25f).scaleY(1.25f).translationY(-dp(8))
                            .setDuration(160).setInterpolator(new OvershootInterpolator(1.2f))
                            .withEndAction(new Runnable() {
                                @Override
                                public void run() {
                                    item.animate().scaleX(1.12f).scaleY(1.12f).translationY(-dp(3))
                                            .setDuration(220)
                                            .setInterpolator(new OvershootInterpolator(1.6f))
                                            .start();
                                }
                            }).start();
                } else {
                    item.animate().scaleX(1.12f).scaleY(1.12f).translationY(-dp(3))
                            .setDuration(220).setInterpolator(new OvershootInterpolator(1.4f)).start();
                }
            } else {
                item.animate().cancel();
                item.animate().scaleX(1f).scaleY(1f).translationY(0f)
                        .setDuration(180).start();
                icon.setColorFilter(Color.parseColor("#475569"));
                label.setTextColor(Color.parseColor("#475569"));
                dot.setVisibility(View.INVISIBLE);
            }
        }
    }

    // ------------------------------------------------------------------ //
    // 尺寸
    // ------------------------------------------------------------------ //

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }
}
