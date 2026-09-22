package edu.nustti.timetable.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.google.android.material.textfield.TextInputEditText;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import edu.nustti.timetable.R;
import edu.nustti.timetable.api.ApiClient;
import edu.nustti.timetable.data.SessionStore;
import edu.nustti.timetable.model.TimetableResult;

/** 设置页：服务端地址、账号与密码、当前周次、学期切换、缓存信息与接口清单。 */
public class SettingsFragment extends Fragment implements MainActivity.DataListener {

    private TextInputEditText etBaseUrl;
    private TextInputEditText etStudentId;
    private EditText etWeek;
    private TextView tvWeekRange;
    private TextView tvCacheInfo;
    private Spinner spTerm;

    private TimetableResult data;
    private int week = 1;
    private boolean suppressTermCallback = true;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        final MainActivity main = (MainActivity) requireActivity();
        final SessionStore store = main.repository().store();

        etBaseUrl = view.findViewById(R.id.etBaseUrl);
        etStudentId = view.findViewById(R.id.etStudentId);
        etWeek = view.findViewById(R.id.etWeek);
        tvWeekRange = view.findViewById(R.id.tvWeekRange);
        tvCacheInfo = view.findViewById(R.id.tvCacheInfo);
        spTerm = view.findViewById(R.id.spTerm);

        etBaseUrl.setText(store.getBaseUrl());
        etStudentId.setText(store.getStudentId());
        etWeek.setText(String.valueOf(store.getCurrentWeek()));

        TextView tvApiList = view.findViewById(R.id.tvApiList);
        tvApiList.setText("GET  /api/health —— 健康检查\n"
                + "GET  /api/captcha —— 教务系统验证码图片\n"
                + "POST /api/login —— 登录教务系统（body: studentId/password/captcha）\n"
                + "GET  /api/timetable?term= —— 课表 JSON\n"
                + "GET  /api/demo/timetable —— 演示课表（免登录）\n"
                + "POST /api/logout —— 注销会话");

        Button btnSave = view.findViewById(R.id.btnSave);
        Button btnRefresh = view.findViewById(R.id.btnRefresh);
        Button btnDemo = view.findViewById(R.id.btnDemo);
        Button btnLogout = view.findViewById(R.id.btnLogout);

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String base = ApiClient.normalizeBase(text(etBaseUrl));
                if (base.isEmpty()) {
                    toast("服务端地址不能为空");
                    return;
                }
                store.setBaseUrl(base);
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
                main.setWeek(value);
                toast("设置已保存");
            }
        });

        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                main.refresh(false);
            }
        });
        btnDemo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                main.refresh(true);
            }
        });
        btnLogout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                main.confirmLogout();
            }
        });

        spTerm.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressTermCallback || data == null || data.terms == null
                        || position >= data.terms.size()) {
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
    public void onStart() {
        super.onStart();
        ((MainActivity) requireActivity()).requestData(this);
    }

    @Override
    public void onTimetable(TimetableResult data, int week) {
        this.data = data;
        this.week = week;

        MainActivity main = (MainActivity) requireActivity();
        String base = main.repository().store().getBaseUrl();
        if (etBaseUrl.getText() == null || etBaseUrl.getText().toString().trim().isEmpty()) {
            etBaseUrl.setText(base);
        }
        if (etWeek.getText() == null || etWeek.getText().toString().trim().isEmpty()) {
            etWeek.setText(String.valueOf(week));
        }

        int max = data == null ? 20 : data.maxWeek();
        tvWeekRange.setText("可填 1 - " + max + "（当前第 " + week + " 周）");

        fillTerms(data);
        updateCacheInfo();
    }

    private void fillTerms(TimetableResult result) {
        List<String> labels = new ArrayList<>();
        int selected = 0;
        String currentTerm = ((MainActivity) requireActivity()).repository().store().getTerm();
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
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spTerm.setAdapter(adapter);
        spTerm.setSelection(Math.min(selected, labels.size() - 1));
        suppressTermCallback = false;
    }

    private void updateCacheInfo() {
        MainActivity main = (MainActivity) requireActivity();
        SessionStore store = main.repository().store();
        long at = store.cacheTime();
        String time = at <= 0 ? "无" : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date(at));
        String from = store.cacheFrom();
        String fromText = "demo".equals(from) ? "演示数据" : ("server".equals(from) ? "教务系统" : "未知");
        int count = data == null ? 0 : data.courses.size();
        int max = data == null ? 0 : data.maxWeek();
        String term = store.getTermLabel().isEmpty() ? store.getTerm() : store.getTermLabel();
        tvCacheInfo.setText("来源：" + fromText + "\n"
                + "缓存时间：" + time + "\n"
                + "课程数：" + count + " 门\n"
                + "周次范围：1 - " + max + " 周\n"
                + "当前学期：" + (term.isEmpty() ? "（未选择）" : term) + "\n"
                + "服务端登录态：" + (ApiClient.hasSession() ? "已建立" : "未建立"));
    }

    private String text(EditText editText) {
        return editText.getText() == null ? "" : editText.getText().toString().trim();
    }

    private void toast(String message) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
    }
}
