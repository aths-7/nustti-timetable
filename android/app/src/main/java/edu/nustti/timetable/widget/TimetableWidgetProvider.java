package edu.nustti.timetable.widget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.text.TextUtils;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import edu.nustti.timetable.R;
import edu.nustti.timetable.api.Async;
import edu.nustti.timetable.data.SessionStore;
import edu.nustti.timetable.data.TimetableRepository;
import edu.nustti.timetable.model.Course;
import edu.nustti.timetable.model.TimetableResult;

/**
 * 桌面课表小组件：从本地缓存渲染「整周课表」（周一~周日 × 第 1~5 周网格），
 * 高亮今天所在列；点击刷新时复用 App 内已有的自动重登录链路从教务系统官网拉取最新课表
 * （无网络/无凭据时回退到缓存）。
 *
 * <p>组件本身不依赖 Activity，所有数据来自 {@link TimetableRepository#cached()}
 * 或后台 {@link TimetableRepository#fetchFromJwgl()}（后者会在会话失效时用
 * 本地保存的学号/密码自动重新登录一次）。</p>
 */
public class TimetableWidgetProvider extends AppWidgetProvider {

    /** 小组件主动刷新（点击刷新按钮）。 */
    public static final String ACTION_REFRESH = "edu.nustti.timetable.widget.REFRESH";
    /** 课表缓存变化/清除时由 SessionStore 发出，用于即时刷新所有小组件。 */
    public static final String ACTION_TIMETABLE_CHANGED =
            "edu.nustti.timetable.widget.TIMETABLE_CHANGED";

