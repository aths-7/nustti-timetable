package edu.nustti.timetable.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** 课表自定义背景：图片复制到应用私有目录持久化，SharedPreferences 记录启用状态。 */
public final class BackgroundManager {

    private static final String PREFS = "timetable_bg";
    private static final String KEY_ENABLED = "enabled";
    private static final String FILE_NAME = "background.jpg";
    private static final int MAX_SIDE = 1920;

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

    /** 解码背景位图；未设置背景时返回 null。调用方负责 recycle。 */
    public static Bitmap loadBitmap(Context context) {
        if (!hasBackground(context)) {
            return null;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(bgFile(context).getAbsolutePath(), opts);
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
}
