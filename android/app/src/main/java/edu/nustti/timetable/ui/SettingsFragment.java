package edu.nustti.timetable.ui;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;

import com.google.android.material.textfield.TextInputEditText;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import edu.nustti.timetable.R;
import edu.nustti.timetable.api.Async;
import edu.nustti.timetable.data.SessionStore;
import edu.nustti.timetable.edu.JwglSession;
import edu.nustti.timetable.model.TimetableResult;

/** 设置页：账号、当前周次、学期切换、缓存信息与数据来源（课表直连教务系统官网）。 */
public class SettingsFragment extends Fragment implements MainActivity.DataListener {

    private TextInputEditText etStudentId;
    private EditText etWeek;
    private TextView tvWeekRange;
    private TextView tvCacheInfo;
    private TextView tvBgStatus;
    private Spinner spTerm;

    private TimetableResult data;
    private int week = 1;
    private boolean suppressTermCallback = true;

    private final ActivityResultLauncher<String> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    handleBackgroundPicked(uri);
                }
            });

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        final MainActivity main = (MainActivity) requireActivity();
        final SessionStore store = main.repository().store();

        etStudentId = view.findViewById(R.id.etStudentId);
        etWeek = view.findViewById(R.id.etWeek);
        tvWeekRange = view.findViewById(R.id.tvWeekRange);
        tvCacheInfo = view.findViewById(R.id.tvCacheInfo);
        tvBgStatus = view.findViewById(R.id.tvBgStatus);
        spTerm = view.findViewById(R.id.spTerm);

        TextView tvAboutVersion = view.findViewById(R.id.tvAboutVersion);
        tvAboutVersion.setText("v" + versionName());

        etStudentId.setText(store.getStudentId());
        etWeek.setText(String.valueOf(store.getCurrentWeek()));

        TextView tvApiList = view.findViewById(R.id.tvApiList);
        tvApiList.setText("数据来源：南京理工大学泰州科技学院教务系统官网\n"
                + "地址：https://jwgl.nustti.edu.cn/jsxsd/\n"
                + "登录：POST /jsxsd/xk/LoginToXk（学号 + 密码，无验证码）\n"
                + "课表：GET /jsxsd/xskb/xskb_list.do?xnxq01id=学期\n"
                + "说明：手机端直连官网，不经过任何中间服务端");

        Button btnSave = view.findViewById(R.id.btnSave);
        Button btnRefresh = view.findViewById(R.id.btnRefresh);
        Button btnDemo = view.findViewById(R.id.btnDemo);
        Button btnLogout = view.findViewById(R.id.btnLogout);

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

        Button btnPickBackground = view.findViewById(R.id.btnPickBackground);
        Button btnResetBackground = view.findViewById(R.id.btnResetBackground);
        btnPickBackground.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickImageLauncher.launch("image/*");
            }
        });
        btnResetBackground.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BackgroundManager.clear(requireContext());
                main.notifyBackgroundChanged();
                updateBgStatus();
                toast("已恢复默认背景");
            }
        });

        Spinner spBgMode = view.findViewById(R.id.spBgMode);
        ArrayAdapter<String> bgModeAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item,
                new String[]{"等比例裁切（推荐）", "拉伸铺满"});
        bgModeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spBgMode.setAdapter(bgModeAdapter);
        spBgMode.setSelection(
                BackgroundManager.MODE_CROP.equals(BackgroundManager.scaleMode(requireContext())) ? 0 : 1);
        spBgMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String mode = position == 0 ? BackgroundManager.MODE_CROP : BackgroundManager.MODE_STRETCH;
                if (mode.equals(BackgroundManager.scaleMode(requireContext()))) {
                    return;
                }
                BackgroundManager.setScaleMode(requireContext(), mode);
                main.notifyBackgroundChanged();
                updateBgStatus();
                toast("背景显示模式已切换");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        updateBgStatus();

        // 字体颜色：点击行弹出调色盘（HSV 选择器），选择后即时刷新周/今日视图
        View rowFontColor = view.findViewById(R.id.rowFontColor);
        final View vFontColorPreview = view.findViewById(R.id.vFontColorPreview);
        updateColorPreview(vFontColorPreview, store.getCourseTextColor());
        rowFontColor.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showColorPickerDialog("选择字体颜色", store.getCourseTextColor(),
                        new ColorPickedListener() {
                            @Override
                            public void onPicked(int color) {
                                store.setCourseTextColor(color);
                                updateColorPreview(vFontColorPreview, color);
                                main.notifyBackgroundChanged();
                                toast("字体颜色已更新");
                            }
                        });
            }
        });

        // 课程字体：默认 / 宋体 / 黑体 / 仿宋 / 楷体（持久化，即时刷新周/今日视图）
        Spinner spFontFamily = view.findViewById(R.id.spFontFamily);
        final String[] fontKeys = {
                SessionStore.FONT_DEFAULT,
                SessionStore.FONT_SERIF,
                SessionStore.FONT_SANS,
                SessionStore.FONT_FANGSONG,
                SessionStore.FONT_KAITI
        };
        ArrayAdapter<String> fontAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item,
                new String[]{"默认", "宋体", "黑体", "仿宋", "楷体"});
        fontAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spFontFamily.setAdapter(fontAdapter);
        spFontFamily.setSelection(indexOfString(fontKeys, store.getCourseFont()));
        spFontFamily.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (fontKeys[position].equals(store.getCourseFont())) {
                    return;
                }
                store.setCourseFont(fontKeys[position]);
                main.notifyBackgroundChanged();
                toast("课程字体已更新");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // 课程块透明度：实时持久化并刷新周/今日视图，实现拖动即预览
        SeekBar sbBlockAlpha = view.findViewById(R.id.sbBlockAlpha);
        final TextView tvBlockAlpha = view.findViewById(R.id.tvBlockAlpha);
        TextView btnResetBlockAlpha = view.findViewById(R.id.btnResetBlockAlpha);
        sbBlockAlpha.setProgress(BackgroundManager.blockAlphaPercent(requireContext()));
        tvBlockAlpha.setText(BackgroundManager.blockAlphaPercent(requireContext()) + "%");
        sbBlockAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvBlockAlpha.setText(progress + "%");
                BackgroundManager.setBlockAlphaPercent(requireContext(), progress);
                main.notifyBackgroundChanged();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        btnResetBlockAlpha.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sbBlockAlpha.setProgress(BackgroundManager.DEFAULT_BLOCK_ALPHA_PERCENT);
                toast("课程块透明度已恢复默认（85%）");
            }
        });

        // 关于与更新：次级菜单项，点击打开独立详情页（版本号、检查更新、开发者信息、开源仓库）
        View rowAbout = view.findViewById(R.id.rowAbout);
        rowAbout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(requireContext(), AboutActivity.class));
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
        if (etStudentId.getText() == null || etStudentId.getText().toString().trim().isEmpty()) {
            etStudentId.setText(main.repository().store().getStudentId());
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
        String fromText;
        if ("demo".equals(from)) {
            fromText = "演示数据";
        } else if ("jwgl".equals(from) || "server".equals(from)) {
            fromText = "教务系统官网";
        } else {
            fromText = "未知";
        }
        int count = data == null ? 0 : data.courses.size();
        int max = data == null ? 0 : data.maxWeek();
        String term = store.getTermLabel().isEmpty() ? store.getTerm() : store.getTermLabel();
        tvCacheInfo.setText("来源：" + fromText + "\n"
                + "缓存时间：" + time + "\n"
                + "课程数：" + count + " 门\n"
                + "周次范围：1 - " + max + " 周\n"
                + "当前学期：" + (term.isEmpty() ? "（未选择）" : term) + "\n"
                + "教务系统登录态：" + (JwglSession.ready() ? "已登录" : "未登录（刷新时自动重新登录）"));
    }

    /** SAF 选图结果：保存为背景并刷新课表页。 */
    private void handleBackgroundPicked(Uri uri) {
        boolean ok = BackgroundManager.save(requireContext(), uri);
        if (ok) {
            ((MainActivity) requireActivity()).notifyBackgroundChanged();
            updateBgStatus();
            toast("背景图片已应用");
        } else {
            toast("背景图片设置失败，请换一张图片重试");
        }
    }

    private void updateBgStatus() {
        boolean has = BackgroundManager.hasBackground(requireContext());
        String mode = BackgroundManager.MODE_CROP.equals(BackgroundManager.scaleMode(requireContext()))
                ? "等比例裁切" : "拉伸铺满";
        tvBgStatus.setText(has ? "背景：已启用自定义背景（" + mode + "）" : "背景：默认（" + mode + "）");
    }

    /** 返回 target 在 values 中的下标；未命中返回 0（首个选项）。 */
    private static int indexOfString(String[] values, String target) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(target)) {
                return i;
            }
        }
        return 0;
    }

    /** 更新设置页颜色预览块。 */
    private void updateColorPreview(View preview, int color) {
        if (preview.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) preview.getBackground()).setColor(color);
        }
    }

    /** 颜色选择回调。 */
    private interface ColorPickedListener {
        void onPicked(int color);
    }

    /** 调色盘对话框：HSV 三通道滑块 + 实时圆形预览，确定后回调。 */
    private void showColorPickerDialog(String title, final int initialColor,
                                       final ColorPickedListener listener) {
        final float[] hsv = new float[3];
        Color.colorToHSV(initialColor, hsv);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(dp(20));
        root.setPadding(pad, pad, pad, pad);

        final View preview = new View(requireContext());
        final GradientDrawable previewBg = new GradientDrawable();
        previewBg.setShape(GradientDrawable.OVAL);
        previewBg.setColor(initialColor);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(Math.round(dp(64)),
                Math.round(dp(64)));
        pLp.gravity = Gravity.CENTER_HORIZONTAL;
        preview.setLayoutParams(pLp);
        preview.setBackground(previewBg);
        root.addView(preview);

        addHsvRow(root, "色相", 360, Math.round(hsv[0]), hsv, 0, previewBg);
        addHsvRow(root, "饱和度", 100, Math.round(hsv[1] * 100f), hsv, 1, previewBg);
        addHsvRow(root, "亮度", 100, Math.round(hsv[2] * 100f), hsv, 2, previewBg);

        new AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setView(root)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        listener.onPicked(Color.HSVToColor(hsv));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 调色盘一行：标签 + SeekBar，拖动实时更新 HSV 与预览。 */
    private void addHsvRow(LinearLayout root, final String label, final int max,
                           int progress, final float[] hsv, final int slot,
                           final GradientDrawable previewBg) {
        final TextView tv = new TextView(requireContext());
        tv.setText(label + "：" + progress);
        tv.setTextColor(0xFF333333);
        tv.setTextSize(14);
        root.addView(tv);

        SeekBar sb = new SeekBar(requireContext());
        sb.setMax(max);
        sb.setProgress(progress);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                hsv[slot] = max == 360 ? progress : progress / 100f;
                previewBg.setColor(Color.HSVToColor(hsv));
                tv.setText(label + "：" + progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        root.addView(sb);
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private String text(EditText editText) {
        return editText.getText() == null ? "" : editText.getText().toString().trim();
    }

    private void toast(String message) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ //
    // 版本号：设置页次级菜单行与详情页共用
    // ------------------------------------------------------------------ //

    private String versionName() {
        try {
            PackageInfo info = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0);
            return info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }


}
