package edu.nustti.timetable.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;

import edu.nustti.timetable.R;
import edu.nustti.timetable.api.Async;
import edu.nustti.timetable.data.SessionStore;
import edu.nustti.timetable.data.TimetableRepository;
import edu.nustti.timetable.edu.JwglClient;
import edu.nustti.timetable.model.TimetableResult;

/**
 * 登录页：填写教务系统账号 → 手机端直连官网 jwgl.nustti.edu.cn 登录并抓取课表。
 *
 * <p>南京理工大学泰州科技学院教务系统登录页无验证码，故此处只有学号与密码两个输入项。</p>
 */
public class LoginActivity extends AppCompatActivity {

    private TextInputEditText etStudentId;
    private TextInputEditText etPassword;
    private TextView tvStatus;
    private ProgressBar progress;

    private SessionStore store;
    private TimetableRepository repository;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        store = new SessionStore(this);
        repository = new TimetableRepository(this);

        etStudentId = findViewById(R.id.etStudentId);
        etPassword = findViewById(R.id.etPassword);
        tvStatus = findViewById(R.id.tvStatus);
        progress = findViewById(R.id.progress);

        Button btnLogin = findViewById(R.id.btnLogin);
        Button btnDemo = findViewById(R.id.btnDemo);

        etStudentId.setText(store.getStudentId());
        etPassword.setText(store.getPassword());

        btnLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doLogin();
            }
        });
        btnDemo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doDemo();
            }
        });

        TimetableResult cached = repository.cached();
        if (cached != null) {
            setStatus("本地已有 " + cached.courses.size() + " 门课的缓存，可直接登录或加载演示数据");
        }
    }

    // ------------------------------------------------------------------ //

    private void doLogin() {
        final String studentId = text(etStudentId);
        final String password = text(etPassword);
        if (studentId.isEmpty() && password.isEmpty()) {
            setStatus("请填写学号与密码");
            return;
        }
        if (studentId.isEmpty()) {
            setStatus("请填写学号");
            return;
        }
        if (password.isEmpty()) {
            setStatus("请填写密码");
            return;
        }
        store.setBaseUrl(JwglClient.DEFAULT_BASE);
        store.setStudentId(studentId);
        store.setPassword(password);

        setBusy(true, "正在登录教务系统官网 ...");
        Async.run(new Async.Task<TimetableResult>() {
            @Override
            public TimetableResult run() throws Exception {
                repository.loginJwgl();
                return repository.fetchFromJwgl();
            }
        }, new Async.Done<TimetableResult>() {
            @Override
            public void onResult(TimetableResult result) {
                store.setPassword(password);
                setBusy(false, "登录成功，已获取 " + result.courses.size() + " 门课");
                openMain();
            }
        }, new Async.Fail() {
            @Override
            public void onError(Exception e) {
                String msg = message(e);
                if (msg.startsWith("请")) {
                    setBusy(false, msg);
                } else {
                    setBusy(false, "登录失败：" + msg);
                }
            }
        });
    }

    private void doDemo() {
        setBusy(true, "正在生成本地演示课表 ...");
        Async.run(new Async.Task<TimetableResult>() {
            @Override
            public TimetableResult run() throws Exception {
                return repository.fetchDemo();
            }
        }, new Async.Done<TimetableResult>() {
            @Override
            public void onResult(TimetableResult result) {
                setBusy(false, "演示课表已加载：" + result.courses.size() + " 门课");
                openMain();
            }
        }, new Async.Fail() {
            @Override
            public void onError(Exception e) {
                setBusy(false, "演示课表加载失败：" + message(e));
            }
        });
    }

    private void openMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    // ------------------------------------------------------------------ //

    private void setBusy(boolean busy, String status) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        findViewById(R.id.btnLogin).setEnabled(!busy);
        findViewById(R.id.btnDemo).setEnabled(!busy);
        setStatus(status);
    }

    private void setStatus(String text) {
        tvStatus.setText(text == null ? "" : text);
    }

    private String text(TextInputEditText editText) {
        return editText.getText() == null ? "" : editText.getText().toString().trim();
    }

    private static String message(Exception e) {
        String msg = e == null ? null : e.getMessage();
        return TextUtils.isEmpty(msg) ? e == null ? "未知错误" : e.getClass().getSimpleName() : msg;
    }
}
