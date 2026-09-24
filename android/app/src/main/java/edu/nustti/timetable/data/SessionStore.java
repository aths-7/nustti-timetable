package edu.nustti.timetable.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;

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
    private static final String KEY_COURSE_TEXT_COLOR = "courseTextColor";
    private static final String KEY_COURSE_FONT = "courseFont";
    private static final String KEY_THEME_COLOR = "themeColor";

    /** 课程文字颜色默认值：白色（与课程块深色底配套，保证可读）。 */
    public static final int DEFAULT_COURSE_TEXT_COLOR = 0xFFFFFFFF;
    /** 顶部主题颜色默认值：品牌蓝。 */
    public static final int DEFAULT_THEME_COLOR = 0xFF1D4ED8;
    /** 字体选项标识。 */
    public static final String FONT_DEFAULT = "default";
    public static final String FONT_SERIF = "serif";
    public static final String FONT_SANS = "sans_serif";
    public static final String FONT_FANGSONG = "fangsong";
    public static final String FONT_KAITI = "kaiti";

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

    // ------------------------------------------------------------------ //
    // 外观自定义：课程文字颜色 / 课程字体 / 顶部主题颜色
    // ------------------------------------------------------------------ //

    /** 课程文字颜色（ARGB int，默认白色）。 */
    public int getCourseTextColor() {
        return prefs.getInt(KEY_COURSE_TEXT_COLOR, DEFAULT_COURSE_TEXT_COLOR);
    }

    public void setCourseTextColor(int color) {
        prefs.edit().putInt(KEY_COURSE_TEXT_COLOR, color).apply();
    }

    /** 课程字体标识（默认系统字体）。 */
    public String getCourseFont() {
        return prefs.getString(KEY_COURSE_FONT, FONT_DEFAULT);
    }

    public void setCourseFont(String font) {
        prefs.edit().putString(KEY_COURSE_FONT, font == null ? FONT_DEFAULT : font).apply();
    }

    /** 顶部主题颜色（ARGB int，默认品牌蓝）。 */
    public int getThemeColor() {
        return prefs.getInt(KEY_THEME_COLOR, DEFAULT_THEME_COLOR);
    }

    public void setThemeColor(int color) {
        prefs.edit().putInt(KEY_THEME_COLOR, color).apply();
    }

    /**
     * 根据字体设置返回课表文字 Typeface。Android 无内置仿宋 / 楷体字体：
     * 仿宋用 serif 近似（宋体系衬线），楷体用 cursive 近似（系统内置手写体）；
     * 黑体用 sans-serif-medium 近似。返回 null 表示系统默认字体。
     */
    public Typeface courseTypeface() {
        switch (getCourseFont()) {
            case FONT_SERIF:
                return Typeface.create("serif", Typeface.NORMAL);
            case FONT_SANS:
                return Typeface.create("sans-serif-medium", Typeface.NORMAL);
            case FONT_FANGSONG:
                return Typeface.create("serif", Typeface.NORMAL);
            case FONT_KAITI:
                return Typeface.create("cursive", Typeface.NORMAL);
            default:
                return null;
        }
    }
}
