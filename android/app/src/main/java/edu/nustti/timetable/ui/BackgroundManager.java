package edu.nustti.timetable.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** 课表自定义背景：图片复制到应用私有目录持久化，SharedPreferences 记录启用状态。 */
public final class BackgroundManager {

    private static final String PREFS = "timetable_bg";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_MODE = "scale_mode";
    private static final String KEY_BLOCK_ALPHA = "block_alpha_percent";
    private static final String FILE_NAME = "background.jpg";
    private static final int MAX_SIDE = 1920;

    /** 课程块透明度默认值（百分比 0-100）：85% 时背景可见且课程文字保持可读。 */
    public static final int DEFAULT_BLOCK_ALPHA_PERCENT = 85;

    /** 背景显示模式：等比例裁切（默认，CENTER_CROP 等价） / 拉伸铺满。 */
    public static final String MODE_CROP = "crop";
    public static final String MODE_STRETCH = "stretch";

    private BackgroundManager() {
    }

    public static boolean hasBackground(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_ENABLED, false) && bgFile(context).exists();
    }

    /** 通过 SAF 选图结果保存为背景（采样压缩后写入私有目录），返回是否成功。 */
    public static boolean save(Context context, Uri uri) {
        try {
            Bitmap bitmap = decodeSampled(context, uri);
            if (bitmap == null) {
                return false;
            }
            File dir = bgDir(context);
            if (!dir.exists() && !dir.mkdirs()) {
                bitmap.recycle();
                return false;
            }
            File target = bgFile(context);
            try (FileOutputStream out = new FileOutputStream(target)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
            } finally {
                bitmap.recycle();
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_ENABLED, true).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 恢复默认背景：删除图片并清除启用状态。 */
    public static void clear(Context context) {
        File file = bgFile(context);
        if (file.exists()) {
            file.delete();
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .clear().apply();
    }

    /** 当前背景显示模式（默认等比例裁切）。 */
    public static String scaleMode(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_MODE, MODE_CROP);
    }

    /** 保存背景显示模式。 */
    public static void setScaleMode(Context context, String mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_MODE, mode).apply();
    }

    /** 当前课程块透明度（百分比 0-100，默认 85）。 */
    public static int blockAlphaPercent(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_BLOCK_ALPHA, DEFAULT_BLOCK_ALPHA_PERCENT);
    }

    /** 保存课程块透明度（百分比 0-100）。 */
    public static void setBlockAlphaPercent(Context context, int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_BLOCK_ALPHA, clamped).apply();
    }

    /** 解码背景位图；未设置背景时返回 null。调用方负责 recycle。 */
    public static Bitmap loadBitmap(Context context) {
        if (!hasBackground(context)) {
            return null;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(bgFile(context).getAbsolutePath(), opts);
    }

    /** 返回「壁纸+加深」背景 Drawable（BgScaleDrawable 之上叠加 0x66000000 半透明遮罩，与 WeekGridView 原绘制效果一致）；
     *  壁纸未启用或解码失败时返回 null。内部位图由 Drawable 持有，调用方 setBackground 后不得 recycle。 */
    public static Drawable backgroundDrawable(Context context) {
        if (!hasBackground(context)) {
            return null;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeFile(bgFile(context).getAbsolutePath(), opts);
        if (bitmap == null) {
            return null;
        }
        boolean crop = MODE_CROP.equals(scaleMode(context));
        return new LayerDrawable(new Drawable[]{
                new BgScaleDrawable(bitmap, crop),
                new ColorDrawable(0x66000000)
        });
    }

    /** 采样解码，避免超大图 OOM。 */
    private static Bitmap decodeSampled(Context context, Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            return BitmapFactory.decodeStream(in, null, opts);
        }
    }

    private static File bgDir(Context context) {
        return new File(context.getFilesDir(), "background");
    }

    private static File bgFile(Context context) {
        return new File(bgDir(context), FILE_NAME);
    }

    /** 背景绘制 Drawable：按显示模式等比例裁切（CENTER_CROP 等价）或拉伸铺满。 */
    public static final class BgScaleDrawable extends android.graphics.drawable.Drawable {
        private final Bitmap bitmap;
        private final boolean crop;

        public BgScaleDrawable(Bitmap bitmap, boolean crop) {
            this.bitmap = bitmap;
            this.crop = crop;
        }

        @Override
        public void draw(Canvas canvas) {
            if (bitmap == null || bitmap.isRecycled()) {
                return;
            }
            Rect bounds = getBounds();
            float vw = bounds.width();
            float vh = bounds.height();
            float bw = bitmap.getWidth();
            float bh = bitmap.getHeight();
            if (vw <= 0 || vh <= 0 || bw <= 0 || bh <= 0) {
                return;
            }
            Rect src = new Rect(0, 0, (int) bw, (int) bh);
            RectF dst;
            if (crop) {
                float scale = Math.max(vw / bw, vh / bh);
                float dw = bw * scale;
                float dh = bh * scale;
                dst = new RectF((vw - dw) / 2f, (vh - dh) / 2f,
                        (vw - dw) / 2f + dw, (vh - dh) / 2f + dh);
            } else {
                dst = new RectF(0, 0, vw, vh);
            }
            canvas.drawBitmap(bitmap, src, dst, null);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }
}
