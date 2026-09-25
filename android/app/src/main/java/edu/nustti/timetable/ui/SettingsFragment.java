package edu.nustti.timetable.ui;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
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

import androidx.fragment.app.Fragment;

import com.google.android.material.textfield.TextInputEditText;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import edu.nustti.timetable.R;
import edu.nustti.timetable.data.SessionStore;
import edu.nustti.timetable.edu.JwglSession;
import edu.nustti.timetable.model.TimetableResult;

/** 设置页：教育信息（学号/周次/学期）、功能模块、外观/本地缓存/数据来源/关于与更新次级菜单入口。 */
public class SettingsFragment extends Fragment implements MainActivity.DataListener {

    private TextInputEditText etStudentId;
    private EditText etWeek;
    private TextView tvWeekRange;
    private Spinner spTerm;

    private TimetableResult data;
    private int week = 1;
    private boolean suppressTermCallback = true;

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
        spTerm = view.findViewById(R.id.spTerm);

        TextView tvAboutVersion = view.findViewById(R.id.tvAboutVersion);
        tvAboutVersion.setText("v" + versionName());

        etStudentId.setText(store.getStudentId());
        etWeek.setText(String.valueOf(store.getCurrentWeek()));

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

        // 外观：次级菜单入口，点击打开独立详情页（背景/字体/透明度）
        View rowAppearance = view.findViewById(R.id.rowAppearance);
        rowAppearance.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(requireContext(), AppearanceActivity.class));
            }
        });

        // 关于与更新：次级菜单项，点击打开独立详情页（版本号、检查更新、开发者信息、开源仓库）
        View rowAbout = view.findViewById(R.id.rowAbout);
        rowAbout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(requireContext(), AboutActivity.class));
            }
        });

        // 本地缓存：次级菜单入口，点击打开独立详情页（缓存统计与清除）
        View rowCache = view.findViewById(R.id.rowCache);
        rowCache.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(requireContext(), CacheActivity.class));
            }
        });

        // 数据来源：次级菜单入口，点击打开独立详情页（来源说明/接口状态/退出登录）
        View rowDataSource = view.findViewById(R.id.rowDataSource);
        rowDataSource.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(requireContext(), DataSourceActivity.class));
            }
        });

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
        updateSummaries();
    }

    /** 刷新三个次级菜单入口的概要副标题（外观 / 本地缓存 / 数据来源）。 */
    private void updateSummaries() {
        MainActivity main = (MainActivity) requireActivity();
        SessionStore store = main.repository().store();

        // 外观概要：背景状态 + 当前字体
        boolean hasBg = BackgroundManager.hasBackground(requireContext());
        String bgText = hasBg ? "自定义背景" : "默认背景";
        String fontText = fontLabel(store.getCourseFont());
        TextView tvAppearanceSummary = getView().findViewById(R.id.tvAppearanceSummary);
        tvAppearanceSummary.setText(bgText + " · 字体 " + fontText);

        // 缓存概要：来源 + 时间
        long at = store.cacheTime();
        String time = at <= 0 ? "无" : new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                .format(new Date(at));
        String fromText = cacheFromLabel(store.cacheFrom());
        TextView tvCacheSummary = getView().findViewById(R.id.tvCacheSummary);
        tvCacheSummary.setText(fromText + " · 更新于 " + time);

        // 数据来源概要：接口状态 + 学期
        String term = store.getTermLabel().isEmpty() ? store.getTerm() : store.getTermLabel();
        String termText = term.isEmpty() ? "未选择学期" : term;
        String loginText = JwglSession.ready() ? "已登录" : "未登录";
        TextView tvDataSourceSummary = getView().findViewById(R.id.tvDataSourceSummary);
        tvDataSourceSummary.setText("直连教务官网 · " + loginText + " · " + termText);
    }

    private static String fontLabel(String font) {
        if (SessionStore.FONT_SERIF.equals(font)) {
            return "宋体";
        } else if (SessionStore.FONT_SANS.equals(font)) {
            return "黑体";
        } else if (SessionStore.FONT_FANGSONG.equals(font)) {
            return "仿宋";
        } else if (SessionStore.FONT_KAITI.equals(font)) {
            return "楷体";
        }
        return "默认";
    }

    private static String cacheFromLabel(String from) {
        if ("demo".equals(from)) {
            return "演示数据";
        } else if ("jwgl".equals(from) || "server".equals(from)) {
            return "教务官网";
        }
        return "未知";
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

    private String text(EditText editText) {
        return editText.getText() == null ? "" : editText.getText().toString().trim();
    }

    private void toast(String message) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ //
    // 版本号：设置页"关于与更新"入口行展示
    // ------------------------------------------------------------------ //

    private String versionName() {
        try {
            PackageInfo info = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0);
            return info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }
}
