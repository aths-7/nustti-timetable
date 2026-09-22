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
        int pad = dp(12);
        box.setPadding(pad, pad, pad, pad);

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFFEEF3FF);
        background.setCornerRadius(dp(10));
        background.setStroke(dp(1), 0xFFC7D8F5);
        box.setBackground(background);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        box.setLayoutParams(params);

        TextView name = new TextView(requireContext());
        name.setText(course.name == null || course.name.isEmpty() ? "未命名课程" : course.name);
        name.setTextSize(16f);
        name.setTextColor(0xFF1B3A6B);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        box.addView(name);

        String timeRange = data == null ? "" : data.sessionTime(course.startSession());
        TextView meta = new TextView(requireContext());
        meta.setText(course.timeText() + (timeRange.isEmpty() ? "" : "  " + timeRange)
                + "   " + course.weeksText());
        meta.setTextSize(13f);
        meta.setTextColor(0xFF445566);
        box.addView(meta);

        TextView place = new TextView(requireContext());
        place.setText("教师：" + emptyTo(course.teacher) + "    教室：" + emptyTo(course.room));
        place.setTextSize(13f);
        place.setTextColor(0xFF445566);
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
