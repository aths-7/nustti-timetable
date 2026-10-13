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
 * 桌面课表小组件：从本地缓存渲染「今日课表」，点击刷新时复用 App 内已有的
 * 自动重登录链路从教务系统官网拉取最新课表（无网络/无凭据时回退到缓存）。
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

    private static final int MAX_ROWS = 8;

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
        views.setViewVisibility(R.id.widget_courses, android.view.View.GONE);
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

    private static RemoteViews buildViews(Context context, TimetableResult data,
                                          SessionStore store, int week) {
        RemoteViews views = buildBaseViews(context);
        views.setViewVisibility(R.id.widget_progress, android.view.View.GONE);

        Calendar cal = Calendar.getInstance();
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        int weekday = (dow == Calendar.SUNDAY) ? 7 : dow - 1; // 1=周一 .. 7=周日

        // 副标题：日期 + 第 N 周
        String dateLabel = new SimpleDateFormat("M月d日 E", Locale.CHINA).format(new Date());
        String weekLabel = context.getString(R.string.widget_week_format, week);
        views.setTextViewText(R.id.widget_subtitle, dateLabel + " · " + weekLabel);

        if (data == null || data.courses == null || data.courses.isEmpty()) {
            views.setViewVisibility(R.id.widget_courses, android.view.View.GONE);
            views.setViewVisibility(R.id.widget_empty, android.view.View.VISIBLE);
            views.setTextViewText(R.id.widget_empty,
                    context.getString(R.string.widget_no_timetable));
            return views;
        }

        List<Course> today = data.coursesOfDay(weekday, week);
        if (today.isEmpty()) {
            views.setViewVisibility(R.id.widget_courses, android.view.View.GONE);
            views.setViewVisibility(R.id.widget_empty, android.view.View.VISIBLE);
            views.setTextViewText(R.id.widget_empty,
                    context.getString(R.string.widget_no_course_today));
            return views;
        }

        views.setViewVisibility(R.id.widget_courses, android.view.View.VISIBLE);
        views.setViewVisibility(R.id.widget_empty, android.view.View.GONE);
        views.removeAllViews(R.id.widget_courses);

        int count = Math.min(today.size(), MAX_ROWS);
        for (int i = 0; i < count; i++) {
            Course c = today.get(i);
            RemoteViews row = new RemoteViews(context.getPackageName(),
                    R.layout.widget_course_item);
            row.setTextViewText(R.id.wi_name, c.name);
            String time = c.timeText();
            String detail = c.room;
            if (!TextUtils.isEmpty(c.teacher)) {
                detail = (detail.isEmpty() ? "" : detail + " · ") + c.teacher;
            }
            if (!TextUtils.isEmpty(time)) {
                detail = (detail.isEmpty() ? "" : detail + "\n") + time + "节";
            }
            row.setTextViewText(R.id.wi_detail, detail);
            views.addView(R.id.widget_courses, row);
        }

        if (today.size() > count) {
            RemoteViews more = new RemoteViews(context.getPackageName(),
                    R.layout.widget_course_item);
            more.setTextViewText(R.id.wi_name, "…还有 " + (today.size() - count) + " 节");
            more.setTextViewText(R.id.wi_detail, "点击打开 App 查看完整课表");
            views.addView(R.id.widget_courses, more);
        }
        return views;
    }
}
