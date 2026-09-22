package edu.nustti.timetable.ui;

import android.content.Context;

import androidx.appcompat.app.AlertDialog;

import edu.nustti.timetable.model.Course;

/** 课程详情弹窗。 */
public final class CourseDialog {

    private CourseDialog() {
    }

    public static void show(Context context, Course course) {
        if (course == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("时间：").append(weekdayName(course.weekday)).append(' ')
                .append(course.timeText()).append('\n');
        sb.append("教师：").append(emptyTo(course.teacher, "未标注")).append('\n');
        sb.append("教室：").append(emptyTo(course.room, "未标注")).append('\n');
        sb.append("周次：").append(emptyTo(course.weeksText(), "每周"));
        if (course.raw != null && !course.raw.isEmpty()) {
            sb.append("\n\n原始单元格：").append(course.raw);
        }
        new AlertDialog.Builder(context)
                .setTitle(emptyTo(course.name, "课程"))
                .setMessage(sb.toString())
                .setPositiveButton("关闭", null)
                .show();
    }

    static String weekdayName(int weekday) {
        String[] names = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        if (weekday >= 1 && weekday <= 7) {
            return names[weekday];
        }
        return "未知";
    }

    private static String emptyTo(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }
}
