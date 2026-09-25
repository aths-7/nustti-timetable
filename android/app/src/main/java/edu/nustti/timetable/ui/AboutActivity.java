package edu.nustti.timetable.ui;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import edu.nustti.timetable.R;
import edu.nustti.timetable.api.Async;

/**
 * 关于与更新详情页：应用版本号、检查更新（GitHub Releases / ci-artifacts）、开发者信息、开源仓库链接。
 *
 * <p>由设置页「关于与更新」次级菜单项进入，返回可回到设置页。检查更新逻辑自设置页原实现迁移。</p>
 */
public class AboutActivity extends AppCompatActivity {

    private static final String UPDATE_UA =
            "Mozilla/5.0 (Linux; Android 13) nustti-timetable-update-check";
    private static final String API_RELEASES_LATEST =
            "https://api.github.com/repos/aths-7/nustti-timetable/releases/latest";
    private static final String RAW_VERSION_JSON =
            "https://raw.githubusercontent.com/aths-7/nustti-timetable/ci-artifacts/version.json";
    private static final String RAW_APK_PREFIX =
            "https://raw.githubusercontent.com/aths-7/nustti-timetable/ci-artifacts/";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        TextView tvVersion = findViewById(R.id.tvVersion);
        tvVersion.setText("版本 " + versionName());

