package edu.nustti.timetable.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import edu.nustti.timetable.data.SessionStore;
import edu.nustti.timetable.model.Course;
import edu.nustti.timetable.model.TimetableResult;

/**
 * 手写课表网格视图，整周课表。
 * 每个课程块宽为列宽的一半（居中）、竖向高度不变；课程名完整显示可多行换行，块内显示上课教室。
 * 同一课时多门课时默认只显示一门，点击课程块弹出选择框由用户切换显示哪一门，选择结果持久化。
 */
public class WeekGridView extends View {

    public static final int MODE_FULL = 0;

    /** 同格多课「当前显示哪一门」的持久化文件名（与 SessionStore 分离，避免键冲突）。 */
    private static final String PREF_PICKS = "nustti_cell_picks";
    private static final String KEY_PICK_PREFIX = "cell_pick_";

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

    /** 同格课程分组：key = weekday:startSession-endSession（完整节次区间），value = 该格全部课程（当前周可见）。
     *  仅节次区间完全相同的课程才视为“同格多课”（默认显示一门+点击自选）；
     *  若只按 startSession 分组，会把“同起始节但不同结束节”的课程（如 1-2 节与 1-4 节）误合并，导致整块课程缺失。 */
    private final Map<String, List<Course>> slotGroups = new LinkedHashMap<>();
    /** 每个格子当前显示课程的 key（Course.key()），无持久化记录时取组内第一门。 */
    private final Map<String, String> slotPicks = new LinkedHashMap<>();
    /** 命中区域对应的格子 key 与该格课程数，用于点击时判断是否需要弹选择框。 */
    private final List<String> hitSlotKeys = new ArrayList<>();
    private final List<Integer> hitSlotSizes = new ArrayList<>();

    private int mode = MODE_FULL;
    private int columns = 7;
    private int rows = 12;
    private int week = 1;
    private int todayWeekday = 0;
    private Map<String, List<String>> sessionTimes = new LinkedHashMap<>();

    private OnCourseClickListener listener;
    private final float density;

    private Bitmap bgBitmap;
    private boolean bgAttempted;

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
        // 课程块可能半透明透出背景，给文字加阴影保证可读
        titleText.setShadowLayer(dp(1.5f), 0f, dp(1f), 0xB3000000);

        bodyText.setColor(Color.parseColor("#F1F5F9"));
        bodyText.setShadowLayer(dp(1.5f), 0f, dp(1f), 0xB3000000);

