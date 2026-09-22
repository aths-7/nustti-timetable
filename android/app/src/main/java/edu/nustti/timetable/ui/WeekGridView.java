package edu.nustti.timetable.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import edu.nustti.timetable.model.Course;
import edu.nustti.timetable.model.TimetableResult;

/**
 * 手写课表网格视图，支持两种呈现：
 * <ul>
 *   <li>{@link #MODE_FULL}：整周课表，课程块显示课程名 / 教室 / 教师 / 周次</li>
 *   <li>{@link #MODE_COMPACT}：紧凑课表，课程块只显示课程名，字号与行高更小，一屏看更多</li>
 * </ul>
 * 表格结构（含 rowspan 合并单元格）由服务端解析好后以「星期 + 小节区间」形式下发。
 */
public class WeekGridView extends View {

    public static final int MODE_FULL = 0;
    public static final int MODE_COMPACT = 1;

    public interface OnCourseClickListener {
        void onCourseClick(Course course);
    }

    private static final String[] WEEKDAY_NAMES = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private static final int[] BLOCK_COLORS = {
            Color.parseColor("#3B82F6"), Color.parseColor("#10B981"), Color.parseColor("#F59E0B"),
            Color.parseColor("#EF4444"), Color.parseColor("#8B5CF6"), Color.parseColor("#06B6D4"),
            Color.parseColor("#EC4899"), Color.parseColor("#84CC16"), Color.parseColor("#6366F1"),
            Color.parseColor("#F97316")
    };

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint headerText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint labelText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint labelSubText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titleText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bodyText = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private final List<Course> courses = new ArrayList<>();
    private final List<RectF> hitRects = new ArrayList<>();
    private final List<Course> hitCourses = new ArrayList<>();

    private int mode = MODE_FULL;
    private int columns = 7;
    private int rows = 12;
    private int week = 1;
    private int todayWeekday = 0;
    private Map<String, List<String>> sessionTimes = new LinkedHashMap<>();

    private OnCourseClickListener listener;
    private final float density;

    public WeekGridView(Context context) {
        this(context, null);
    }

