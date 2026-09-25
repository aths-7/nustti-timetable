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
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import edu.nustti.timetable.R;

/**
 * 底部 Dock 导航栏（真实高斯模糊毛玻璃版）。
 *
 * <p>悬浮于内容之上的胶囊形导航条：水平居中、左右留边距，含整周 / 今日 / 设置三个图标。</p>
 *
 * <p>毛玻璃实现（Android 12+）：不再自绘模拟玻璃，改为「壁纸区域裁剪位图 +
 * {@link RenderEffect#createBlurEffect} 真实高斯模糊 + 半透明灰白雾面」三层叠加。
 * 模糊源仅取静态壁纸（BackgroundManager）在 Dock 区域对应的片段，事件驱动刷新
 * （背景变化 / 自身尺寸变化时各重建一次），<strong>严禁</strong>使用
 * ViewTreeObserver.OnDrawListener + 每帧截屏 + RenderEffect 组合（会与硬件加速冲突，
 * 导致整页模糊穿透与无限重绘）。低版本（API &lt; 31）降级为半透明灰白渐变静态玻璃。</p>
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
    /** 真实高斯模糊半径（dp），可按观感调整（12~20dp 均合适）。 */
    private static final float BLUR_RADIUS_DP = 16f;

    private BlurBackgroundView blurLayer;
    private LinearLayout contentRow;
    private final View[] itemViews = new View[ITEM_COUNT];
    private final ImageView[] itemIcons = new ImageView[ITEM_COUNT];
    private final TextView[] itemLabels = new TextView[ITEM_COUNT];
    private final View[] itemDots = new View[ITEM_COUNT];

    private int selectedIndex = 0;
    private OnDockItemSelectedListener listener;
    /** 选中项高亮胶囊背景（品牌蓝半透明圆角），让选中态更醒目。 */
    private GradientDrawable capsuleBg;

    /** 全屏壁纸快照（与窗口根背景同一 Drawable 绘制结果），用于按 Dock 区域裁剪模糊源。 */
    private Bitmap fullWallpaper;
    private boolean blurInitialized = false;

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

        // 模糊背景层：真实高斯模糊毛玻璃（壁纸区域裁剪 + RenderEffect 模糊 + 半透明雾面）
        blurLayer = new BlurBackgroundView(context, corner);
        FrameLayout.LayoutParams blurLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (int) dp(HEIGHT_DP) + (int) dp(2) + (int) dp(14));
        blurLp.leftMargin = (int) dp(8);
        blurLp.rightMargin = (int) dp(8);
        blurLp.topMargin = (int) dp(3);
        addView(blurLayer, blurLp);

        // 内容行：三个图标（整周 / 今日 / 设置）
        // 使用 FrameLayout.LayoutParams + gravity=CENTER 使图标组在 Dock 容器内严格水平居中，
        // 左右 margin 与 blurLayer 对齐（均 8dp），避免图标行与胶囊背景错位
        contentRow = new LinearLayout(context);
        contentRow.setOrientation(LinearLayout.HORIZONTAL);
        contentRow.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams rowLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, (int) dp(HEIGHT_DP));
        rowLp.gravity = Gravity.CENTER;
        rowLp.leftMargin = (int) dp(8);
        rowLp.rightMargin = (int) dp(8);
        contentRow.setLayoutParams(rowLp);

        int[] iconRes = {R.drawable.ic_dock_week, R.drawable.ic_dock_today, R.drawable.ic_dock_settings};
        String[] labels = {"整周", "今日", "设置"};
        for (int i = 0; i < ITEM_COUNT; i++) {
            View item = buildItem(context, i, iconRes[i], labels[i]);
            itemViews[i] = item;
            contentRow.addView(item);
        }
        addView(contentRow);

        capsuleBg = new GradientDrawable();
        capsuleBg.setShape(GradientDrawable.RECTANGLE);
        capsuleBg.setCornerRadius(dp(18f));
        capsuleBg.setColor(Color.parseColor("#7A1D4ED8"));

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
        icon.setColorFilter(Color.parseColor("#FFFFFF"));
        itemIcons[index] = icon;

        TextView label = new TextView(context);
        label.setText(labelText);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        label.setTextColor(Color.parseColor("#F1F5F9"));
        label.setGravity(Gravity.CENTER);
        itemLabels[index] = label;

        View dot = new View(context);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(Color.parseColor("#FFFFFF"));
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

    // ------------------------------------------------------------------ //
    // 对外接口
    // ------------------------------------------------------------------ //

    public void setOnDockItemSelectedListener(OnDockItemSelectedListener listener) {
        this.listener = listener;
    }

    /**
     * 设置主题色氛围层（背景吸色结果）：叠加在雾面之上呈现毛玻璃氛围色，与顶部玻璃容器同族；
     * 默认品牌蓝。宿主在首次布局 / 背景变化时低频调用。
     */
    public void setThemeColor(int color) {
        if (blurLayer != null) {
            blurLayer.setThemeColor(color);
        }
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

    /**
     * 事件驱动刷新模糊背景：宿主在壁纸/背景变化时调用一次（如
     * {@link MainActivity#notifyBackgroundChanged()}），重新取壁纸快照并按当前
     * Dock 位置裁剪模糊源。<strong>严禁</strong>每帧调用或挂 OnDrawListener。
     */
    public void refreshBlurBackground() {
        blurInitialized = true;
        Bitmap fresh = buildFullWallpaper();
        if (fresh != null) {
            Bitmap old = fullWallpaper;
            fullWallpaper = fresh;
            if (old != null && old != fresh) {
                old.recycle();
            }
        }
        rebuildBlurRegion();
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
                item.setBackground(capsuleBg);
                icon.setColorFilter(Color.parseColor("#FFFFFF"));
                label.setTextColor(Color.parseColor("#FFFFFF"));
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
                item.setBackground(null);
                item.animate().cancel();
                item.animate().scaleX(1f).scaleY(1f).translationY(0f)
                        .setDuration(180).start();
                icon.setColorFilter(Color.parseColor("#FFFFFF"));
                label.setTextColor(Color.parseColor("#F1F5F9"));
                dot.setVisibility(View.INVISIBLE);
            }
        }
    }

    // ------------------------------------------------------------------ //
    // 模糊背景构建（事件驱动，禁止每帧截屏）
    // ------------------------------------------------------------------ //

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) {
            return;
        }
        if (!blurInitialized) {
            // 首次布局完成：若已启用壁纸则初始化模糊源；未启用时保持纯雾面降级
            blurInitialized = true;
            refreshBlurBackground();
        } else {
            // 尺寸（如 insets 变化）后 Dock 位置变化，重建裁剪区域
            rebuildBlurRegion();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (fullWallpaper != null) {
            fullWallpaper.recycle();
            fullWallpaper = null;
        }
        blurLayer.clearRegionBitmap();
    }

    /** 将「壁纸 + 加深遮罩」根背景绘制为全屏位图（与 android.R.id.content 同尺寸同坐标）。 */
    private Bitmap buildFullWallpaper() {
        Drawable d = BackgroundManager.backgroundDrawable(getContext());
        if (d == null) {
            return null;
        }
        ViewGroup parent = (ViewGroup) getParent();
        int w = parent != null ? parent.getWidth() : getWidth();
        int h = parent != null ? parent.getHeight() : getHeight();
        if (w <= 0 || h <= 0) {
            return null;
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return bmp;
    }

    /** 按 blurLayer 在窗口中的位置，从全屏壁纸快照裁剪出 Dock 区域的模糊源。 */
    private void rebuildBlurRegion() {
        if (blurLayer == null) {
            return;
        }
        if (fullWallpaper == null || fullWallpaper.isRecycled()
                || blurLayer.getWidth() <= 0 || blurLayer.getHeight() <= 0) {
            blurLayer.clearRegionBitmap();
            return;
        }
        int[] loc = new int[2];
        blurLayer.getLocationInWindow(loc);
        int left = Math.max(0, loc[0]);
        int top = Math.max(0, loc[1]);
        int right = Math.min(fullWallpaper.getWidth(), left + blurLayer.getWidth());
        int bottom = Math.min(fullWallpaper.getHeight(), top + blurLayer.getHeight());
        if (right <= left || bottom <= top) {
            blurLayer.clearRegionBitmap();
            return;
        }
        Bitmap region = Bitmap.createBitmap(fullWallpaper, left, top,
                right - left, bottom - top);
        blurLayer.setRegionBitmap(region);
    }

    // ------------------------------------------------------------------ //
    // 尺寸
    // ------------------------------------------------------------------ //

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    // ------------------------------------------------------------------ //
    // 真实高斯模糊背景层
    // ------------------------------------------------------------------ //

    /**
     * 毛玻璃背景层：绘制「壁纸 Dock 区域裁剪位图 + 半透明灰白雾面」。
     *
     * <p>Android 12+（API 31）在构造时对自身设置
     * {@link RenderEffect#createBlurEffect}，整层渲染输出经 GPU 真实高斯模糊，
     * 即呈现对背后壁纸内容的毛玻璃效果；位图边缘由 outline + clipToOutline 裁为
     * 胶囊圆角。低版本不支持 RenderEffect 时降级为静态半透明玻璃渐变。</p>
     *
     * <p><strong>注意</strong>：本层必须走硬件加速渲染（严禁 setLayerType(SOFTWARE)），
     * RenderEffect 依赖硬件渲染管线。</p>
     */
    private static class BlurBackgroundView extends View {

        private final float density;
        private final Paint fogPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint themeTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Bitmap regionBitmap;
        private int themeColor = 0xFF1D4ED8;

        BlurBackgroundView(Context context, float corner) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), corner);
                }
            });
            setClipToOutline(true);
            // 柔和系统阴影（RenderThread 绘制，可跨 bounds，为阴影预留底部留白）
            setElevation(dp(8));

            // 半透明灰白雾面：与顶部悬浮玻璃容器同一玻璃语言，营造毛玻璃质感
            fogPaint.setShader(new LinearGradient(0f, 0f, 0f, dp(HEIGHT_DP + 2f),
                    new int[]{0x26D8DCE0, 0x1AD8DCE0}, null, Shader.TileMode.CLAMP));

            // 弱主题色氛围层：吸色结果叠加在雾面之上（默认品牌蓝），毛玻璃氛围色
            themeTintPaint.setColor(ColorUtils.setAlphaComponent(themeColor, 0x14));

            // 真实高斯模糊：API 31+ 对整层渲染输出做 GPU 模糊
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRenderEffect(RenderEffect.createBlurEffect(dp(BLUR_RADIUS_DP),
                        dp(BLUR_RADIUS_DP), Shader.TileMode.CLAMP));
            }
        }

        private float dp(float v) {
            return v * density;
        }

        /** 更新氛围色（背景吸色结果），触发重绘。 */
        void setThemeColor(int color) {
            themeColor = color;
            themeTintPaint.setColor(ColorUtils.setAlphaComponent(color, 0x14));
            invalidate();
        }

        void setRegionBitmap(Bitmap bmp) {
            if (regionBitmap == bmp) {
                return;
            }
            if (regionBitmap != null && regionBitmap != bmp) {
                regionBitmap.recycle();
            }
            regionBitmap = bmp;
            invalidate();
        }

        void clearRegionBitmap() {
            if (regionBitmap != null) {
                regionBitmap.recycle();
                regionBitmap = null;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            // 1) 壁纸 Dock 区域片段（RenderEffect 已对整层输出做真实高斯模糊）
            if (regionBitmap != null && !regionBitmap.isRecycled()) {
                Rect src = new Rect(0, 0, regionBitmap.getWidth(), regionBitmap.getHeight());
                RectF dst = new RectF(0f, 0f, w, h);
                canvas.drawBitmap(regionBitmap, src, dst, null);
            }
            // 2) 半透明灰白雾面（低版本降级时提供静态玻璃观感）
            canvas.drawRect(0f, 0f, w, h, fogPaint);
            // 3) 弱主题色氛围层（背景吸色结果），与顶部玻璃容器同族毛玻璃氛围色
            canvas.drawRect(0f, 0f, w, h, themeTintPaint);
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
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private Bitmap shadowMask;

        ShadowedImageView(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            // 深色描边：紧贴图标轮廓一圈，保证白色图标在彩色壁纸上清晰可辨
            strokePaint.setShadowLayer(dp(1f), 0f, 0f, 0xB3000000);
            // 明显投影：拉开图标与背景的层次
            shadowPaint.setShadowLayer(dp(4f), 0f, dp(1.5f), 0x99000000);
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
            // 先画描边（紧贴轮廓的深色一圈），再画投影（扩散阴影），最后画原图标
            canvas.drawBitmap(shadowMask, 0f, 0f, strokePaint);
            canvas.drawBitmap(shadowMask, 0f, 0f, shadowPaint);
            super.onDraw(canvas);
        }
    }
}
