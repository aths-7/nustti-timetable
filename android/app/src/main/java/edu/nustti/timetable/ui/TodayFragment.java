package edu.nustti.timetable.ui;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import edu.nustti.timetable.R;
import edu.nustti.timetable.data.SessionStore;
import edu.nustti.timetable.model.Course;
import edu.nustti.timetable.model.TimetableResult;

/** 今日视图：按星期 + 周次展示当天的课程清单。 */
public class TodayFragment extends Fragment implements MainActivity.DataListener {

    private static final String[] WEEKDAY_NAMES = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private LinearLayout listView;
    private TextView tvTitle;
    private TextView tvEmpty;
    private Spinner spWeekday;
    private Spinner spWeek;

    private TimetableResult data;
    private int week = 1;
    private int weekday = 1;
    private boolean suppressCallback = true;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_today, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        listView = view.findViewById(R.id.todayList);
        tvTitle = view.findViewById(R.id.tvTodayTitle);
        tvEmpty = view.findViewById(R.id.tvTodayEmpty);
        spWeekday = view.findViewById(R.id.spWeekday);
        spWeek = view.findViewById(R.id.spWeek);

        Calendar calendar = Calendar.getInstance();
        int todayDow = calendar.get(Calendar.DAY_OF_WEEK);
        weekday = todayDow == Calendar.SUNDAY ? 7 : todayDow - 1;

        ArrayAdapter<String> weekdayAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, WEEKDAY_NAMES);
        weekdayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spWeekday.setAdapter(weekdayAdapter);
        spWeekday.setSelection(weekday - 1);
        spWeekday.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                weekday = position + 1;
                render();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        spWeek.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressCallback) {
                    return;
                }
                week = position + 1;
                MainActivity main = (MainActivity) requireActivity();
                main.setWeek(week);
                render();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        applyBackground();
    }

    /** 自定义背景 / 透明度变化后刷新背景与课程卡片。 */
    public void reloadBackground() {
        applyBackground();
        render();
    }

    /** 背景已统一由 MainActivity 窗口根绘制（壁纸+加深铺满全屏含状态栏/导航栏），
     *  Fragment 根布局保持透明透出窗口背景，此处无需再覆盖根背景。 */
    private void applyBackground() {
        View root = getView();
        if (root != null) {
            root.setBackgroundResource(android.R.color.transparent);
        }
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
        fillWeekSpinner(data, week);
        render();
    }

    private void fillWeekSpinner(TimetableResult result, int selected) {
        int max = result == null ? 20 : result.maxWeek();
        List<String> labels = new ArrayList<>();
        for (int i = 1; i <= max; i++) {
            labels.add("第 " + i + " 周");
        }
        suppressCallback = true;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spWeek.setAdapter(adapter);
        spWeek.setSelection(Math.max(0, Math.min(labels.size() - 1, selected - 1)));
        suppressCallback = false;
    }

    private void render() {
        if (listView == null) {
            return;
        }
        listView.removeAllViews();
        List<Course> courses = data == null ? new ArrayList<Course>() : data.coursesOfDay(weekday, week);

        SimpleDateFormat fmt = new SimpleDateFormat("M月d日", Locale.CHINA);
        String title = fmt.format(Calendar.getInstance().getTime()) + "（" + WEEKDAY_NAMES[weekday - 1] + "）"
                + " · 第 " + week + " 周";
        tvTitle.setText(title);

        if (courses.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText(WEEKDAY_NAMES[weekday - 1] + "（第 " + week + " 周）没有课程安排");
            return;
        }
        tvEmpty.setVisibility(View.GONE);
        for (Course course : courses) {
            listView.addView(buildCard(course));
        }
    }

    private View buildCard(Course course) {
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        // 卡片内边距与设置页行卡片一致（start 16dp / top 12dp / end 12dp / bottom 12dp）
        box.setPadding(dp(16), dp(12), dp(12), dp(12));

        GradientDrawable background = new GradientDrawable();
        // 卡片样式与设置页 bg_settings_row 一致：半透明白玻璃雾面 + 14dp 圆角 + 无描边，透出窗口壁纸背景
        background.setColor(0x30FFFFFF);
        background.setCornerRadius(dp(14));
        box.setBackground(background);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(10);
        box.setLayoutParams(params);

        TextView name = new TextView(requireContext());
        name.setText(course.name == null || course.name.isEmpty() ? "未命名课程" : course.name);
        name.setTextSize(16f);
        // 课程名应用用户自定义的字体颜色与字体；浅色卡片上默认白字不可读，未显式设置时回退深蓝
        SessionStore store = new SessionStore(requireContext());
        int textColor = store.getCourseTextColor();
        if (textColor == SessionStore.DEFAULT_COURSE_TEXT_COLOR) {
            // 半透明白玻璃卡片上默认白字不可读，回退设置页主标题深色
            textColor = 0xFF0F172A;
        }
        name.setTextColor(textColor);
        android.graphics.Typeface tf = store.courseTypeface();
        name.setTypeface(tf == null ? name.getTypeface() : tf, android.graphics.Typeface.BOLD);
        box.addView(name);

        String timeRange = data == null ? "" : data.sessionTime(course.startSession());
        TextView meta = new TextView(requireContext());
        meta.setText(course.timeText() + (timeRange.isEmpty() ? "" : "  " + timeRange)
                + "   " + course.weeksText());
        meta.setTextSize(12f);
        // 次级文字与设置页 text_secondary_bright 一致
        meta.setTextColor(0xFFC7D2E3);
        box.addView(meta);

        TextView place = new TextView(requireContext());
        place.setText("教师：" + emptyTo(course.teacher) + "    教室：" + emptyTo(course.room));
        place.setTextSize(12f);
        place.setTextColor(0xFFC7D2E3);
        box.addView(place);

        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CourseDialog.show(requireContext(), course);
            }
        });
        return box;
    }

    private static String emptyTo(String value) {
        return value == null || value.isEmpty() ? "未标注" : value;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
