package edu.nustti.timetable.ui;

import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import java.util.List;

import edu.nustti.timetable.R;
import edu.nustti.timetable.api.Async;
import edu.nustti.timetable.data.SessionStore;
import edu.nustti.timetable.data.TimetableRepository;
import edu.nustti.timetable.edu.JwglSession;
import edu.nustti.timetable.model.TimetableResult;

/**
 * 主界面：底部三个页签（整周 / 今日 / 设置），统一持有课表数据并向下分发。
 *
 * <p>课表由手机端直连教务系统官网获取，刷新失败的判断依据是教务系统返回的登录态与错误信息。</p>
 */
public class MainActivity extends AppCompatActivity {

    public static final String EXTRA_REFRESH = "refreshFromServer";

    /** 课表数据订阅者（各课表视图）。 */
    public interface DataListener {
        void onTimetable(TimetableResult data, int week);
    }

    private TimetableRepository repository;
    private TimetableResult data;
    private int week = 1;
    private ViewPager2 viewPager;
    private Toast currentToast;
    /** toolbar 原始 layoutParams.height（首次 insets 分发时记录，避免多次分发重复累加）。 */
    private int toolbarBaseHeight = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 边缘到边缘：内容延伸绘制到状态栏与系统导航栏区域，避免底部系统栏露出白色背景
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        applyWindowBackground();
        applyWindowInsets();

        // 顶部玻璃标题栏为浅色玻璃底，状态栏图标改用深色保证可见
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(true);

        repository = new TimetableRepository(this);
        week = repository.store().getCurrentWeek();
        data = repository.cached();

