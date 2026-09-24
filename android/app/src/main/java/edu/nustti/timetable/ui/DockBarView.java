package edu.nustti.timetable.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
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

        // 玻璃层：液态玻璃自绘层（多层阴影 + 双实线边框 + 内部光晕 + 对比度滤镜）
        glassLayer = new GlassLayerView(context, corner);
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

        // 阴影：胶囊形轮廓 + elevation（调低，外阴影主要由自绘层提供）
        setElevation(dp(8));
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

        ImageView icon = new ShadowedImageView(context);
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
        // 高度 = 玻璃主体(HEIGHT_DP + topMargin) + 底部阴影留白；宽铺满，左右阴影靠主体 inset 留出
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (int) dp(HEIGHT_DP) + (int) dp(2) + (int) dp(14));
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

    // ------------------------------------------------------------------ //
    // 液态玻璃自绘层
    // ------------------------------------------------------------------ //

    /**
     * 圆润悬浮玻璃自绘层（软件层渲染以启用 Paint.setShadowLayer 对图形绘制支持）：
     * <ol>
     *   <li>柔和外阴影：远投影 + 近投影两层叠加，形成柔和悬浮感；</li>
     *   <li>玻璃底：浅灰半透明磨砂渐变（与右上角三点容器同一玻璃语言），无边框；</li>
     *   <li>顶部柔和高光：弱化后的渐变高光提升玻璃质感。</li>
     * </ol>
     */
    private static class GlassLayerView extends View {

        private final float corner;
        private final float density;
        private final RectF body = new RectF();

        private final Paint shadowOuter = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadowSoft = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        GlassLayerView(Context context, float corner) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            this.corner = corner;
            density = getResources().getDisplayMetrics().density;

            // 1) 柔和阴影：远投影 + 近投影（fill 透明只留阴影）
            shadowOuter.setShadowLayer(dp(14f), 0f, dp(6f), 0x26000000);
            shadowOuter.setColor(0x00000000);
            shadowSoft.setShadowLayer(dp(8f), 0f, dp(3f), 0x1F000000);
            shadowSoft.setColor(0x00000000);

            // 2) 浅灰半透明磨砂渐变底（无边框，柔和圆角）
            glassPaint.setShader(new LinearGradient(0f, 0f, 0f, dp(HEIGHT_DP + 2f),
                    new int[]{0x99D8DCE0, 0x59D8DCE0}, null, Shader.TileMode.CLAMP));

            // 3) 柔和顶部高光
            highlightPaint.setShader(new LinearGradient(0f, 0f, 0f, dp(30f),
                    new int[]{0x40FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        }

        private float dp(float v) {
            return v * density;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            // 玻璃主体：左右留白供阴影溢出，顶部 3dp 起，与内容行对齐
            body.set(dp(8f), dp(3f), w - dp(8f), dp(3f) + dp(HEIGHT_DP + 2f));

            // 1) 柔和阴影（远投影 + 近投影）
            canvas.drawRoundRect(body, corner, corner, shadowOuter);
            canvas.drawRoundRect(body, corner, corner, shadowSoft);

            // 2) 浅灰半透明磨砂渐变底（无边框）
            canvas.drawRoundRect(body, corner, corner, glassPaint);

            // 3) 柔和顶部高光
            RectF top = new RectF(body.left, body.top, body.right, body.top + dp(30f));
            canvas.drawRoundRect(top, corner, corner, highlightPaint);
        }
    }

    // ------------------------------------------------------------------ //
    // 带投影的图标
    // ------------------------------------------------------------------ //

    /**
     * 带 drop-shadow 的图标视图：软件层先按图标 alpha 蒙版绘制投影，再绘制原图标。
     */
    private static class ShadowedImageView extends ImageView {

        private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private Bitmap shadowMask;

        ShadowedImageView(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            shadowPaint.setShadowLayer(dp(3f), 0f, dp(1.5f), 0x4D000000);
        }

        private float dp(float v) {
            return v * density;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Drawable d = getDrawable();
            if (d == null) {
                super.onDraw(canvas);
                return;
            }
            int w = getWidth();
            int h = getHeight();
            if (shadowMask == null || shadowMask.getWidth() != w || shadowMask.getHeight() != h) {
                if (w <= 0 || h <= 0) {
                    super.onDraw(canvas);
                    return;
                }
                shadowMask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas mc = new Canvas(shadowMask);
                Rect b = d.copyBounds();
                if (b.isEmpty()) {
                    b.set(0, 0, w, h);
                    d.setBounds(b);
                }
                d.draw(mc);
            }
            // 先画投影（阴影由图标 alpha 蒙版 + setShadowLayer 生成）
            canvas.drawBitmap(shadowMask, 0f, 0f, shadowPaint);
            super.onDraw(canvas);
        }
    }
}
