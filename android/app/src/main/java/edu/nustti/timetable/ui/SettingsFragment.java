package edu.nustti.timetable.ui;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;

import com.google.android.material.textfield.TextInputEditText;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import edu.nustti.timetable.R;
import edu.nustti.timetable.api.Async;
import edu.nustti.timetable.data.SessionStore;
import edu.nustti.timetable.edu.JwglSession;
import edu.nustti.timetable.model.TimetableResult;

/** 设置页：账号、当前周次、学期切换、缓存信息与数据来源（课表直连教务系统官网）。 */
public class SettingsFragment extends Fragment implements MainActivity.DataListener {

    private TextInputEditText etStudentId;
    private EditText etWeek;
    private TextView tvWeekRange;
    private TextView tvCacheInfo;
    private TextView tvBgStatus;
    private Spinner spTerm;

    private TimetableResult data;
    private int week = 1;
    private boolean suppressTermCallback = true;

    private final ActivityResultLauncher<String> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    handleBackgroundPicked(uri);
                }
            });

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        final MainActivity main = (MainActivity) requireActivity();
        final SessionStore store = main.repository().store();

        etStudentId = view.findViewById(R.id.etStudentId);
        etWeek = view.findViewById(R.id.etWeek);
        tvWeekRange = view.findViewById(R.id.tvWeekRange);
        tvCacheInfo = view.findViewById(R.id.tvCacheInfo);
        tvBgStatus = view.findViewById(R.id.tvBgStatus);
        spTerm = view.findViewById(R.id.spTerm);

        TextView tvVersion = view.findViewById(R.id.tvVersion);
        tvVersion.setText("版本 " + versionName());

        etStudentId.setText(store.getStudentId());
        etWeek.setText(String.valueOf(store.getCurrentWeek()));

        TextView tvApiList = view.findViewById(R.id.tvApiList);
        tvApiList.setText("数据来源：南京理工大学泰州科技学院教务系统官网\n"
                + "地址：https://jwgl.nustti.edu.cn/jsxsd/\n"
                + "登录：POST /jsxsd/xk/LoginToXk（学号 + 密码，无验证码）\n"
                + "课表：GET /jsxsd/xskb/xskb_list.do?xnxq01id=学期\n"
                + "说明：手机端直连官网，不经过任何中间服务端");

        Button btnSave = view.findViewById(R.id.btnSave);
        Button btnRefresh = view.findViewById(R.id.btnRefresh);
        Button btnDemo = view.findViewById(R.id.btnDemo);
        Button btnLogout = view.findViewById(R.id.btnLogout);

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                store.setStudentId(text(etStudentId));
                String weekText = text(etWeek);
                int value = week;
                try {
                    if (!weekText.isEmpty()) {
                        value = Integer.parseInt(weekText);
                    }
                } catch (NumberFormatException ignored) {
                    value = week;
                }
                int max = data == null ? 20 : data.maxWeek();
                value = Math.max(1, Math.min(max, value));
                store.setCurrentWeek(value);
                week = value;
                etWeek.setText(String.valueOf(value));
                main.setWeek(value);
                toast("设置已保存");
            }
        });

        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                main.refresh(false);
            }
        });
        btnDemo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                main.refresh(true);
            }
        });
        btnLogout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                main.confirmLogout();
            }
        });

        Button btnCheckUpdate = view.findViewById(R.id.btnCheckUpdate);
        btnCheckUpdate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkUpdate(btnCheckUpdate);
            }
        });

        Button btnPickBackground = view.findViewById(R.id.btnPickBackground);
        Button btnResetBackground = view.findViewById(R.id.btnResetBackground);
        btnPickBackground.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickImageLauncher.launch("image/*");
            }
        });
        btnResetBackground.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BackgroundManager.clear(requireContext());
                main.notifyBackgroundChanged();
                updateBgStatus();
                toast("已恢复默认背景");
            }
        });

        Spinner spBgMode = view.findViewById(R.id.spBgMode);
        ArrayAdapter<String> bgModeAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item,
                new String[]{"等比例裁切（推荐）", "拉伸铺满"});
        bgModeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spBgMode.setAdapter(bgModeAdapter);
        spBgMode.setSelection(
                BackgroundManager.MODE_CROP.equals(BackgroundManager.scaleMode(requireContext())) ? 0 : 1);
        spBgMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String mode = position == 0 ? BackgroundManager.MODE_CROP : BackgroundManager.MODE_STRETCH;
                if (mode.equals(BackgroundManager.scaleMode(requireContext()))) {
                    return;
                }
                BackgroundManager.setScaleMode(requireContext(), mode);
                main.notifyBackgroundChanged();
                updateBgStatus();
                toast("背景显示模式已切换");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        updateBgStatus();

        spTerm.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressTermCallback || data == null || data.terms == null
                        || position >= data.terms.size()) {
                    return;
                }
                TimetableResult.TermOption option = data.terms.get(position);
                if (option.value.equals(store.getTerm())) {
                    return;
                }
                store.setTerm(option.value, option.label);
                main.refresh(false);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        ((MainActivity) requireActivity()).requestData(this);
    }

    @Override
    public void onTimetable(TimetableResult data, int week) {
        this.data = data;
        this.week = week;

        MainActivity main = (MainActivity) requireActivity();
        if (etStudentId.getText() == null || etStudentId.getText().toString().trim().isEmpty()) {
            etStudentId.setText(main.repository().store().getStudentId());
        }
        if (etWeek.getText() == null || etWeek.getText().toString().trim().isEmpty()) {
            etWeek.setText(String.valueOf(week));
        }

        int max = data == null ? 20 : data.maxWeek();
        tvWeekRange.setText("可填 1 - " + max + "（当前第 " + week + " 周）");

        fillTerms(data);
        updateCacheInfo();
    }

    private void fillTerms(TimetableResult result) {
        List<String> labels = new ArrayList<>();
        int selected = 0;
        String currentTerm = ((MainActivity) requireActivity()).repository().store().getTerm();
        if (result != null && result.terms != null) {
            for (int i = 0; i < result.terms.size(); i++) {
                TimetableResult.TermOption option = result.terms.get(i);
                labels.add(option.toString());
                if (option.value.equals(currentTerm)) {
                    selected = i;
                }
            }
        }
        if (labels.isEmpty()) {
            labels.add(result == null || result.term == null || result.term.isEmpty()
                    ? "（未获取到学期列表）" : result.term);
        }
        suppressTermCallback = true;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spTerm.setAdapter(adapter);
        spTerm.setSelection(Math.min(selected, labels.size() - 1));
        suppressTermCallback = false;
    }

    private void updateCacheInfo() {
        MainActivity main = (MainActivity) requireActivity();
        SessionStore store = main.repository().store();
        long at = store.cacheTime();
        String time = at <= 0 ? "无" : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date(at));
        String from = store.cacheFrom();
        String fromText;
        if ("demo".equals(from)) {
            fromText = "演示数据";
        } else if ("jwgl".equals(from) || "server".equals(from)) {
            fromText = "教务系统官网";
        } else {
            fromText = "未知";
        }
        int count = data == null ? 0 : data.courses.size();
        int max = data == null ? 0 : data.maxWeek();
        String term = store.getTermLabel().isEmpty() ? store.getTerm() : store.getTermLabel();
        tvCacheInfo.setText("来源：" + fromText + "\n"
                + "缓存时间：" + time + "\n"
                + "课程数：" + count + " 门\n"
                + "周次范围：1 - " + max + " 周\n"
                + "当前学期：" + (term.isEmpty() ? "（未选择）" : term) + "\n"
                + "教务系统登录态：" + (JwglSession.ready() ? "已登录" : "未登录（刷新时自动重新登录）"));
    }

    /** SAF 选图结果：保存为背景并刷新课表页。 */
    private void handleBackgroundPicked(Uri uri) {
        boolean ok = BackgroundManager.save(requireContext(), uri);
        if (ok) {
            ((MainActivity) requireActivity()).notifyBackgroundChanged();
            updateBgStatus();
            toast("背景图片已应用");
        } else {
            toast("背景图片设置失败，请换一张图片重试");
        }
    }

    private void updateBgStatus() {
        boolean has = BackgroundManager.hasBackground(requireContext());
        String mode = BackgroundManager.MODE_CROP.equals(BackgroundManager.scaleMode(requireContext()))
                ? "等比例裁切" : "拉伸铺满";
        tvBgStatus.setText(has ? "背景：已启用自定义背景（" + mode + "）" : "背景：默认（" + mode + "）");
    }

    private String text(EditText editText) {
        return editText.getText() == null ? "" : editText.getText().toString().trim();
    }

    private void toast(String message) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ //
    // 检查更新：优先 GitHub Releases latest，无 Release 则回退 ci-artifacts 分支 version.json
    // ------------------------------------------------------------------ //

    private static final String UPDATE_UA =
            "Mozilla/5.0 (Linux; Android 13) nustti-timetable-update-check";
    private static final String API_RELEASES_LATEST =
            "https://api.github.com/repos/aths-7/nustti-timetable/releases/latest";
    private static final String RAW_VERSION_JSON =
            "https://raw.githubusercontent.com/aths-7/nustti-timetable/ci-artifacts/version.json";
    private static final String RAW_APK_PREFIX =
            "https://raw.githubusercontent.com/aths-7/nustti-timetable/ci-artifacts/";

    private String versionName() {
        try {
            PackageInfo info = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0);
            return info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private long versionCode() {
        try {
            PackageInfo info = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0);
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                return info.getLongVersionCode();
            }
            return info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

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
        new AlertDialog.Builder(requireContext())
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
