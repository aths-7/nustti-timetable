package edu.nustti.timetable.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 课表接口的返回体：课程列表 + 学期信息 + 解析元数据。 */
public class TimetableResult {

    public List<Course> courses = new ArrayList<>();
    public String term = "";
    public List<TermOption> terms = new ArrayList<>();
    public Meta meta = new Meta();

    public static class Meta {
        public Integer minWeek;
        public Integer maxWeek;
        public Integer tables;
        public Integer headerRow;
        public List<String> columns = new ArrayList<>();
        /** {"1": ["08:00","08:45"], ...} */
        public Map<String, List<String>> sessionTimes = new LinkedHashMap<>();
        public String error;

        public static Meta error(String message) {
            Meta meta = new Meta();
            meta.error = message;
            return meta;
        }
    }
}
