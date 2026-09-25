package edu.nustti.timetable.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

import edu.nustti.timetable.R;
import edu.nustti.timetable.data.SessionStore;
import edu.nustti.timetable.model.TimetableResult;

/** 教育信息详情页：学号 / 当前周次 / 学期 / 保存设置 / 刷新课表 / 演示数据 / 退出登录，返回可回设置页。 */
public class EducationActivity extends AppCompatActivity implements MainActivity.DataListener {

    private TextInputEditText etStudentId;
    private EditText etWeek;
    private TextView tvWeekRange;
    private Spinner spTerm;

    private TimetableResult data;
    private int week = 1;
    private boolean suppressTermCallback = true;
    private SessionStore store;
    private MainActivity main;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_education);

        store = new SessionStore(this);
        // 从设置页进入时主界面必然存活；为 null 时降级为仅持久化 + 提示返回设置页操作
        main = MainActivity.instance();

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        etStudentId = findViewById(R.id.etStudentId);
        etWeek = findViewById(R.id.etWeek);
        tvWeekRange = findViewById(R.id.tvWeekRange);
        spTerm = findViewById(R.id.spTerm);

        etStudentId.setText(store.getStudentId());
        etWeek.setText(String.valueOf(store.getCurrentWeek()));

        Button btnSave = findViewById(R.id.btnSave);
        Button btnRefresh = findViewById(R.id.btnRefresh);
        Button btnDemo = findViewById(R.id.btnDemo);
        Button btnLogout = findViewById(R.id.btnLogout);

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                store.setStudentId(text(etStudentId));
                String weekText = text(etWeek);
                int value = week;
                try {
                    if (!weekText.isEmpty()) {
                        value = Integer.parseInt(weekText);
                    }
                } catch (NumberFormatException ignored) {
                    value = week;
                }
                int max = data == null ? 20 : data.maxWeek();
                value = Math.max(1, Math.min(max, value));
                store.setCurrentWeek(value);
                week = value;
                etWeek.setText(String.valueOf(value));
                if (main != null) {
                    main.setWeek(value);
                }
                toast("设置已保存");
            }
        });

        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (main != null) {
                    main.refresh(false);
                } else {
                    toast("请返回设置页后刷新课表");
                }
            }
        });
        btnDemo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (main != null) {
                    main.refresh(true);
                } else {
                    toast("请返回设置页后加载演示数据");
                }
            }
        });
        btnLogout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (main != null) {
                    // 先关闭详情页，再复用主界面的退出登录确认流程（确认后由主界面跳登录页）
                    finish();
                    main.confirmLogout();
                } else {
                    toast("请返回设置页后退出登录");
                }
            }
        });

        spTerm.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressTermCallback || data == null || data.terms == null
                        || position >= data.terms.size() || main == null) {
                    return;
                }
                TimetableResult.TermOption option = data.terms.get(position);
                if (option.value.equals(store.getTerm())) {
                    return;
                }
                store.setTerm(option.value, option.label);
                main.refresh(false);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 从主界面同步一次课表数据（学期列表 / 周次范围）
        if (main != null) {
            main.requestData(this);
        } else {
            fillTerms(null);
            tvWeekRange.setText("可填 1 - 20");
        }
    }

    @Override
    public void onTimetable(TimetableResult data, int week) {
        this.data = data;
        this.week = week;

        if (etStudentId.getText() == null || etStudentId.getText().toString().trim().isEmpty()) {
            etStudentId.setText(store.getStudentId());
        }
        if (etWeek.getText() == null || etWeek.getText().toString().trim().isEmpty()) {
            etWeek.setText(String.valueOf(week));
        }

        int max = data == null ? 20 : data.maxWeek();
        tvWeekRange.setText("可填 1 - " + max + "（当前第 " + week + " 周）");

        fillTerms(data);
    }

    /** 按当前学期填充学期下拉列表（与设置页原逻辑一致）。 */
    private void fillTerms(TimetableResult result) {
        List<String> labels = new ArrayList<>();
        int selected = 0;
        String currentTerm = store.getTerm();
        if (result != null && result.terms != null) {
            for (int i = 0; i < result.terms.size(); i++) {
                TimetableResult.TermOption option = result.terms.get(i);
                labels.add(option.toString());
                if (option.value.equals(currentTerm)) {
                    selected = i;
                }
            }
        }
        if (labels.isEmpty()) {
            labels.add(result == null || result.term == null || result.term.isEmpty()
                    ? "（未获取到学期列表）" : result.term);
        }
        suppressTermCallback = true;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                R.layout.spinner_item_white, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spTerm.setAdapter(adapter);
        spTerm.setSelection(Math.min(selected, labels.size() - 1));
        suppressTermCallback = false;
    }

    private String text(EditText editText) {
        return editText.getText() == null ? "" : editText.getText().toString().trim();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
