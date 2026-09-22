package edu.nustti.timetable.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一课程数据模型（与 Kivy 版 kb_parser / tt_model 的字段完全一致）。
 *
 * <pre>
 * name     课程名
 * teacher  教师
 * room     教室
 * weeks    有课的周次列表
 * weekRaw  周次原文，如 "1-16周"
 * weekday  1=星期一 ... 7=星期日
 * sessions 第几小节（1 起）
 * raw      单元格原始文本（排查用）
 * </pre>
 */
public class Course {

    public String name = "";
    public String teacher = "";
    public String room = "";
    public List<Integer> weeks = new ArrayList<>();
    public String weekRaw = "";
    public int weekday;
    public List<Integer> sessions = new ArrayList<>();
    public String raw = "";

    public Course() {
    }

    public Course(String name, String teacher, String room, int weekday,
                  List<Integer> sessions, List<Integer> weeks) {
        this.name = name == null ? "" : name;
        this.teacher = teacher == null ? "" : teacher;
        this.room = room == null ? "" : room;
        this.weekday = weekday;
        this.sessions = sessions == null ? new ArrayList<>() : new ArrayList<>(sessions);
        this.weeks = weeks == null ? new ArrayList<>() : new ArrayList<>(weeks);
    }

    public int startSession() {
        int min = Integer.MAX_VALUE;
        for (Integer s : sessions) {
            if (s != null && s < min) {
                min = s;
            }
        }
        return min == Integer.MAX_VALUE ? 1 : min;
    }

    public int endSession() {
        int max = 1;
        for (Integer s : sessions) {
            if (s != null && s > max) {
                max = s;
            }
        }
        return max;
    }

    /** 未标注周次 → 视为每周都有（与桌面版一致）。 */
    public boolean inWeek(int week) {
        if (weeks == null || weeks.isEmpty()) {
            return true;
        }
        return weeks.contains(week);
    }

    @Override
    public String toString() {
        return "Course{" + name + ", 周" + weekday + ", 节" + sessions + ", " + room + "}";
    }
}