        GlassToolbarView toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(getString(R.string.app_name));
        // 应用用户自定义的顶部主题颜色（默认品牌蓝）
        applyThemeColor();
        // 右上角三个点独立小玻璃容器：点击弹出刷新 / 退出菜单，行为与原先一致
        toolbar.getMenuButton().setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PopupMenu popup = new PopupMenu(MainActivity.this, v);
                popup.getMenuInflater().inflate(R.menu.main_menu, popup.getMenu());
                popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(MenuItem item) {
                        int id = item.getItemId();
                        if (id == R.id.action_refresh) {
                            showRefreshDialog();
                            return true;
                        }
                        if (id == R.id.action_logout) {
                            confirmLogout();
                            return true;
                        }
                        return false;
                    }
                });
                popup.show();
            }
        });

        bottomNavSetup();

        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_REFRESH, false)) {
            refresh(false);
        }
    }

    /** 边缘到边缘 inset 适配：toolbar 整体加高（原始高度 + 状态栏）使玻璃背景覆盖状态栏、标题进入安全区；Dock 距底固定 30dp。 */
    private void applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(getWindow().getDecorView(), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            GlassToolbarView tb = findViewById(R.id.toolbar);
            if (tb != null) {
                ViewGroup.LayoutParams lp = tb.getLayoutParams();
                if (toolbarBaseHeight < 0) {
                    toolbarBaseHeight = lp.height;
                }
                lp.height = toolbarBaseHeight + bars.top;
                tb.setLayoutParams(lp);
                tb.setPadding(tb.getPaddingLeft(), bars.top, tb.getPaddingRight(),
                        tb.getPaddingBottom());
            }
            DockBarView db = findViewById(R.id.dockBar);
            if (db != null) {
                int gap = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 30f,
                        getResources().getDisplayMetrics()));
                db.setPadding(db.getPaddingLeft(), db.getPaddingTop(), db.getPaddingRight(), gap);
            }
            return insets;
        });
    }

    private void bottomNavSetup() {
        viewPager = findViewById(R.id.viewPager);
        final DockBarView dockBar = findViewById(R.id.dockBar);

        viewPager.setAdapter(new FragmentStateAdapter(this) {
            @NonNull
            @Override
            public Fragment createFragment(int position) {
                switch (position) {
                    case 0:
                        return new WeekFragment();
                    case 1:
                        return new TodayFragment();
                    default:
                        return new SettingsFragment();
                }
            }

            @Override
            public int getItemCount() {
                return 3;
            }
        });
        viewPager.setOffscreenPageLimit(2);
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                dockBar.setSelectedIndex(position);
            }
        });
        // 苹果 Dock 样式液态玻璃导航栏：点击图标弹性放大上浮并切换页面
        dockBar.setOnDockItemSelectedListener(new DockBarView.OnDockItemSelectedListener() {
            @Override
            public void onDockItemSelected(int position) {
                viewPager.setCurrentItem(position, true);
            }
        });
    }

    // ------------------------------------------------------------------ //
    // 对外：数据访问与分发
    // ------------------------------------------------------------------ //

    public TimetableRepository repository() {
        return repository;
    }

    public TimetableResult data() {
        return data;
    }

    public int week() {
        return week;
    }

    /** 切换当前周次（1..maxWeek）。 */
    public void setWeek(int target) {
        int max = data == null ? 20 : data.maxWeek();
        int clamped = Math.max(1, Math.min(max, target));
        if (clamped == week) {
            return;
        }
        week = clamped;
        repository.store().setCurrentWeek(week);
        dispatch();
    }

    /** 订阅者就绪后主动索取一次数据。 */
    public void requestData(DataListener listener) {
        if (listener != null) {
            listener.onTimetable(data, week);
        }
    }

    public void dispatch() {
        List<Fragment> fragments = getSupportFragmentManager().getFragments();
        for (Fragment fragment : fragments) {
            if (fragment instanceof DataListener) {
                ((DataListener) fragment).onTimetable(data, week);
            }
        }
    }

    /** 自定义背景变化后通知周视图 / 今日页重新加载背景，并事件驱动刷新 Dock 毛玻璃模糊源。 */
    public void notifyBackgroundChanged() {
        applyWindowBackground();
        DockBarView dockBar = findViewById(R.id.dockBar);
        if (dockBar != null) {
            dockBar.refreshBlurBackground();
        }
        List<Fragment> fragments = getSupportFragmentManager().getFragments();
        for (Fragment fragment : fragments) {
            if (fragment instanceof WeekFragment) {
                ((WeekFragment) fragment).reloadBackground();
            } else if (fragment instanceof TodayFragment) {
                ((TodayFragment) fragment).reloadBackground();
            }
        }
    }

    /** 将「壁纸+加深」背景应用到窗口根（android.R.id.content），铺满含状态栏/导航栏的全屏区域；
     *  各 Fragment 根布局为透明，透出该统一背景；未启用壁纸时回退默认 surface 底色。 */
    private void applyWindowBackground() {
        View content = findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        Drawable bg = BackgroundManager.backgroundDrawable(this);
        if (bg != null) {
            content.setBackground(bg);
        } else {
            content.setBackgroundResource(R.color.surface);
        }
    }

    /** 应用用户自定义的顶部主题颜色到玻璃标题栏（默认品牌蓝），设置页修改后调用即时生效。 */
    public void applyThemeColor() {
        GlassToolbarView tb = findViewById(R.id.toolbar);
        if (tb == null) {
            return;
        }
        tb.setThemeColor(new SessionStore(this).getThemeColor());
    }

    public void refresh(boolean demo) {
        toast(demo ? "正在加载演示课表..." : "正在直连教务系统官网刷新课表...");
        Async.run(new Async.Task<TimetableResult>() {
            @Override
            public TimetableResult run() throws Exception {
                return demo ? repository.fetchDemo() : repository.fetchFromJwgl();
            }
        }, new Async.Done<TimetableResult>() {
            @Override
            public void onResult(TimetableResult result) {
                data = result;
                week = Math.max(1, Math.min(result.maxWeek(), week));
                repository.store().setCurrentWeek(week);
                dispatch();
                toast("课表已更新：共 " + result.courses.size() + " 门课，第 " + week + "/"
                        + result.maxWeek() + " 周");
            }
        }, new Async.Fail() {
            @Override
            public void onError(Exception e) {
                String msg = e == null || e.getMessage() == null ? "未知错误" : e.getMessage();
                toast("刷新失败：" + msg);
                if (msg.contains("请先填写学号与密码") || msg.contains("重新登录")
                        || msg.contains("登录失败")) {
                    startActivity(new Intent(MainActivity.this, LoginActivity.class));
                    finish();
                    return;
                }
                if (data == null) {
                    TimetableResult cached = repository.cached();
                    if (cached != null) {
                        data = cached;
                        dispatch();
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------------ //

    private void showRefreshDialog() {
        String[] items = {"从教务系统官网刷新（需已登录）", "加载演示课表"};
        new AlertDialog.Builder(this)
                .setTitle(R.string.action_refresh)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        refresh(which == 1);
                    }
                })
                .show();
    }

    public void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.action_logout)
                .setMessage("将清除本地登录状态与课表缓存，确定退出吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("退出", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        doLogout();
                    }
                })
                .show();
    }

    private void doLogout() {
        JwglSession.clear();
        repository.store().clearTimetable();
        toast("已退出登录");
        startActivity(new Intent(MainActivity.this, LoginActivity.class));
        finish();
    }

    private void toast(String text) {
        if (currentToast != null) {
            currentToast.cancel();
        }
        currentToast = Toast.makeText(this, text, Toast.LENGTH_SHORT);
        currentToast.show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }
}
