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
import android.graphics.Typeface;
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
 * {@link RenderEffect#createBlurEffect} 真实高斯模糊 + 半透明深色雾面」三层叠加。
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
    /** 圆角雾面 mask：独立于模糊层，负责呈现胶囊圆角形状与深色雾面渐变（不参与 RenderEffect 模糊，
     *  避免模糊层 alpha 边缘软化导致左右露白 / 底部断开）。 */
    private BlurMaskView blurMask;
    private LinearLayout contentRow;
    private final View[] itemViews = new View[ITEM_COUNT];
    private final View[] itemContents = new View[ITEM_COUNT];
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
        // 关闭子视图裁剪：选中项弹性放大上浮时，胶囊/图标超出自身 bounds 仍完整绘制，顶部不被裁切
        setClipChildren(false);
        setClipToPadding(false);
        float corner = dp(CORNER_DP);

        // 模糊背景层：真实高斯模糊毛玻璃（壁纸区域裁剪 + RenderEffect 模糊）。
        // 本层为全宽矩形（无圆角、无阴影）：RenderEffect 对整层输出（含 alpha）做高斯模糊，
        // 若像旧版一样带圆角 outline，模糊会把圆角/直边 alpha 边缘软化，透出下方亮背景形成
        // 左右露白；矩形铺满后模糊边缘落在屏幕左右边缘（不可见），圆角形状改由上层 blurMask 呈现
        blurLayer = new BlurBackgroundView(context);
        FrameLayout.LayoutParams blurLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (int) dp(HEIGHT_DP) + (int) dp(2) + (int) dp(14));
        blurLp.topMargin = (int) dp(3);
        addView(blurLayer, blurLp);

        // 圆角雾面 mask：清晰圆角裁剪（30dp），绘制深色雾面渐变 + 主题色氛围层。
        // 独立于模糊层，保证胶囊边缘不被 RenderEffect 模糊软化；左右 margin 32dp
        // （= 原根布局 paddingStart/End 24dp + 旧 margin 8dp），与图标行对齐
        blurMask = new BlurMaskView(context, corner);
        FrameLayout.LayoutParams maskLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (int) dp(HEIGHT_DP) + (int) dp(2) + (int) dp(14));
        maskLp.leftMargin = (int) dp(32);
        maskLp.rightMargin = (int) dp(32);
        maskLp.topMargin = (int) dp(3);
        addView(blurMask, maskLp);

        // 内容行：三个图标（整周 / 今日 / 设置）
        // item 等宽均分（weight=1）占满 Dock 横向宽度，左右 margin 与 blurMask 对齐（均 32dp）
        // 实现左右对称；行高与 blurMask 完全一致且顶部对齐（topMargin 3dp），
        // 消除胶囊顶部与玻璃层错位导致的露白断层
        contentRow = new LinearLayout(context);
        contentRow.setOrientation(LinearLayout.HORIZONTAL);
        contentRow.setGravity(Gravity.CENTER);
        // 关闭子视图裁剪：选中项弹性放大溢出 item 边界时不被 contentRow 裁切
        contentRow.setClipChildren(false);
        FrameLayout.LayoutParams rowLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (int) dp(HEIGHT_DP) + (int) dp(2) + (int) dp(14));
        rowLp.gravity = Gravity.TOP;
        rowLp.topMargin = (int) dp(3);
        rowLp.leftMargin = (int) dp(32);
        rowLp.rightMargin = (int) dp(32);
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
        // item：等宽均分占满 Dock 横向宽度（weight=1），三组左右对称；
        // 仅作为「点击区域 + 缩放动画宿主」，胶囊背景不挂在 item 上
        FrameLayout item = new FrameLayout(context);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        item.setLayoutParams(lp);
        item.setClickable(true);
        item.setFocusable(true);

        // 内容容器：图标+文字+指示点整体作为「胶囊包裹区域」，
        // 选中时仅此容器挂蓝色胶囊背景——胶囊只包裹本组内容（wrap_content 宽），
        // 不横跨整行/整格宽度；容器在 item 内水平垂直居中
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding((int) dp(12), (int) dp(8), (int) dp(12), (int) dp(4));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.CENTER;
        content.setLayoutParams(clp);
        itemContents[index] = content;
        item.addView(content);

        ImageView icon = new ShadowedImageView(context);
        icon.setImageResource(iconRes);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams((int) dp(26), (int) dp(26));
        icon.setLayoutParams(ilp);
        icon.setColorFilter(Color.parseColor("#FFFFFF"));
        itemIcons[index] = icon;
        content.addView(icon);

        TextView label = new TextView(context);
        label.setText(labelText);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        label.setTextColor(Color.parseColor("#F1F5F9"));
        label.setGravity(Gravity.CENTER);
        // 黑色描影：Dock 悬浮于壁纸之上，加阴影保证浅色小字在亮/暗背景上都清晰可读
        label.setShadowLayer(dp(2f), 0f, dp(1f), 0x8C000000);
        itemLabels[index] = label;
        // 图标与文字间距紧凑：仅 1dp 过渡，避免视觉空隙
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        llp.topMargin = (int) dp(1);
        label.setLayoutParams(llp);
        content.addView(label);

        View dot = new View(context);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(Color.parseColor("#FFFFFF"));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams((int) dp(6), (int) dp(3));
        dlp.topMargin = (int) dp(2);
        dot.setLayoutParams(dlp);
        dot.setBackground(dotBg);
        dot.setVisibility(View.INVISIBLE);
        itemDots[index] = dot;
        content.addView(dot);

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
        if (blurMask != null) {
            blurMask.setThemeColor(color);
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
                // 胶囊背景挂到「内容容器」上：仅包裹本组图标+文字，不横跨整格宽度
                itemContents[i].setBackground(capsuleBg);
                icon.setColorFilter(Color.parseColor("#FFFFFF"));
                label.setTextColor(Color.parseColor("#FFFFFF"));
                // 选中项文字加粗 + 更重描影，视觉权重明显高于未选中项
                label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                label.setShadowLayer(dp(2f), 0f, dp(1f), 0xAA000000);
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
                itemContents[i].setBackground(null);
                item.animate().cancel();
                item.animate().scaleX(1f).scaleY(1f).translationY(0f)
                        .setDuration(180).start();
                icon.setColorFilter(Color.parseColor("#FFFFFF"));
                label.setTextColor(Color.parseColor("#F1F5F9"));
                label.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
                label.setShadowLayer(dp(2f), 0f, dp(1f), 0x8C000000);
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
            // 尺寸（如 insets / 分屏 / 窗口 resize）变化后：先校验全屏快照与父容器尺寸
            // 是否仍一致。旋转 / 分屏 / resize 后旧快照尺寸过期，若仅按新位置裁剪会导致
            // 坐标错位、旧帧残影、模糊源露底，须重建全屏快照 + 裁剪区域
            ViewGroup parent = (ViewGroup) getParent();
            if (parent == null || fullWallpaper == null
                    || fullWallpaper.getWidth() != parent.getWidth()
                    || fullWallpaper.getHeight() != parent.getHeight()) {
                refreshBlurBackground();
            } else {
                // 尺寸变化但父容器未变（如 Dock 自身 inset 调整）：仅按新位置重建裁剪区域
                rebuildBlurRegion();
            }
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
     * 模糊内容层：绘制「壁纸 Dock 区域裁剪位图」，整层经 RenderEffect 真实高斯模糊。
     *
     * <p>本层为全宽矩形，<strong>不带圆角 outline、不设 elevation</strong>：
     * RenderEffect 是对整层渲染输出（含 alpha 通道）做 GPU 高斯模糊，若带圆角裁剪，
     * 模糊会把边缘 alpha 软化，透出下方亮背景形成左右露白；矩形铺满后模糊边缘落在
     * 屏幕左右边缘，肉眼不可见。胶囊圆角形状与深色雾面由上层 {@link BlurMaskView} 呈现。</p>
     *
     * <p><strong>注意</strong>：本层必须走硬件加速渲染（严禁 setLayerType(SOFTWARE)），
     * RenderEffect 依赖硬件渲染管线。</p>
     */
    private static class BlurBackgroundView extends View {

        private final float density;
        private Bitmap regionBitmap;

        BlurBackgroundView(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;

            // 真实高斯模糊：API 31+ 对整层渲染输出做 GPU 模糊
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRenderEffect(RenderEffect.createBlurEffect(dp(BLUR_RADIUS_DP),
                        dp(BLUR_RADIUS_DP), Shader.TileMode.CLAMP));
            }
        }

        private float dp(float v) {
            return v * density;
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
            // 壁纸 Dock 区域片段（RenderEffect 已对整层输出做真实高斯模糊）
            if (regionBitmap != null && !regionBitmap.isRecycled()) {
                Rect src = new Rect(0, 0, regionBitmap.getWidth(), regionBitmap.getHeight());
                RectF dst = new RectF(0f, 0f, w, h);
                canvas.drawBitmap(regionBitmap, src, dst, null);
            }
        }
    }

    /**
     * 圆角雾面 mask：绘制「半透明深色雾面渐变 + 弱主题色氛围层」，带清晰圆角裁剪。
     *
     * <p>独立于模糊层，自身不挂 RenderEffect：胶囊圆角 / 直边边缘不被模糊软化，
     * 左右两侧 margin 区由下方全宽模糊层（与背景同色）填充，与背景完全连续无露白。
     * 不设 elevation，避免 paddingBottom 区出现更黑横向断开带。</p>
     */
    private static class BlurMaskView extends View {

        private final float density;
        private final float corner;
        private final Paint fogPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint themeTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int themeColor = 0xFF1D4ED8;

        BlurMaskView(Context context, float corner) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            this.corner = corner;
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                            BlurMaskView.this.corner);
                }
            });
            setClipToOutline(true);

            // 半透明深色玻璃雾面：与全屏背景「壁纸 + 0x66000000 深色遮罩」同族观感，
            // 消除浅灰白雾面导致的左右边缘白色矩形块露白；
            // 注意不透明度必须远低于背景遮罩（0x66）：背景已叠 40% 黑，雾面若再叠
            // 40% 深蓝黑，Dock 在深色壁纸下会发黑断层、圆角边界线可见；故顶部 18%、
            // 底部 8% 渐浅，深浅背景下均呈「略亮于背景的毛玻璃」而非黑块或白块
            fogPaint.setShader(new LinearGradient(0f, 0f, 0f, dp(HEIGHT_DP + 2f),
                    new int[]{0x2E101A28, 0x14101A28}, null, Shader.TileMode.CLAMP));

            // 弱主题色氛围层：吸色结果叠加在雾面之上（默认品牌蓝），毛玻璃氛围色
            themeTintPaint.setColor(ColorUtils.setAlphaComponent(themeColor, 0x14));
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

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            // 1) 半透明深色雾面（低版本降级时提供静态玻璃观感）
            canvas.drawRect(0f, 0f, w, h, fogPaint);
            // 2) 弱主题色氛围层（背景吸色结果），与顶部玻璃容器同族毛玻璃氛围色
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
        private final float density;
        private Bitmap shadowMask;

        ShadowedImageView(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            // 标准柔和阴影：单层绘制「白色图标本体 + 黑色柔和投影」。
            // 本体与阴影同一次 drawBitmap 完成（paint 自带 setShadowLayer），
            // 无描边剪影层、无第二层蒙版叠加，从根源杜绝重影与白色错位剪影
            shadowPaint.setColor(Color.WHITE);
            shadowPaint.setShadowLayer(dp(2f), 0f, dp(1f), 0x66000000);
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
                shadowMask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
                Canvas mc = new Canvas(shadowMask);
                // 手动按 fitCenter 布局图标（与 ImageView 默认缩放一致并强制居中），
                // 避免早期 bounds 未居中/尺寸不匹配导致蒙版与原图标错位
                int dw = d.getIntrinsicWidth();
                int dh = d.getIntrinsicHeight();
                if (dw <= 0 || dh <= 0) {
                    dw = w;
                    dh = h;
                }
                float scale = Math.min((float) w / dw, (float) h / dh);
                int cw = Math.round(dw * scale);
                int ch = Math.round(dh * scale);
                d.setBounds((w - cw) / 2, (h - ch) / 2,
                        (w - cw) / 2 + cw, (h - ch) / 2 + ch);
                d.draw(mc);
            }
            // 单层绘制：本体（白）与柔和投影同源同层，无任何错位/重影可能
            canvas.drawBitmap(shadowMask, 0f, 0f, shadowPaint);
        }
    }
}
