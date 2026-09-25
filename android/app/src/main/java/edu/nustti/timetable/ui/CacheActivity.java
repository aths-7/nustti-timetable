package edu.nustti.timetable.ui;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import edu.nustti.timetable.R;
import edu.nustti.timetable.data.SessionStore;
import edu.nustti.timetable.edu.JwglSession;

/** 本地缓存详情页：缓存占用统计与"清除缓存"操作（应用沙盒内，用户确认后执行），返回可回设置页。 */
public class CacheActivity extends AppCompatActivity {

    private TextView tvCacheStats;
    private SessionStore store;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cache);
        store = new SessionStore(this);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        tvCacheStats = findViewById(R.id.tvCacheStats);
        refreshStats();

        Button btnClearCache = findViewById(R.id.btnClearCache);
        btnClearCache.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClear();
            }
        });
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("清除缓存")
                .setMessage("将删除应用缓存目录内容与课表缓存，不影响账号、背景图片等个人数据。确定清除吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("清除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        deleteContents(getCacheDir());
                        store.clearTimetable();
                        refreshStats();
                        toast("缓存已清除");
                    }
                })
                .show();
    }

    private void refreshStats() {
        long cacheDirBytes = sizeOf(getCacheDir());
        long filesDirBytes = sizeOf(getFilesDir());
        String cacheJson = store.loadTimetableJson();
        long timetableBytes = cacheJson == null ? 0 : cacheJson.getBytes().length;
        long at = store.cacheTime();
        String time = at <= 0 ? "无" : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date(at));
        String from = cacheFromLabel(store.cacheFrom());
        tvCacheStats.setText("应用缓存目录：约 " + formatSize(cacheDirBytes) + "\n"
                + "应用数据目录：约 " + formatSize(filesDirBytes) + "\n"
                + "课表数据缓存：约 " + formatSize(timetableBytes) + "\n"
                + "缓存来源：" + from + "\n"
                + "缓存时间：" + time + "\n"
                + "教务系统登录态：" + (JwglSession.ready() ? "已登录" : "未登录"));
    }

    /** 删除目录内的全部内容（保留目录本身），仅用于应用沙盒缓存目录。 */
    private static void deleteContents(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            deleteRecursive(child);
        }
    }

    private static void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    private static long sizeOf(File dir) {
        if (dir == null || !dir.exists()) {
            return 0;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        long total = 0;
        for (File child : children) {
            if (child.isDirectory()) {
                total += sizeOf(child);
            } else {
                total += child.length();
            }
        }
        return total;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.CHINA, "%.2f MB", bytes / (1024.0 * 1024.0));
    }

    private static String cacheFromLabel(String from) {
        if ("demo".equals(from)) {
            return "演示数据";
        } else if ("jwgl".equals(from) || "server".equals(from)) {
            return "教务系统官网";
        }
        return "未知";
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
