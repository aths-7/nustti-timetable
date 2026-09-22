package edu.nustti.timetable.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import edu.nustti.timetable.R;
import edu.nustti.timetable.model.Course;
import edu.nustti.timetable.model.TimetableResult;

/** 课表网格页基类：整周视图与紧凑视图共用周次切换、学期选择与数据绑定逻辑。 */
public abstract class GridFragment extends Fragment implements MainActivity.DataListener {

    protected WeekGridView grid;
    protected TextView tvWeekTitle;
    @Nullable
    protected Spinner spTerm;
    protected TimetableResult data;
    protected int week = 1;

    private boolean suppressTermCallback = true;

    protected abstract int layoutId();

    protected abstract int gridMode();

    protected abstract String titleText(int week);

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(layoutId(), container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        grid = view.findViewById(R.id.gridView);
        tvWeekTitle = view.findViewById(R.id.tvWeekTitle);
        spTerm = view.findViewById(R.id.spTerm);
        grid.setMode(gridMode());
        grid.setTodayWeekday(todayWeekday());
        grid.setOnCourseClickListener(new WeekGridView.OnCourseClickListener() {
            @Override
            public void onCourseClick(Course course) {
                CourseDialog.show(requireContext(), course);
            }
        });

        Button prev = view.findViewById(R.id.btnPrevWeek);
        Button next = view.findViewById(R.id.btnNextWeek);
        prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MainActivity main = (MainActivity) requireActivity();
                main.setWeek(main.week() - 1);
            }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MainActivity main = (MainActivity) requireActivity();
                main.setWeek(main.week() + 1);
            }
        });

        if (spTerm != null) {
            spTerm.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (suppressTermCallback || data == null || data.terms == null
                            || position >= data.terms.size()) {
                        return;
                    }
                    TimetableResult.TermOption option = data.terms.get(position);
                    MainActivity main = (MainActivity) requireActivity();
                    String current = main.repository().store().getTerm();
                    if (option.value.equals(current)) {
                        return;
                    }
                    main.repository().store().setTerm(option.value, option.label);
                    main.refresh(false);
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        MainActivity main = (MainActivity) requireActivity();
        main.requestData(this);
    }

    @Override
    public void onTimetable(TimetableResult data, int week) {
        this.data = data;
        this.week = week;
        grid.setData(data, week);
        tvWeekTitle.setText(titleText(week));
        fillTerms(data);
    }

    private void fillTerms(TimetableResult result) {
        if (spTerm == null) {
            return;
        }
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
            String label = result == null || result.term == null || result.term.isEmpty()
                    ? "（未获取到学期列表）" : result.term;
            labels.add(label);
        }
        suppressTermCallback = true;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spTerm.setAdapter(adapter);
        spTerm.setSelection(Math.min(selected, labels.size() - 1));
        suppressTermCallback = false;
    }

    protected static int todayWeekday() {
        Calendar calendar = Calendar.getInstance();
        int dow = calendar.get(Calendar.DAY_OF_WEEK);
        return dow == Calendar.SUNDAY ? 7 : dow - 1;
    }
}