    /** 网格行数（第 1~5 周）。 */
    private static final int WEEK_ROWS = 5;
    /** 单元格最多展示的课程数。 */
    private static final int MAX_CELLS = 2;

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        renderAll(context, appWidgetManager, appWidgetIds);
    }

    @Override
    public void onEnabled(Context context) {
        renderAll(context, AppWidgetManager.getInstance(context),
                AppWidgetManager.getInstance(context).getAppWidgetIds(
                        new ComponentName(context, TimetableWidgetProvider.class)));
    }

    @Override
    public void onReceive(final Context context, Intent intent) {
        super.onReceive(context, intent);
        final String action = intent == null ? null : intent.getAction();
        if (ACTION_REFRESH.equals(action)) {
            // 后台拉取（复用自动重登录），结束后再渲染。使用 goAsync 避免 10s ANR。
            final AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            final int[] ids = mgr.getAppWidgetIds(
                    new ComponentName(context, TimetableWidgetProvider.class));
            showLoading(context, mgr, ids);
            final android.content.BroadcastReceiver.PendingResult result = goAsync();
            Async.run(new Async.Task<TimetableResult>() {
                @Override
                public TimetableResult run() throws Exception {
                    return new TimetableRepository(context).fetchFromJwgl();
                }
            }, new Async.Done<TimetableResult>() {
                @Override
                public void onResult(TimetableResult data) {
                    renderAll(context, mgr, ids);
                    if (result != null) {
                        result.finish();
                    }
                }
            }, new Async.Fail() {
                @Override
                public void onError(Exception e) {
                    // 刷新失败：仍用本地缓存渲染，保证桌面组件始终有内容
                    renderAll(context, mgr, ids);
                    if (result != null) {
                        result.finish();
                    }
                }
            });
        } else if (ACTION_TIMETABLE_CHANGED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_DATE_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                && "android.app.action.CONFIGURATION_CHANGED".equals(action))) {
            final AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            renderAll(context, mgr, mgr.getAppWidgetIds(
                    new ComponentName(context, TimetableWidgetProvider.class)));
        }
    }

    // ------------------------------------------------------------------ //

    private static void showLoading(Context context, AppWidgetManager mgr, int[] ids) {
        RemoteViews views = buildBaseViews(context);
        views.setViewVisibility(R.id.widget_progress, android.view.View.VISIBLE);
        views.setViewVisibility(R.id.widget_grid, android.view.View.GONE);
        views.setViewVisibility(R.id.widget_header, android.view.View.GONE);
        views.setViewVisibility(R.id.widget_empty, android.view.View.GONE);
        views.setTextViewText(R.id.widget_subtitle, context.getString(R.string.widget_loading));
        for (int id : ids) {
            mgr.updateAppWidget(id, views);
        }
    }

    private static void renderAll(Context context, AppWidgetManager mgr, int[] ids) {
        TimetableRepository repository = new TimetableRepository(context);
        TimetableResult data = repository.cached();
        SessionStore store = repository.store();
        int week = store.getCurrentWeek();
        RemoteViews views = buildViews(context, data, store, week);
        for (int id : ids) {
            mgr.updateAppWidget(id, views);
        }
    }

    private static RemoteViews buildBaseViews(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_timetable);

        // 点击标题/底框打开主界面（显式 PendingIntent，exported=false 不影响）
        Intent open = new Intent(context, edu.nustti.timetable.ui.MainActivity.class);
        open.putExtra(edu.nustti.timetable.ui.MainActivity.EXTRA_FROM_WIDGET, true);
        android.app.PendingIntent openPi = android.app.PendingIntent.getActivity(
                context, 0, open,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
                        | android.app.PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, openPi);

        // 点击刷新
        Intent refresh = new Intent(context, TimetableWidgetProvider.class)
                .setAction(ACTION_REFRESH);
        android.app.PendingIntent refreshPi = android.app.PendingIntent.getBroadcast(
                context, 0, refresh,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
                        | android.app.PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.btn_refresh, refreshPi);
        return views;
    }

    /** 计算本周一及之后 7 天的日期标签（如 周一10/13）。 */
    private static String[] weekdayLabels() {
        Calendar cal = Calendar.getInstance();
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        int offsetToMonday = (dow == Calendar.SUNDAY) ? -6 : Calendar.MONDAY - dow;
        cal.add(Calendar.DAY_OF_MONTH, offsetToMonday);
        SimpleDateFormat sdf = new SimpleDateFormat("M/d", Locale.CHINA);
        String[] labels = new String[7];
        String[] names = {"一", "二", "三", "四", "五", "六", "日"};
        for (int i = 0; i < 7; i++) {
            labels[i] = names[i] + sdf.format(cal.getTime());
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        return labels;
    }

    private static RemoteViews buildViews(Context context, TimetableResult data,
                                          SessionStore store, int week) {
        RemoteViews views = buildBaseViews(context);
        views.setViewVisibility(R.id.widget_progress, android.view.View.GONE);

        Calendar cal = Calendar.getInstance();
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        int todayWeekday = (dow == Calendar.SUNDAY) ? 7 : dow - 1; // 1=周一 .. 7=周日
        String[] labels = weekdayLabels();

        // 副标题：本周日期区间 + 第 N 周
        String range = labels[0].replaceAll("^.", "") + " - " + labels[6].replaceAll("^.", "");
        views.setTextViewText(R.id.widget_subtitle,
                range + " · " + context.getString(R.string.widget_week_format, week));

        if (data == null || data.courses == null || data.courses.isEmpty()) {
            views.setViewVisibility(R.id.widget_header, android.view.View.GONE);
            views.setViewVisibility(R.id.widget_grid, android.view.View.GONE);
            views.setViewVisibility(R.id.widget_empty, android.view.View.VISIBLE);
            views.setTextViewText(R.id.widget_empty,
                    context.getString(R.string.widget_no_timetable));
            return views;
        }

        // 表头：周一~周日
        views.setViewVisibility(R.id.widget_header, android.view.View.VISIBLE);
        views.setViewVisibility(R.id.widget_grid, android.view.View.VISIBLE);
        views.setViewVisibility(R.id.widget_empty, android.view.View.GONE);
        views.removeAllViews(R.id.widget_header);
        for (int d = 0; d < 7; d++) {
            RemoteViews h = new RemoteViews(context.getPackageName(), R.layout.widget_header_cell);
            h.setTextViewText(R.id.wh_label, labels[d]);
            views.addView(R.id.widget_header, h);
        }

        // 网格：第 1~5 周 × 周一~周日
        views.removeAllViews(R.id.widget_grid);
        for (int w = 1; w <= WEEK_ROWS; w++) {
            RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_row);
            // 行首：第 w 周
            RemoteViews weekLabel = new RemoteViews(context.getPackageName(),
                    R.layout.widget_header_cell);
            weekLabel.setTextViewText(R.id.wh_label, "W" + w);
            row.addView(R.id.widget_row, weekLabel);
            for (int d = 1; d <= 7; d++) {
                List<Course> day = data.coursesOfDay(d, w);
                RemoteViews cell = new RemoteViews(context.getPackageName(), R.layout.widget_cell);
                if (day.isEmpty()) {
                    cell.setTextViewText(R.id.wc_name, "—");
                    cell.setTextViewText(R.id.wc_more, "");
                } else {
                    StringBuilder sb = new StringBuilder();
                    int n = Math.min(day.size(), MAX_CELLS);
                    for (int i = 0; i < n; i++) {
                        if (i > 0) {
                            sb.append("\n");
                        }
                        sb.append(day.get(i).name);
                    }
                    cell.setTextViewText(R.id.wc_name, sb.toString());
                    if (day.size() > n) {
                        cell.setTextViewText(R.id.wc_more, "+" + (day.size() - n));
                        cell.setViewVisibility(R.id.wc_more, android.view.View.VISIBLE);
                    } else {
                        cell.setTextViewText(R.id.wc_more, "");
                        cell.setViewVisibility(R.id.wc_more, android.view.View.GONE);
                    }
                }
                // 高亮今天所在列（且仅当这一行就是当前周）
                if (w == week && d == todayWeekday) {
                    cell.setInt(R.id.wc_name, "setBackgroundResource", R.drawable.widget_cell_today);
                }
                row.addView(R.id.widget_row, cell);
            }
            views.addView(R.id.widget_grid, row);
        }
        return views;
    }
}
