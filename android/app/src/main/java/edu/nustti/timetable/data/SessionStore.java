package edu.nustti.timetable.data;

import android.content.Context;
import android.content.SharedPreferences;

/** 本地配置与课表缓存（对应桌面版的 config.json + 课表缓存）。 */
public class SessionStore {

    private static final String PREF = "nustti_session";

    private static final String KEY_BASE = "baseUrl";
    private static final String KEY_STUDENT = "studentId";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_TERM = "term";
    private static final String KEY_TERM_LABEL = "termLabel";
    private static final String KEY_WEEK = "currentWeek";
    private static final String KEY_CACHE = "timetableJson";
    private static final String KEY_CACHE_AT = "timetableAt";
    private static final String KEY_CACHE_FROM = "timetableFrom";

    /** 课表数据来源：南京理工大学泰州科技学院教务系统官网（客户端直连）。 */
    public static final String DEFAULT_BASE = "https://jwgl.nustti.edu.cn";

    private final SharedPreferences prefs;

    public SessionStore(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public String getBaseUrl() {
        String v = prefs.getString(KEY_BASE, DEFAULT_BASE);
        return v == null || v.trim().isEmpty() ? DEFAULT_BASE : v;
    }

    public void setBaseUrl(String value) {
        prefs.edit().putString(KEY_BASE, value == null ? "" : value.trim()).apply();
    }

    public String getStudentId() {
        return prefs.getString(KEY_STUDENT, "");
    }

    public void setStudentId(String value) {
        prefs.edit().putString(KEY_STUDENT, value == null ? "" : value.trim()).apply();
    }

    public String getPassword() {
        return prefs.getString(KEY_PASSWORD, "");
    }

    public void setPassword(String value) {
        prefs.edit().putString(KEY_PASSWORD, value == null ? "" : value).apply();
    }

    public String getTerm() {
        return prefs.getString(KEY_TERM, "");
    }

    public String getTermLabel() {
        return prefs.getString(KEY_TERM_LABEL, "");
    }

    public void setTerm(String value, String label) {
        prefs.edit().putString(KEY_TERM, value == null ? "" : value)
                .putString(KEY_TERM_LABEL, label == null ? "" : label).apply();
    }

    public int getCurrentWeek() {
        return prefs.getInt(KEY_WEEK, 1);
    }

    public void setCurrentWeek(int week) {
        prefs.edit().putInt(KEY_WEEK, Math.max(1, week)).apply();
    }

    public void saveTimetable(String json, String from) {
        prefs.edit().putString(KEY_CACHE, json)
                .putLong(KEY_CACHE_AT, System.currentTimeMillis())
                .putString(KEY_CACHE_FROM, from == null ? "" : from)
                .apply();
    }

    public String loadTimetableJson() {
        return prefs.getString(KEY_CACHE, "");
    }

    public long cacheTime() {
        return prefs.getLong(KEY_CACHE_AT, 0L);
    }

    public String cacheFrom() {
        return prefs.getString(KEY_CACHE_FROM, "");
    }

    public void clearTimetable() {
        prefs.edit().remove(KEY_CACHE).remove(KEY_CACHE_AT).remove(KEY_CACHE_FROM).apply();
    }
}
