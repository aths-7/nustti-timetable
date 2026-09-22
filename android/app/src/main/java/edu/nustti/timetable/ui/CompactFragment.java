package edu.nustti.timetable.ui;

/** 紧凑视图：只保留课程名，缩小行高与字号，单屏可见更多小节。 */
public class CompactFragment extends GridFragment {

    @Override
    protected int layoutId() {
        return edu.nustti.timetable.R.layout.fragment_compact;
    }

    @Override
    protected int gridMode() {
        return WeekGridView.MODE_COMPACT;
    }

    @Override
    protected String titleText(int week) {
        return "第 " + week + " 周（紧凑）";
    }
}