    public WeekGridView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        float scaled = getResources().getDisplayMetrics().scaledDensity;

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(1));
        linePaint.setColor(Color.parseColor("#D8DEE9"));

        headerPaint.setColor(Color.parseColor("#EEF2FF"));

        blockPaint.setStyle(Paint.Style.FILL);

        headerText.setColor(Color.parseColor("#1E293B"));
        headerText.setTextSize(12 * scaled);
        headerText.setTextAlign(Paint.Align.CENTER);

        labelText.setColor(Color.parseColor("#475569"));
        labelText.setTextAlign(Paint.Align.CENTER);

        labelSubText.setColor(Color.parseColor("#94A3B8"));
        labelSubText.setTextAlign(Paint.Align.CENTER);

        titleText.setColor(Color.WHITE);
        titleText.setFakeBoldText(true);

        bodyText.setColor(Color.parseColor("#F1F5F9"));

        applyModeMetrics(scaled);
    }

    // ------------------------------------------------------------------ //
    // 对外接口
    // ------------------------------------------------------------------ //

    public void setMode(int mode) {
        this.mode = mode;
        applyModeMetrics(getResources().getDisplayMetrics().scaledDensity);
        requestLayout();
        invalidate();
    }

    public void setTodayWeekday(int weekday) {
        this.todayWeekday = weekday;
        invalidate();
    }

    public void setOnCourseClickListener(OnCourseClickListener listener) {
        this.listener = listener;
    }

    public int getColumns() {
        return columns;
    }

    public int getRows() {
        return rows;
    }

    public int getHeaderHeightPx() {
        return (int) headerH();
    }

    /** 绑定数据并指定当前周次；同格多课会在格内并排排布。 */
    public void setData(TimetableResult result, int week) {
        this.week = Math.max(1, week);
        courses.clear();
        if (result != null) {
            if (result.meta != null) {
                if (result.meta.sessionTimes != null && !result.meta.sessionTimes.isEmpty()) {
                    sessionTimes = result.meta.sessionTimes;
                    rows = Math.max(rowsMin(), sessionTimes.size());
                }
                if (result.meta.columns != null && result.meta.columns.size() >= 7) {
                    columns = 7;
                }
            }
            for (Course c : result.courses) {
                if (c == null || c.weekday < 1 || c.weekday > columns) {
                    continue;
                }
                if (c.inWeek(this.week)) {
                    courses.add(c);
                }
            }
        }
        hitRects.clear();
        hitCourses.clear();
        requestLayout();
        invalidate();
    }

    // ------------------------------------------------------------------ //
    // 尺寸
    // ------------------------------------------------------------------ //

    private int rowsMin() {
        return mode == MODE_COMPACT ? 10 : 10;
    }

    private void applyModeMetrics(float scaled) {
        if (mode == MODE_COMPACT) {
            titleText.setTextSize(10 * scaled);
            bodyText.setTextSize(9 * scaled);
            labelText.setTextSize(10 * scaled);
            labelSubText.setTextSize(8 * scaled);
        } else {
            titleText.setTextSize(12.5f * scaled);
            bodyText.setTextSize(10 * scaled);
            labelText.setTextSize(11 * scaled);
            labelSubText.setTextSize(9 * scaled);
        }
    }

    private float labelW() {
        return dp(mode == MODE_COMPACT ? 34 : 52);
    }

    private float colW() {
        return dp(mode == MODE_COMPACT ? 62 : 96);
    }

    private float rowH() {
        return dp(mode == MODE_COMPACT ? 46 : 74);
    }

    private float headerH() {
        return dp(mode == MODE_COMPACT ? 28 : 38);
    }

    private int desiredWidth() {
        return (int) (labelW() + columns * colW());
    }

    private int desiredHeight() {
        return (int) (headerH() + rows * rowH());
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width;
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            width = desiredWidth();
        } else {
            width = Math.max(desiredWidth(), MeasureSpec.getSize(widthMeasureSpec));
        }
        int height;
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            height = desiredHeight();
        } else {
            height = Math.max(desiredHeight(), MeasureSpec.getSize(heightMeasureSpec));
        }
        setMeasuredDimension(width, height);
    }

    // ------------------------------------------------------------------ //
    // 绘制
    // ------------------------------------------------------------------ //

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float labelW = labelW();
        float colW = colW();
        float rowH = rowH();
        float headerH = headerH();

        // 表头
        canvas.drawRect(0, 0, getWidth(), headerH, headerPaint);
        for (int i = 0; i < columns; i++) {
            float cx = labelW + i * colW + colW / 2f;
            if (todayWeekday == i + 1) {
                Paint highlight = new Paint(Paint.ANTI_ALIAS_FLAG);
                highlight.setColor(Color.parseColor("#1D4ED8"));
                canvas.drawRect(labelW + i * colW, 0, labelW + (i + 1) * colW, headerH, highlight);
                headerText.setColor(Color.WHITE);
            } else {
                headerText.setColor(Color.parseColor("#1E293B"));
            }
            float baseline = headerH / 2f - (headerText.descent() + headerText.ascent()) / 2f;
            canvas.drawText(WEEKDAY_NAMES[i], cx, baseline, headerText);
        }

        // 节次列 + 网格
        for (int r = 0; r < rows; r++) {
            float top = headerH + r * rowH;
            String time = "";
            if (sessionTimes != null && sessionTimes.get(String.valueOf(r + 1)) != null) {
                List<String> t = sessionTimes.get(String.valueOf(r + 1));
                if (!t.isEmpty()) {
                    time = t.get(0);
                }
            }
            labelText.setColor(Color.parseColor("#334155"));
            float b1 = top + rowH / 2f - dp(4);
            canvas.drawText(String.valueOf(r + 1), labelW / 2f, b1, labelText);
            labelSubText.setColor(Color.parseColor("#94A3B8"));
            canvas.drawText(time, labelW / 2f, b1 + dp(11), labelSubText);
        }

        for (int i = 0; i <= columns; i++) {
            float x = labelW + i * colW;
            canvas.drawLine(x, 0, x, headerH + rows * rowH, linePaint);
        }
        for (int r = 0; r <= rows; r++) {
            float y = headerH + r * rowH;
            canvas.drawLine(0, y, labelW + columns * colW, y, linePaint);
        }

        // 课程块（同格多课并排）
        hitRects.clear();
        hitCourses.clear();
        Map<String, List<Course>> slots = new LinkedHashMap<>();
        for (Course c : courses) {
            String key = c.weekday + ":" + c.startSession();
            List<Course> list = slots.get(key);
            if (list == null) {
                list = new ArrayList<>();
                slots.put(key, list);
            }
            list.add(c);
        }
        for (Map.Entry<String, List<Course>> entry : slots.entrySet()) {
            List<Course> group = entry.getValue();
            int count = group.size();
            float cellLeft = labelW + (group.get(0).weekday - 1) * colW;
            float partW = colW / count;
            for (int i = 0; i < count; i++) {
                Course c = group.get(i);
                int start = Math.max(1, Math.min(rows, c.startSession()));
                int end = Math.max(start, Math.min(rows, c.endSession()));
                float top = headerH + (start - 1) * rowH + dp(2);
                float bottom = headerH + end * rowH - dp(2);
                RectF rect = new RectF(cellLeft + i * partW + dp(2), top,
                        cellLeft + (i + 1) * partW - dp(2), bottom);
                drawBlock(canvas, rect, c);
                hitRects.add(new RectF(rect));
                hitCourses.add(c);
            }
        }
    }

    private void drawBlock(Canvas canvas, RectF rect, Course course) {
        int color = BLOCK_COLORS[Math.abs(course.name.hashCode()) % BLOCK_COLORS.length];
        blockPaint.setColor(color);
        float radius = dp(6);
        canvas.drawRoundRect(rect, radius, radius, blockPaint);

        float pad = dp(4);
        float maxWidth = rect.width() - pad * 2;
        if (maxWidth < dp(10)) {
            return;
        }
        titleText.setColor(Color.WHITE);
        String name = TextUtils.ellipsize(course.name == null ? "" : course.name, titleText, maxWidth,
                TextUtils.TruncateAt.END).toString();
        float y = rect.top + pad + titleText.getTextSize() - dp(2);
        canvas.drawText(name, rect.left + pad, y, titleText);

        if (mode == MODE_COMPACT) {
            return;
        }
        float lineH = bodyText.getTextSize() + dp(2);
        if (rect.height() > lineH * 2 + dp(6)) {
            bodyText.setColor(Color.parseColor("#EFF6FF"));
            String room = TextUtils.ellipsize(course.room == null ? "" : course.room, bodyText, maxWidth,
                    TextUtils.TruncateAt.END).toString();
            if (!room.isEmpty()) {
                y += lineH;
                canvas.drawText(room, rect.left + pad, y, bodyText);
            }
            if (rect.height() > lineH * 3 + dp(4)) {
                String teacher = TextUtils.ellipsize(course.teacher == null ? "" : course.teacher, bodyText,
                        maxWidth, TextUtils.TruncateAt.END).toString();
                if (!teacher.isEmpty()) {
                    y += lineH;
                    canvas.drawText(teacher, rect.left + pad, y, bodyText);
                }
            }
            if (rect.height() > lineH * 4 + dp(2)) {
                String weeks = TextUtils.ellipsize(course.weeksText(), bodyText, maxWidth,
                        TextUtils.TruncateAt.END).toString();
                y += lineH;
                bodyText.setColor(Color.parseColor("#DBEAFE"));
                canvas.drawText(weeks, rect.left + pad, y, bodyText);
            }
        }
    }

    // ------------------------------------------------------------------ //
    // 交互
    // ------------------------------------------------------------------ //

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return true;
            case MotionEvent.ACTION_UP:
                performClick();
                Course hit = findCourse(event.getX(), event.getY());
                if (hit != null && listener != null) {
                    listener.onCourseClick(hit);
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private Course findCourse(float x, float y) {
        for (int i = hitRects.size() - 1; i >= 0; i--) {
            RectF r = hitRects.get(i);
            if (r.contains(x, y)) {
                return hitCourses.get(i);
            }
        }
        return null;
    }

    private float dp(float value) {
        return value * density;
    }
}
