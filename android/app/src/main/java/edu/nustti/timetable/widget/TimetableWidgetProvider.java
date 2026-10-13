package edu.nustti.timetable.widget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.widget.RemoteViews;

import java.util.Calendar;

import edu.nustti.timetable.R;
import edu.nustti.timetable.api.Async;
import edu.nustti.timetable.data.SessionStore;
import edu.nustti.timetable.data.TimetableRepository;
import edu.nustti.timetable.model.TimetableResult;

/**
 * 桌面课表小组件：以位图方式渲染「本周课表」，视觉风格与 App 内整周视图保持一致。
 *
 * <p>点击刷新时复用 App 内已有的自动重登录链路从教务系统官网拉取最新课表
 * （无网络/无凭据时回退到缓存）。</p>
 */
public class TimetableWidgetProvider extends AppWidgetProvider {

    /** 小组件主动刷新（点击刷新按钮）。 */
    public static final String ACTION_REFRESH = "edu.nustti.timetable.widget.REFRESH";
    /** 课表缓存变化/清除时由 SessionStore 发出，用于即时刷新所有小组件。 */
    public static final String ACTION_TIMETABLE_CHANGED =
            "edu.nustti.timetable.widget.TIMETABLE_CHANGED";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        renderAll(context, appWidgetManager, appWidgetIds);
    }

    @Override
    public void onEnabled(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        renderAll(context, mgr, mgr.getAppWidgetIds(
                new ComponentName(context, TimetableWidgetProvider.class)));
    }

    @Override
    public void onReceive(final Context context, Intent intent) {
        super.onReceive(context, intent);
        final String action = intent == null ? null : intent.getAction();
        if (ACTION_REFRESH.equals(action)) {
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
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            renderAll(context, mgr, mgr.getAppWidgetIds(
                    new ComponentName(context, TimetableWidgetProvider.class)));
        }
    }

    // ------------------------------------------------------------------ //

    private static void showLoading(Context context, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) {
            RemoteViews views = buildBaseViews(context);
            views.setViewVisibility(R.id.widget_progress, android.view.View.VISIBLE);
            views.setViewVisibility(R.id.widget_image, android.view.View.GONE);
            views.setViewVisibility(R.id.widget_empty, android.view.View.GONE);
            views.setTextViewText(R.id.widget_subtitle, context.getString(R.string.widget_loading));
            mgr.updateAppWidget(id, views);
        }
    }

    private static void renderAll(Context context, AppWidgetManager mgr, int[] ids) {
        TimetableRepository repository = new TimetableRepository(context);
        TimetableResult data = repository.cached();
        SessionStore store = repository.store();
        int week = store.getCurrentWeek();
        for (int id : ids) {
            RemoteViews views = buildViews(context, mgr, id, data, week);
            mgr.updateAppWidget(id, views);
        }
    }

    /** 供外观设置页调用：透明度等设置变更后立即重绘所有小组件。 */
    public static void refreshWidgets(Context context) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            int[] ids = mgr.getAppWidgetIds(
                    new ComponentName(context, TimetableWidgetProvider.class));
            if (ids != null && ids.length > 0) {
                renderAll(context, mgr, ids);
            }
        } catch (Exception ignored) {
            // 未添加小组件或系统异常时静默忽略
        }
    }

    private static RemoteViews buildBaseViews(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_timetable);

        // 整体不透明度：设置在根布局上，连同背景与内容一起透出桌面壁纸
        int alphaPercent = new SessionStore(context).getWidgetAlphaPercent();
        try {
            views.setFloat(R.id.widget_root, "setAlpha", alphaPercent / 100f);
        } catch (Throwable ignored) {
            // 个别厂商 Launcher 对该 RemoteViews 反射调用不兼容时忽略，不影响显示
        }

        Intent open = new Intent(context, edu.nustti.timetable.ui.MainActivity.class);
        open.putExtra(edu.nustti.timetable.ui.MainActivity.EXTRA_FROM_WIDGET, true);
        android.app.PendingIntent openPi = android.app.PendingIntent.getActivity(
                context, 0, open,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
                        | android.app.PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, openPi);
        views.setOnClickPendingIntent(R.id.widget_image, openPi);

        Intent refresh = new Intent(context, TimetableWidgetProvider.class)
                .setAction(ACTION_REFRESH);
        android.app.PendingIntent refreshPi = android.app.PendingIntent.getBroadcast(
                context, 0, refresh,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
                        | android.app.PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.btn_refresh, refreshPi);
        return views;
    }

    private static RemoteViews buildViews(Context context, AppWidgetManager mgr,
                                          int appWidgetId, TimetableResult data, int week) {
        RemoteViews views = buildBaseViews(context);
        views.setViewVisibility(R.id.widget_progress, android.view.View.GONE);

        Calendar cal = Calendar.getInstance();
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        int todayWeekday = (dow == Calendar.SUNDAY) ? 7 : dow - 1;
        views.setTextViewText(R.id.widget_subtitle,
                "第 " + week + " 周 · 今天 " + new String[]{"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"}[todayWeekday]);

        if (data == null || data.courses == null || data.courses.isEmpty()) {
            views.setViewVisibility(R.id.widget_image, android.view.View.GONE);
            views.setViewVisibility(R.id.widget_empty, android.view.View.VISIBLE);
            views.setTextViewText(R.id.widget_empty,
                    context.getString(R.string.widget_no_timetable));
            return views;
        }

        int[] size = widgetSize(context, mgr, appWidgetId);
        Bitmap bitmap = WidgetBitmapRenderer.render(context, data, week, size[0], size[1]);
        if (bitmap == null) {
            views.setViewVisibility(R.id.widget_image, android.view.View.GONE);
            views.setViewVisibility(R.id.widget_empty, android.view.View.VISIBLE);
            views.setTextViewText(R.id.widget_empty,
                    context.getString(R.string.widget_no_timetable));
            return views;
        }

        views.setViewVisibility(R.id.widget_image, android.view.View.VISIBLE);
        views.setViewVisibility(R.id.widget_empty, android.view.View.GONE);
        views.setImageViewBitmap(R.id.widget_image, bitmap);
        return views;
    }

    /**
     * 估算小组件实际像素尺寸。优先使用 AppWidgetManager 返回的 options；
     * 取不到时用 minWidth/minHeight 乘以屏幕密度兜底。
     */
    private static int[] widgetSize(Context context, AppWidgetManager mgr, int appWidgetId) {
        Bundle options = mgr.getAppWidgetOptions(appWidgetId);
        float density = context.getResources().getDisplayMetrics().density;
        int w = dpToPx(320, density);
        int h = dpToPx(480, density);
        if (options != null) {
            int minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
            int maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0);
            int minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
            int maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
            if (maxW > 0 && maxH > 0) {
                // 用 max 作为实际尺寸上限，渲染在该区域内
                w = (int) (maxW * density);
                h = (int) (maxH * density);
            } else if (minW > 0 && minH > 0) {
                w = (int) (minW * density);
                h = (int) (minH * density);
            }
        }
        // 保证最小绘制区域，避免课程名无法显示
        w = Math.max(w, dpToPx(280, density));
        h = Math.max(h, dpToPx(400, density));
        return new int[]{w, h};
    }

    private static int dpToPx(int dp, float density) {
        return Math.round(dp * density);
    }
}