        // 返回设置页
        TextView btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        // 检查更新：优先 GitHub Releases latest，无 Release 则回退 ci-artifacts 分支 version.json
        Button btnCheckUpdate = findViewById(R.id.btnCheckUpdate);
        btnCheckUpdate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkUpdate(btnCheckUpdate);
            }
        });

        // 开源仓库：点击打开 GitHub
        TextView tvRepo = findViewById(R.id.tvRepo);
        tvRepo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/aths-7/nustti-timetable")));
                } catch (Exception e) {
                    toast("无法打开浏览器，请手动访问：\nhttps://github.com/aths-7/nustti-timetable");
                }
            }
        });
    }

    // ------------------------------------------------------------------ //
    // 版本号
    // ------------------------------------------------------------------ //

    private String versionName() {
        try {
            PackageInfo info = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            return info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private long versionCode() {
        try {
            PackageInfo info = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                return info.getLongVersionCode();
            }
            return info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ //
    // 检查更新（自设置页迁移）
    // ------------------------------------------------------------------ //

    private void checkUpdate(final Button btn) {
        btn.setEnabled(false);
        Async.run(new Async.Task<UpdateCheckResult>() {
            @Override
            public UpdateCheckResult run() throws Exception {
                return fetchLatest();
            }
        }, new Async.Done<UpdateCheckResult>() {
            @Override
            public void onResult(UpdateCheckResult result) {
                btn.setEnabled(true);
                if (result == null || result.versionName == null) {
                    toast("检查更新失败：接口返回异常");
                    return;
                }
                if (isNewer(result)) {
                    showUpdateDialog(result);
                } else {
                    toast("已是最新版本");
                }
            }
        }, new Async.Fail() {
            @Override
            public void onError(Exception e) {
                btn.setEnabled(true);
                String m = e == null || e.getMessage() == null ? "未知错误" : e.getMessage();
                toast("检查更新失败：" + (m.length() > 60 ? m.substring(0, 60) : m));
            }
        });
    }

    /** 子线程：先查 GitHub Releases，无 Release（404）则回退 ci-artifacts/version.json。 */
    private UpdateCheckResult fetchLatest() throws Exception {
        HttpGetResult release = httpGet(API_RELEASES_LATEST, "application/vnd.github+json");
        if (release.code == 200) {
            JsonObject obj = JsonParser.parseString(release.body).getAsJsonObject();
            String tag = stringOf(obj, "tag_name");
            String versionName = normalizeVersion(tag);
            String notes = stringOf(obj, "body");
            String download = null;
            if (obj.has("assets") && obj.get("assets").isJsonArray()) {
                for (JsonElement el : obj.getAsJsonArray("assets")) {
                    JsonObject asset = el.getAsJsonObject();
                    String name = stringOf(asset, "name");
                    if (name != null && name.toLowerCase().endsWith(".apk")) {
                        download = stringOf(asset, "browser_download_url");
                        break;
                    }
                }
            }
            if (download == null) {
                download = "https://github.com/aths-7/nustti-timetable/releases/latest";
            }
            return new UpdateCheckResult(0, versionName, notes, download);
        }
        if (release.code != 404) {
            throw new RuntimeException("GitHub Releases 接口返回 " + release.code);
        }
        HttpGetResult raw = httpGet(RAW_VERSION_JSON, null);
        if (raw.code != 200) {
            throw new RuntimeException("版本信息文件不可用（HTTP " + raw.code + "）");
        }
        JsonObject obj = JsonParser.parseString(raw.body).getAsJsonObject();
        String versionName = normalizeVersion(stringOf(obj, "versionName"));
        long versionCode = obj.has("versionCode") && obj.get("versionCode").isJsonPrimitive()
                ? obj.get("versionCode").getAsLong() : 0;
        String notes = stringOf(obj, "releaseNotes");
        String apk = stringOf(obj, "apkFileName");
        String download = apk == null || apk.isEmpty()
                ? "https://github.com/aths-7/nustti-timetable/tree/ci-artifacts"
                : RAW_APK_PREFIX + apk;
        return new UpdateCheckResult(versionCode, versionName, notes, download);
    }

    private boolean isNewer(UpdateCheckResult remote) {
        long localCode = versionCode();
        if (remote.versionCode > 0 && localCode > 0) {
            return remote.versionCode > localCode;
        }
        return compareVersion(remote.versionName, normalizeVersion(versionName())) > 0;
    }

    private void showUpdateDialog(final UpdateCheckResult result) {
        String title = "发现新版本 " + result.versionName;
        String message = "当前版本：" + versionName() + "\n\n更新说明：\n"
                + (result.notes == null || result.notes.trim().isEmpty() ? "（暂无说明）" : result.notes.trim())
                + "\n\n点击「下载更新」将打开浏览器下载 APK。";
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("下载更新", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(result.downloadUrl)));
                        } catch (Exception e) {
                            toast("无法打开浏览器，请手动访问：\n" + result.downloadUrl);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static HttpGetResult httpGet(String urlStr, String accept) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", UPDATE_UA);
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            if (accept != null) {
                conn.setRequestProperty("Accept", accept);
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = readAll(in);
            return new HttpGetResult(code, body);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }

    private static String stringOf(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }

    /** 去掉版本号前的非数字前缀（如 "v2.1.8" -> "2.1.8"）。 */
    private static String normalizeVersion(String v) {
        if (v == null || v.isEmpty()) {
            return "";
        }
        String s = v.trim();
        int i = 0;
        while (i < s.length() && !Character.isDigit(s.charAt(i))) {
            i++;
        }
        return s.substring(i);
    }

    private static int compareVersion(String a, String b) {
        String[] as = a.split("\\.");
        String[] bs = b.split("\\.");
        int len = Math.max(as.length, bs.length);
        for (int i = 0; i < len; i++) {
            int x = i < as.length ? parseSegment(as[i]) : 0;
            int y = i < bs.length ? parseSegment(bs[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static int parseSegment(String s) {
        String clean = s == null ? "" : s.replaceAll("\\D.*", "");
        return clean.isEmpty() ? 0 : Integer.parseInt(clean);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ //

    private static final class UpdateCheckResult {
        final long versionCode;
        final String versionName;
        final String notes;
        final String downloadUrl;

        UpdateCheckResult(long versionCode, String versionName, String notes, String downloadUrl) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.notes = notes;
            this.downloadUrl = downloadUrl;
        }
    }

    private static final class HttpGetResult {
        final int code;
        final String body;

        HttpGetResult(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }
}
