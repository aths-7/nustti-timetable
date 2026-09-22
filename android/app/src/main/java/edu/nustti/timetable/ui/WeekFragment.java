package edu.nustti.timetable.ui;

/** 整周视图：完整课程信息（课程名 / 教室 / 教师 / 周次）。 */
public class WeekFragment extends GridFragment {

    @Override
    protected int layoutId() {
        return edu.nustti.timetable.R.layout.fragment_week;
    }

    @Override
    protected int gridMode() {
        return WeekGridView.MODE_FULL;
    }

    @Override
    protected String titleText(int week) {
        return "第 " + week + " 周";
    }
}
