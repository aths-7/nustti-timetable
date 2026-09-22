package edu.nustti.timetable.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 作息时间表：12 小节，与桌面版 store.DEFAULT_SESSION_TIMES 一致。 */
public final class SessionTimes {

    public static final int SLOT_COUNT = 12;

    private static final String[][] DEFAULT = {
            {"08:00", "08:45"},
            {"08:50", "09:35"},
            {"09:50", "10:35"},
            {"10:40", "11:25"},
            {"13:30", "14:15"},
            {"14:20", "15:05"},
            {"15:20", "16:05"},
            {"16:10", "16:55"},
            {"18:30", "19:15"},
            {"19:20", "20:05"},
            {"20:10", "20:55"},
            {"21:00", "21:45"},
    };

    private SessionTimes() {
    }

    public static List<List<String>> defaults() {
        List<List<String>> out = new ArrayList<>();
        for (String[] pair : DEFAULT) {
            List<String> row = new ArrayList<>(2);
            row.add(pair[0]);
            row.add(pair[1]);
            out.add(row);
        }
        return out;
    }

    /** 转成 JSON 友好的 map：{"1": ["08:00","08:45"], ...}。 */
    public static Map<String, List<String>> defaultsAsMap() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        List<List<String>> rows = defaults();
        for (int i = 0; i < rows.size(); i++) {
            map.put(String.valueOf(i + 1), rows.get(i));
        }
        return map;
    }

    /** "08:05" → 485；非法输入返回 -1。 */
    public static int parseHhmm(String text) {
        if (text == null) {
            return -1;
        }
        try {
            String[] parts = text.trim().replace('：', ':').split(":");
            return Integer.parseInt(parts[0].trim()) * 60 + Integer.parseInt(parts[1].trim());
        } catch (Exception e) {
            return -1;
        }
    }
}
