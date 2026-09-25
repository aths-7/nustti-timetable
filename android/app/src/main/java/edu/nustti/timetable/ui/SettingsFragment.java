package edu.nustti.timetable.ui;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import edu.nustti.timetable.R;
import edu.nustti.timetable.data.SessionStore;
import edu.nustti.timetable.edu.JwglSession;
import edu.nustti.timetable.model.TimetableResult;

/** 设置页：五个次级菜单入口（教育信息 / 外观 / 关于与更新 / 本地缓存 / 数据来源），各自跳转独立详情页。 */
public class SettingsFragment extends Fragment implements MainActivity.DataListener {

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 教育信息：次级菜单入口，点击打开独立详情页（学号/周次/学期/保存设置/刷新/演示/退出）
        View rowEducation = view.findViewById(R.id.rowEducation);
        rowEducation.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(requireContext(), EducationActivity.class));
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
        TextView tvAboutSummary = view.findViewById(R.id.tvAboutSummary);
        tvAboutSummary.setText("v" + versionName());

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
    }

    @Override
    public void onStart() {
        super.onStart();
        ((MainActivity) requireActivity()).requestData(this);
    }

    @Override
    public void onTimetable(TimetableResult data, int week) {
        updateSummaries();
    }

    /** 刷新各次级菜单入口的概要副标题（教育信息 / 外观 / 本地缓存 / 数据来源）。 */
    private void updateSummaries() {
        MainActivity main = (MainActivity) requireActivity();
        SessionStore store = main.repository().store();

        // 教育信息概要：学号 + 当前周次 + 学期
        String studentId = store.getStudentId();
        String studentText = studentId.isEmpty() ? "未设置学号" : studentId;
        String term = store.getTermLabel().isEmpty() ? store.getTerm() : store.getTermLabel();
        String termText = term.isEmpty() ? "未选择学期" : term;
        TextView tvEducationSummary = getView().findViewById(R.id.tvEducationSummary);
        tvEducationSummary.setText(studentText + " · 第 " + store.getCurrentWeek() + " 周 · " + termText);

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
