package edu.nustti.timetable.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import edu.nustti.timetable.R;
import edu.nustti.timetable.data.SessionStore;
import edu.nustti.timetable.edu.JwglSession;

/** 数据来源详情页：来源说明、缓存状态、演示数据/教务接口状态与退出登录入口，返回可回设置页。 */
public class DataSourceActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_datasource);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        TextView tvSourceInfo = findViewById(R.id.tvSourceInfo);
        tvSourceInfo.setText("数据来源：南京理工大学泰州科技学院教务系统官网\n"
                + "地址：https://jwgl.nustti.edu.cn/jsxsd/\n"
                + "登录：POST /jsxsd/xk/LoginToXk（学号 + 密码，无验证码）\n"
                + "课表：GET /jsxsd/xskb/xskb_list.do?xnxq01id=学期\n"
                + "说明：手机端直连官网，不经过任何中间服务端");

        TextView tvState = findViewById(R.id.tvState);
        refreshState(tvState);

        Button btnLogout = findViewById(R.id.btnLogout);
        btnLogout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MainActivity main = MainActivity.instance();
                if (main != null) {
                    main.confirmLogout();
                } else {
                    toast("请返回设置页后退出登录");
                }
            }
        });
    }

    private void refreshState(TextView tvState) {
        SessionStore store = new SessionStore(this);
        String json = store.loadTimetableJson();
        String jsonSize = json == null || json.isEmpty() ? "无" : formatSize(json.getBytes().length);
        long at = store.cacheTime();
        String time = at <= 0 ? "无" : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date(at));
        String from = cacheFromLabel(store.cacheFrom());
        String term = store.getTermLabel().isEmpty() ? store.getTerm() : store.getTermLabel();
        tvState.setText("数据接口：直连教务系统官网"
                + (JwglSession.ready() ? "（已登录）" : "（未登录，刷新时自动重新登录）") + "\n"
                + "当前来源：" + from + "\n"
                + "缓存最后更新时间：" + time + "\n"
                + "课表缓存大小：约 " + jsonSize + "\n"
                + "当前学期：" + (term.isEmpty() ? "（未选择）" : term) + "\n"
                + "演示数据：仅用于免登录体验，刷新后会标记为演示数据来源");
    }

    private static String cacheFromLabel(String from) {
        if ("demo".equals(from)) {
            return "演示数据";
        } else if ("jwgl".equals(from) || "server".equals(from)) {
            return "教务系统官网";
        }
        return "未知";
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.CHINA, "%.2f MB", bytes / (1024.0 * 1024.0));
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