        applyModeMetrics(scaled);
        // 应用用户自定义的课程文字颜色 / 字体（默认白字 + 系统字体）
        applyTextStyles();
    }

    /** 应用外观自定义：课程文字颜色（title/body）与字体（title/body/header/label），设置变更后调用以即时生效。 */
    public void applyTextStyles() {
        SessionStore store = new SessionStore(getContext());
        int textColor = store.getCourseTextColor();
        titleText.setColor(textColor);
        bodyText.setColor(textColor);
        android.graphics.Typeface typeface = store.courseTypeface();
        titleText.setTypeface(typeface);
        bodyText.setTypeface(typeface);
        headerText.setTypeface(typeface);
        labelText.setTypeface(typeface);
        labelSubText.setTypeface(typeface);
        invalidate();
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

    /** 重新加载自定义背景（设置 / 恢复默认后调用）。 */
    public void reloadBackground() {
        if (bgBitmap != null) {
            bgBitmap.recycle();
            bgBitmap = null;
        }
        bgBitmap = BackgroundManager.loadBitmap(getContext());
        bgAttempted = true;
        // 外观设置（字体颜色 / 字体）变化时一并刷新文字样式
        applyTextStyles();
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

    /** 绑定数据并指定当前周次；同格多课默认只显示一门（优先持久化的用户选择）。 */
    public void setData(TimetableResult result, int week) {
        this.week = Math.max(1, week);
        courses.clear();
        slotGroups.clear();
        slotPicks.clear();
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
                    String key = c.weekday + ":" + c.startSession() + "-" + c.endSession();
                    List<Course> group = slotGroups.get(key);
                    if (group == null) {
                        group = new ArrayList<>();
                        slotGroups.put(key, group);
                    }
                    group.add(c);
                }
            }
        }
        SharedPreferences prefs = getContext().getSharedPreferences(PREF_PICKS, Context.MODE_PRIVATE);
        for (Map.Entry<String, List<Course>> entry : slotGroups.entrySet()) {
            String slotKey = entry.getKey();
            List<Course> group = entry.getValue();
            String picked = prefs.getString(KEY_PICK_PREFIX + slotKey, null);
            String display = null;
            if (picked != null) {
                for (Course c : group) {
                    if (c.key().equals(picked)) {
                        display = c.key();
                        break;
                    }
                }
            }
            if (display == null) {
                display = group.get(0).key();
            }
            slotPicks.put(slotKey, display);
        }
        hitRects.clear();
        hitCourses.clear();
        hitSlotKeys.clear();
        hitSlotSizes.clear();
        requestLayout();
        invalidate();
    }

    // ------------------------------------------------------------------ //
    // 尺寸
    // ------------------------------------------------------------------ //

    private int rowsMin() {
        return 10;
    }

    private void applyModeMetrics(float scaled) {
        titleText.setTextSize(12.5f * scaled);
        bodyText.setTextSize(10 * scaled);
        labelText.setTextSize(11 * scaled);
        labelSubText.setTextSize(9 * scaled);
    }

    private float labelW() {
        return dp(52);
    }

    /** 列宽按屏幕可用宽度自适应：总宽=屏宽（时间列固定、7 列等比分配），实现整周单屏显示。 */
    private float colW() {
        float available = getWidth() - labelW();
        return Math.max(dp(24), available / columns);
    }

    private float rowH() {
        return dp(46);
    }

    private float headerH() {
        return dp(38);
    }

    private int desiredWidth() {
        return (int) (labelW() + columns * colW());
    }

    private int desiredHeight() {
        return (int) (headerH() + rows * rowH());
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // 宽度直接采用父容器给定宽度（屏宽），不再取 desiredWidth 下限，实现整周单屏显示
        int width;
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            width = desiredWidth();
        } else {
            width = Math.max(Math.round(dp(160)), MeasureSpec.getSize(widthMeasureSpec));
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

    /** 绘制自定义背景（默认等比例裁切铺满 + 半透明遮罩保证课程文字可读，可切换拉伸铺满）。 */
    private void drawBackground(Canvas canvas) {
        if (bgBitmap == null) {
            return;
        }
        float vw = getWidth();
        float vh = getHeight();
        float bw = bgBitmap.getWidth();
        float bh = bgBitmap.getHeight();
        if (bw <= 0 || bh <= 0) {
            return;
        }
        Rect src = new Rect(0, 0, (int) bw, (int) bh);
        RectF dst;
        if (BackgroundManager.MODE_CROP.equals(BackgroundManager.scaleMode(getContext()))) {
            // 等比例裁切（CENTER_CROP 等价）：放大后居中铺满，超出部分裁切，不拉伸变形
            float scale = Math.max(vw / bw, vh / bh);
            float dw = bw * scale;
            float dh = bh * scale;
            dst = new RectF((vw - dw) / 2f, (vh - dh) / 2f,
                    (vw - dw) / 2f + dw, (vh - dh) / 2f + dh);
        } else {
            // 拉伸铺满（非等比缩放填满整个视图）
            dst = new RectF(0, 0, vw, vh);
        }
        canvas.drawBitmap(bgBitmap, src, dst, null);
        canvas.drawColor(0x66000000);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!bgAttempted) {
            reloadBackground();
            if (bgBitmap == null) {
                bgAttempted = true;
            }
        }
        // 壁纸已改由 fragment 根布局绘制（BackgroundManager.backgroundDrawable），此处不再绘制，避免重叠构图割裂
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

        // 课程块：块宽为列宽一半（水平居中），竖向高度不变；同格多课仅绘制当前显示的那一门
        hitRects.clear();
        hitCourses.clear();
        hitSlotKeys.clear();
        hitSlotSizes.clear();
        for (Map.Entry<String, List<Course>> entry : slotGroups.entrySet()) {
            String slotKey = entry.getKey();
            List<Course> group = entry.getValue();
            if (group.isEmpty()) {
                continue;
            }
            String displayKey = slotPicks.get(slotKey);
            Course display = group.get(0);
            for (Course c : group) {
                if (c.key().equals(displayKey)) {
                    display = c;
                    break;
                }
            }
            float cellLeft = labelW + (display.weekday - 1) * colW;
            int start = Math.max(1, Math.min(rows, display.startSession()));
            int end = Math.max(start, Math.min(rows, display.endSession()));
            float top = headerH + (start - 1) * rowH + dp(2);
            float bottom = headerH + end * rowH - dp(2);
            float blockW = colW - dp(8);
            float left = cellLeft + (colW - blockW) / 2f;
            RectF rect = new RectF(left, top, left + blockW, bottom);
            drawBlock(canvas, rect, display);
            hitRects.add(new RectF(rect));
            hitCourses.add(display);
            hitSlotKeys.add(slotKey);
            hitSlotSizes.add(group.size());
        }
    }

    private void drawBlock(Canvas canvas, RectF rect, Course course) {
        int color = BLOCK_COLORS[Math.abs(course.name.hashCode()) % BLOCK_COLORS.length];
        blockPaint.setColor(color);
        // 按用户设置的透明度叠加 alpha（百分比 -> 0-255），使自定义背景透过课程块可见
        int alphaPercent = BackgroundManager.blockAlphaPercent(getContext());
        blockPaint.setAlpha(Math.round(alphaPercent * 2.55f));
        float radius = dp(6);
        canvas.drawRoundRect(rect, radius, radius, blockPaint);

        float pad = dp(3);
        float maxWidth = rect.width() - pad * 2;
        if (maxWidth < dp(10)) {
            return;
        }
        String name = course.name == null ? "" : course.name;
        String room = course.room == null ? "" : course.room;
        boolean hasRoom = !room.isEmpty();

        float scaled = getResources().getDisplayMetrics().scaledDensity;
        float roomLineH = bodyText.getFontSpacing() + dp(2);
        float roomH = hasRoom ? roomLineH : 0f;

        // 课程名：自动换行为多行完整显示；行数超出块内可用高度时逐级缩小字号（12.5sp -> 9sp）适配，不截断文字
        float titleSize = 12.5f * scaled;
        float minTitleSize = 9f * scaled;
        StaticLayout title = null;
        while (titleSize >= minTitleSize) {
            titleText.setTextSize(titleSize);
            int maxTitleLines = (int) Math.max(1,
                    Math.floor((rect.height() - pad * 2 - roomH) / titleText.getFontSpacing()));
            title = StaticLayout.Builder.obtain(name, 0, name.length(), titleText,
                    Math.max(1, (int) maxWidth))
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setMaxLines(maxTitleLines)
                    .setEllipsize(null)
                    .build();
            if (title.getLineCount() <= maxTitleLines) {
                break;
            }
            titleSize -= 0.5f * scaled;
        }
        if (title == null) {
            return;
        }

        canvas.save();
        canvas.translate(rect.left + pad, rect.top + pad);
        title.draw(canvas);
        canvas.restore();

        // 教室：显示在课程名下方（@ 前缀）；过长时省略号截断，避免溢出块边界
        if (hasRoom) {
            float roomY = rect.top + pad + title.getHeight() + dp(2) + roomLineH;
            float roomBottomLimit = rect.bottom - pad;
            if (roomY + bodyText.descent() > roomBottomLimit) {
                roomY = roomBottomLimit - bodyText.descent();
            }
            String roomText = TextUtils.ellipsize("@" + room, bodyText, maxWidth,
                    TextUtils.TruncateAt.END).toString();
            canvas.drawText(roomText, rect.left + pad, roomY, bodyText);
        }
        // 教师 / 周次等完整信息通过点击课程块弹出的详情查看
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
                int index = findHitIndex(event.getX(), event.getY());
                if (index < 0) {
                    return true;
                }
                String slotKey = hitSlotKeys.get(index);
                int groupSize = hitSlotSizes.get(index);
                if (groupSize > 1) {
                    // 同格多门课：先弹选择框由用户决定显示哪一门
                    showPickDialog(slotKey);
                } else if (listener != null) {
                    listener.onCourseClick(hitCourses.get(index));
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

    /** 弹出同格多课选择框，选中后持久化并重绘。 */
    private void showPickDialog(final String slotKey) {
        final List<Course> group = slotGroups.get(slotKey);
        if (group == null || group.size() <= 1 || getContext() == null) {
            return;
        }
        final String[] items = new String[group.size()];
        for (int i = 0; i < group.size(); i++) {
            Course c = group.get(i);
            String room = c.room == null ? "" : c.room;
            items[i] = c.name + (room.isEmpty() ? "" : " · " + room);
        }
        new AlertDialog.Builder(getContext())
                .setTitle("该时段有多门课，选择要显示的课程")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Course chosen = group.get(which);
                        slotPicks.put(slotKey, chosen.key());
                        getContext().getSharedPreferences(PREF_PICKS, Context.MODE_PRIVATE)
                                .edit()
                                .putString(KEY_PICK_PREFIX + slotKey, chosen.key())
                                .apply();
                        invalidate();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int findHitIndex(float x, float y) {
        for (int i = hitRects.size() - 1; i >= 0; i--) {
            RectF r = hitRects.get(i);
            if (r.contains(x, y)) {
                return i;
            }
        }
        return -1;
    }

    private float dp(float value) {
        return value * density;
    }
}
