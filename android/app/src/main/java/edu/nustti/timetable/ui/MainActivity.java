package edu.nustti.timetable.ui;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.List;

import edu.nustti.timetable.R;
import edu.nustti.timetable.api.Async;
import edu.nustti.timetable.data.TimetableRepository;
import edu.nustti.timetable.edu.JwglSession;
import edu.nustti.timetable.model.TimetableResult;

/**
 * 主界面：底部四个页签（整周 / 今日 / 紧凑 / 设置），统一持有课表数据并向下分发。
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        repository = new TimetableRepository(this);
        week = repository.store().getCurrentWeek();
        data = repository.cached();

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.app_name);
        toolbar.inflateMenu(R.menu.main_menu);
        toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
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

        bottomNavSetup();

        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_REFRESH, false)) {
            refresh(false);
        }
    }

    private void bottomNavSetup() {
        viewPager = findViewById(R.id.viewPager);
        final BottomNavigationView bottomNav = findViewById(R.id.bottomNav);

        viewPager.setAdapter(new FragmentStateAdapter(this) {
            @NonNull
            @Override
            public Fragment createFragment(int position) {
                switch (position) {
                    case 0:
                        return new WeekFragment();
                    case 1:
                        return new TodayFragment();
                    case 2:
                        return new CompactFragment();
                    default:
                        return new SettingsFragment();
                }
            }

            @Override
            public int getItemCount() {
                return 4;
            }
        });
        viewPager.setOffscreenPageLimit(3);
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                bottomNav.getMenu().getItem(position).setChecked(true);
            }
        });
        bottomNav.setOnItemSelectedListener(new BottomNavigationView.OnItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                int id = item.getItemId();
                if (id == R.id.nav_week) {
                    viewPager.setCurrentItem(0, true);
                } else if (id == R.id.nav_today) {
                    viewPager.setCurrentItem(1, true);
                } else if (id == R.id.nav_compact) {
                    viewPager.setCurrentItem(2, true);
                } else if (id == R.id.nav_settings) {
                    viewPager.setCurrentItem(3, true);
                }
                return true;
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
