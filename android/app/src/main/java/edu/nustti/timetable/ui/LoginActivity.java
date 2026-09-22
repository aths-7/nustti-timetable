package edu.nustti.timetable.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;

import edu.nustti.timetable.R;
import edu.nustti.timetable.api.ApiClient;
import edu.nustti.timetable.api.Async;
import edu.nustti.timetable.data.SessionStore;
import edu.nustti.timetable.data.TimetableRepository;
import edu.nustti.timetable.model.TimetableResult;

/**
 * 登录页：填写服务端地址与教务系统账号，验证码由用户人工填写（程序不识别、不绕过）。
 */
public class LoginActivity extends AppCompatActivity {

    private TextInputEditText etBaseUrl;
    private TextInputEditText etStudentId;
    private TextInputEditText etPassword;
    private TextInputEditText etCaptcha;
    private ImageView ivCaptcha;
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

        etBaseUrl = findViewById(R.id.etBaseUrl);
        etStudentId = findViewById(R.id.etStudentId);
        etPassword = findViewById(R.id.etPassword);
        etCaptcha = findViewById(R.id.etCaptcha);
        ivCaptcha = findViewById(R.id.ivCaptcha);
        tvStatus = findViewById(R.id.tvStatus);
        progress = findViewById(R.id.progress);

        Button btnLogin = findViewById(R.id.btnLogin);
        Button btnDemo = findViewById(R.id.btnDemo);

        etBaseUrl.setText(store.getBaseUrl());
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
        ivCaptcha.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadCaptcha();
            }
        });

        TimetableResult cached = repository.cached();
        if (cached != null) {
            setStatus("本地已有 " + cached.courses.size() + " 门课的缓存，可直接登录或加载演示数据");
        }
        loadCaptcha();
    }

    // ------------------------------------------------------------------ //

    private void loadCaptcha() {
        final String base = ApiClient.normalizeBase(text(etBaseUrl));
        if (base.isEmpty()) {
            setStatus("请先填写服务端地址");
            return;
        }
        ivCaptcha.setImageDrawable(null);
        store.setBaseUrl(base);
        Async.run(new Async.Task<byte[]>() {
            @Override
            public byte[] run() throws Exception {
                return ApiClient.captcha(base);
            }
        }, new Async.Done<byte[]>() {
            @Override
            public void onResult(byte[] bytes) {
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap == null) {
                    setStatus("验证码图片解析失败，请点击图片重试");
                    return;
                }
                ivCaptcha.setImageBitmap(bitmap);
                setStatus("验证码已加载，请填写后点击登录");
            }
        }, new Async.Fail() {
            @Override
            public void onError(Exception e) {
                setStatus("验证码获取失败：" + message(e));
            }
        });
    }

    private void doLogin() {
        final String base = ApiClient.normalizeBase(text(etBaseUrl));
        final String studentId = text(etStudentId);
        final String password = text(etPassword);
        final String captcha = text(etCaptcha);

        if (base.isEmpty() || studentId.isEmpty() || password.isEmpty()) {
            setStatus("请填写服务端地址、学号与密码");
            return;
        }
        store.setBaseUrl(base);
        store.setStudentId(studentId);

        setBusy(true, "正在通过服务端登录教务系统...");
        Async.run(new Async.Task<ApiClient.Result>() {
            @Override
            public ApiClient.Result run() throws Exception {
                return ApiClient.login(base, studentId, password, captcha);
            }
        }, new Async.Done<ApiClient.Result>() {
            @Override
            public void onResult(ApiClient.Result result) {
                if (result.ok) {
                    store.setPassword(password);
                    setBusy(false, "登录成功，正在拉取课表...");
                    openMain(true);
                    return;
                }
                if (result.needCaptcha) {
                    setBusy(false, TextUtils.isEmpty(result.message) ? "请输入验证码后重试" : result.message);
                    etCaptcha.setText("");
                    loadCaptcha();
                    return;
                }
                setBusy(false, "登录失败：" + (TextUtils.isEmpty(result.message) ? "未知原因" : result.message));
                loadCaptcha();
            }
        }, new Async.Fail() {
            @Override
            public void onError(Exception e) {
                setBusy(false, "登录异常：" + message(e));
                loadCaptcha();
            }
        });
    }

    private void doDemo() {
        final String base = ApiClient.normalizeBase(text(etBaseUrl));
        if (base.isEmpty()) {
            setStatus("请先填写服务端地址");
            return;
        }
        store.setBaseUrl(base);
        setBusy(true, "正在从服务端加载演示课表...");
        Async.run(new Async.Task<TimetableResult>() {
            @Override
            public TimetableResult run() throws Exception {
                return repository.fetchDemo();
            }
        }, new Async.Done<TimetableResult>() {
            @Override
            public void onResult(TimetableResult result) {
                setBusy(false, "演示课表已加载：" + result.courses.size() + " 门课");
                openMain(false);
            }
        }, new Async.Fail() {
            @Override
            public void onError(Exception e) {
                setBusy(false, "演示课表加载失败：" + message(e));
            }
        });
    }

    private void openMain(boolean refreshFromServer) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_REFRESH, refreshFromServer);
        startActivity(intent);
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

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
