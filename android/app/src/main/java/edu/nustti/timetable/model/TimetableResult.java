package edu.nustti.timetable.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 课表结果结构：课程列表、可选学期列表与解析元信息。 */
public class TimetableResult {

    public List<Course> courses = new ArrayList<>();
    public String term = "";
    public List<TermOption> terms = new ArrayList<>();
    public Meta meta = new Meta();

    public static class TermOption {
        public String value = "";
        public String label = "";

        public TermOption() {
        }

        public TermOption(String value, String label) {
            this.value = value == null ? "" : value;
            this.label = label == null ? "" : label;
        }

        @Override
        public String toString() {
            return label == null || label.isEmpty() ? value : label;
        }
    }

    public static class Meta {
        public Integer minWeek = 1;
        public Integer maxWeek = 20;
        public Integer tables;
        public Integer headerRow;
        public List<String> columns = new ArrayList<>();
        /** {"1": ["08:00","08:45"], ...} */
        public Map<String, List<String>> sessionTimes = new LinkedHashMap<>();
        public String error;

        /** 解析失败时的元信息（课程列表为空，maxWeek 置 0）。 */
        public static Meta error(String message) {
            Meta meta = new Meta();
            meta.error = message;
            meta.maxWeek = 0;
            return meta;
        }
    }

    public int maxWeek() {
        if (meta != null && meta.maxWeek != null && meta.maxWeek > 0) {
            return meta.maxWeek;
        }
        int max = 20;
        if (courses != null) {
            for (Course c : courses) {
                if (c.weeks != null) {
                    for (Integer w : c.weeks) {
                        if (w != null && w > max) {
                            max = w;
                        }
                    }
                }
            }
        }
        return max;
    }

    /** 第 week 周有课的课程（按星期、节次排序）。 */
    public List<Course> coursesOfWeek(int week) {
        List<Course> out = new ArrayList<>();
        if (courses == null) {
            return out;
        }
        for (Course c : courses) {
            if (c.inWeek(week)) {
                out.add(c);
            }
        }
        out.sort((a, b) -> {
            if (a.weekday != b.weekday) {
                return Integer.compare(a.weekday, b.weekday);
            }
            return Integer.compare(a.startSession(), b.startSession());
        });
        return out;
    }

    /** 某天（1=周一 .. 7=周日）第 week 周的课程。 */
    public List<Course> coursesOfDay(int weekday, int week) {
        List<Course> out = new ArrayList<>();
        for (Course c : coursesOfWeek(week)) {
            if (c.weekday == weekday) {
                out.add(c);
            }
        }
        return out;
    }

    /** 作息时间文本，如 "08:00-09:35"。 */
    public String sessionTime(int session) {
        if (meta == null || meta.sessionTimes == null) {
            return "";
        }
        List<String> t = meta.sessionTimes.get(String.valueOf(session));
        if (t == null || t.isEmpty()) {
            return "";
        }
        if (t.size() == 1) {
            return t.get(0);
        }
        return t.get(0) + "-" + t.get(t.size() - 1);
    }
}
