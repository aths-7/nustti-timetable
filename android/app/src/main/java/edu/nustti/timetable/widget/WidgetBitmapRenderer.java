package edu.nustti.timetable.widget;

import android.content.Context;
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

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import edu.nustti.timetable.data.SessionStore;
import edu.nustti.timetable.model.Course;
import edu.nustti.timetable.model.TimetableResult;

/**
 * 将「本周课表」渲染为一张位图，供桌面小组件直接显示。
 *
 * <p>视觉风格与 {@link edu.nustti.timetable.ui.WeekGridView} 保持一致：
 * 暗色背景、课程块按课程名哈希取色、高亮今天列、左侧节次时间标签。</p>
 */
public final class WidgetBitmapRenderer {

    private static final int[] BLOCK_COLORS = {
            Color.parseColor("#3B82F6"), Color.parseColor("#10B981"), Color.parseColor("#F59E0B"),
            Color.parseColor("#EF4444"), Color.parseColor("#8B5CF6"), Color.parseColor("#06B6D4"),
            Color.parseColor("#EC4899"), Color.parseColor("#84CC16"), Color.parseColor("#6366F1"),
            Color.parseColor("#F97316")
    };

    private static final int BG_COLOR = Color.parseColor("#0F172A");
    private static final int HEADER_BG = Color.parseColor("#1E293B");
    private static final int HEADER_TEXT = Color.parseColor("#F1F5F9");
    private static final int TODAY_HEADER_BG = Color.parseColor("#1D4ED8");
    private static final int GRID_LINE = Color.parseColor("#334155");
    private static final int LABEL_TEXT = Color.parseColor("#94A3B8");
    private static final int BLOCK_TEXT = Color.WHITE;
    private static final int EMPTY_TEXT = Color.parseColor("#475569");

    private static final String[] WEEKDAY_NAMES = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private WidgetBitmapRenderer() {
    }

