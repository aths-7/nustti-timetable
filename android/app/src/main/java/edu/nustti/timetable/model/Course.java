package edu.nustti.timetable.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 课程数据模型，字段与 Kivy 桌面版完全一致。
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
        this.sessions = sessions == null ? new ArrayList<Integer>() : sessions;
        this.weeks = weeks == null ? new ArrayList<Integer>() : weeks;
    }

    public int startSession() {
        int min = Integer.MAX_VALUE;
        if (sessions != null) {
            for (Integer s : sessions) {
                if (s != null && s < min) {
                    min = s;
                }
            }
        }
        return min == Integer.MAX_VALUE ? 1 : min;
    }

    public int endSession() {
        int max = 1;
        if (sessions != null) {
            for (Integer s : sessions) {
                if (s != null && s > max) {
                    max = s;
                }
            }
        }
        return max;
    }

    /** 未标注周次时视为每周都有（与桌面版一致）。 */
    public boolean inWeek(int week) {
        if (weeks == null || weeks.isEmpty()) {
            return true;
        }
        return weeks.contains(week);
    }

    /** 周次显示文本：优先用原文，其次用解析结果生成。 */
    public String weeksText() {
        if (weekRaw != null && !weekRaw.isEmpty()) {
            return weekRaw;
        }
        if (weeks == null || weeks.isEmpty()) {
            return "每周";
        }
        return weeks.size() + "周";
    }

    public String timeText() {
        if (sessions == null || sessions.isEmpty()) {
            return "";
        }
        if (sessions.size() == 1) {
            return "第" + sessions.get(0) + "节";
        }
        return "第" + startSession() + "-" + endSession() + "节";
    }

    public String key() {
        return name + "@" + weekday + "@" + startSession() + "@" + endSession();
    }
}