    /**
     * 渲染整周课表位图。
     *
     * @param alphaPercent 背景不透明度百分比（20 - 100）。位图不再铺不透明底色，
     *                     底色由布局中的背景层提供并按此值淡出；此处表头条与网格线
     *                     同样按该值淡出，而课程色块与所有文字始终保持不透明。
     */
    public static Bitmap render(Context context, TimetableResult data, int week,
                                int width, int height, int alphaPercent) {
        if (width <= 0 || height <= 0) {
            width = 720;
            height = 960;
        }
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        // 不铺底色：透明区域透出布局背景层（已按透明度淡出），课程块与文字照常不透明绘制
        int bgAlpha = Math.round(Math.max(0f, Math.min(1f, alphaPercent / 100f)) * 255);

        float density = context.getResources().getDisplayMetrics().density;
        float dp = density;

        int rows = (data != null && data.meta != null && data.meta.sessionTimes != null
                && !data.meta.sessionTimes.isEmpty()) ? data.meta.sessionTimes.size() : 12;
        rows = Math.max(10, rows);

        float headerH = 36 * dp;
        float labelW = 40 * dp;
        float availableW = width - labelW;
        float colW = availableW / 7f;
        float gridH = height - headerH;
        float rowH = gridH / rows;

        Calendar cal = Calendar.getInstance();
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        int todayWeekday = (dow == Calendar.SUNDAY) ? 7 : dow - 1;

        Map<String, List<Course>> slotGroups = groupCourses(data, week);

        // 表头背景（随背景一起淡出）
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(HEADER_BG);
        paint.setAlpha(bgAlpha);
        canvas.drawRect(0, 0, width, headerH, paint);

        TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(HEADER_TEXT);
        textPaint.setTextSize(12 * dp);
        textPaint.setTextAlign(Paint.Align.CENTER);

        // 星期标签
        for (int i = 0; i < 7; i++) {
            float cx = labelW + i * colW + colW / 2f;
            if (i + 1 == todayWeekday) {
                Paint highlight = new Paint(Paint.ANTI_ALIAS_FLAG);
                highlight.setColor(TODAY_HEADER_BG);
                highlight.setAlpha(bgAlpha);
                canvas.drawRect(labelW + i * colW, 0, labelW + (i + 1) * colW, headerH, highlight);
            }
            float baseline = headerH / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;
            canvas.drawText(WEEKDAY_NAMES[i], cx, baseline, textPaint);
        }

        // 网格线（随背景一起淡出）
        Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setColor(GRID_LINE);
        linePaint.setAlpha(bgAlpha);
        linePaint.setStrokeWidth(1 * dp);

        for (int i = 0; i <= 7; i++) {
            float x = labelW + i * colW;
            canvas.drawLine(x, 0, x, height, linePaint);
        }
        for (int r = 0; r <= rows; r++) {
            float y = headerH + r * rowH;
            canvas.drawLine(0, y, width, y, linePaint);
        }

        // 节次标签
        textPaint.setColor(LABEL_TEXT);
        textPaint.setTextSize(10 * dp);
        for (int r = 0; r < rows; r++) {
            float y = headerH + r * rowH + rowH / 2f;
            String time = startTime(data, r + 1);
            canvas.drawText(String.valueOf(r + 1), labelW / 2f, y - 2 * dp, textPaint);
            if (!time.isEmpty()) {
                textPaint.setTextSize(8 * dp);
                canvas.drawText(time, labelW / 2f, y + 10 * dp, textPaint);
                textPaint.setTextSize(10 * dp);
            }
        }

        // 课程块：保持与 App 内 WeekGridView 一致的默认 85% 透明度
        Paint blockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        blockPaint.setStyle(Paint.Style.FILL);
        int blockAlpha = Math.round(0.85f * 255);

        TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(BLOCK_TEXT);
        titlePaint.setFakeBoldText(true);
        titlePaint.setShadowLayer(2 * dp, 0, 1 * dp, 0xB3000000);

        // 教室文字：与 App 内一致（浅色、@前缀、过长省略），显示在课程名下方
        TextPaint roomPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        roomPaint.setColor(Color.parseColor("#F1F5F9"));
        roomPaint.setTextSize(9 * dp);
        roomPaint.setShadowLayer(1.5f * dp, 0, 1 * dp, 0xB3000000);

        for (Map.Entry<String, List<Course>> entry : slotGroups.entrySet()) {
            Course course = entry.getValue().get(0);
            int color = BLOCK_COLORS[Math.abs(course.name.hashCode()) % BLOCK_COLORS.length];
            blockPaint.setColor(color);
            blockPaint.setAlpha(blockAlpha);

            int start = Math.max(1, Math.min(rows, course.startSession()));
            int end = Math.max(start, Math.min(rows, course.endSession()));
            float top = headerH + (start - 1) * rowH + 2 * dp;
            float bottom = headerH + end * rowH - 2 * dp;
            float cellLeft = labelW + (course.weekday - 1) * colW;
            float blockW = colW - 6 * dp;
            float left = cellLeft + (colW - blockW) / 2f;
            RectF rect = new RectF(left, top, left + blockW, bottom);
            float radius = 5 * dp;
            canvas.drawRoundRect(rect, radius, radius, blockPaint);

            // 课程名 + 教室：与 App 内 WeekGridView 一致——标题从块顶部排布，
            // 预留教室行高度后逐级缩小字号；教室以 @ 前缀显示在标题下方，过长省略
            float pad = 3 * dp;
            float maxTextW = rect.width() - pad * 2;
            if (maxTextW < 10 * dp) {
                continue;
            }
            String name = course.name == null ? "" : course.name;
            String room = course.room == null ? "" : course.room;
            boolean hasRoom = !room.isEmpty();
            float roomLineH = roomPaint.getFontSpacing() + 2 * dp;
            float roomH = hasRoom ? roomLineH : 0f;

            float titleSize = 11 * dp;
            float minSize = 7 * dp;
            StaticLayout layout = null;
            while (titleSize >= minSize) {
                titlePaint.setTextSize(titleSize);
                int lines = (int) Math.max(1, Math.floor(
                        (rect.height() - pad * 2 - roomH) / titlePaint.getFontSpacing()));
                layout = StaticLayout.Builder.obtain(name, 0, name.length(), titlePaint,
                                Math.max(1, (int) maxTextW))
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setMaxLines(lines)
                        .build();
                if (layout.getLineCount() <= lines) {
                    break;
                }
                layout = null;
                titleSize -= 0.5f * dp;
            }
            if (layout != null) {
                canvas.save();
                canvas.translate(rect.left + pad, rect.top + pad);
                layout.draw(canvas);
                canvas.restore();

                if (hasRoom) {
                    float roomY = rect.top + pad + layout.getHeight() + 2 * dp + roomLineH;
                    float roomBottomLimit = rect.bottom - pad;
                    if (roomY + roomPaint.descent() > roomBottomLimit) {
                        roomY = roomBottomLimit - roomPaint.descent();
                    }
                    String roomText = TextUtils.ellipsize("@" + room, roomPaint, maxTextW,
                            TextUtils.TruncateAt.END).toString();
                    canvas.drawText(roomText, rect.left + pad, roomY, roomPaint);
                }
            }
        }

        return bmp;
    }

    private static Map<String, List<Course>> groupCourses(TimetableResult data, int week) {
        Map<String, List<Course>> groups = new LinkedHashMap<>();
        if (data == null || data.courses == null) {
            return groups;
        }
        for (Course c : data.courses) {
            if (c == null || c.weekday < 1 || c.weekday > 7 || !c.inWeek(week)) {
                continue;
            }
            String key = c.weekday + ":" + c.startSession() + "-" + c.endSession();
            List<Course> group = groups.get(key);
            if (group == null) {
                group = new ArrayList<>();
                groups.put(key, group);
            }
            group.add(c);
        }
        return groups;
    }

    private static String startTime(TimetableResult data, int session) {
        if (data == null || data.meta == null || data.meta.sessionTimes == null) {
            return "";
        }
        List<String> t = data.meta.sessionTimes.get(String.valueOf(session));
        if (t == null || t.isEmpty()) {
            return "";
        }
        return t.get(0);
    }
}
