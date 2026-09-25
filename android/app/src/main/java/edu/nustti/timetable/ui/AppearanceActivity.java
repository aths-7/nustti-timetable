package edu.nustti.timetable.ui;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import edu.nustti.timetable.R;
import edu.nustti.timetable.data.SessionStore;

/** 外观详情页：背景显示模式、字体颜色、课程字体、选择背景图片、恢复默认背景、课程块透明度。 */
public class AppearanceActivity extends AppCompatActivity {

    private SessionStore store;
    private TextView tvBgStatus;
    private Spinner spBgMode;
    private Spinner spFontFamily;
    private TextView tvBlockAlpha;
    private SeekBar sbBlockAlpha;
    private View vFontColorPreview;
    private boolean suppressBgMode = true;

    private final ActivityResultLauncher<String> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    handleBackgroundPicked(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_appearance);
        store = new SessionStore(this);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        tvBgStatus = findViewById(R.id.tvBgStatus);
        spBgMode = findViewById(R.id.spBgMode);
        spFontFamily = findViewById(R.id.spFontFamily);
        tvBlockAlpha = findViewById(R.id.tvBlockAlpha);
        sbBlockAlpha = findViewById(R.id.sbBlockAlpha);
        vFontColorPreview = findViewById(R.id.vFontColorPreview);

        updateBgStatus();
        setupBgMode();
        setupPickReset();
        setupFontColor();
        setupFontFamily();
        setupBlockAlpha();
    }

    /** 背景显示模式：等比例裁切 / 拉伸铺满。 */
    private void setupBgMode() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"等比例裁切（推荐）", "拉伸铺满"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spBgMode.setAdapter(adapter);
        spBgMode.setSelection(
                BackgroundManager.MODE_CROP.equals(BackgroundManager.scaleMode(this)) ? 0 : 1);
        suppressBgMode = false;
        spBgMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressBgMode) {
                    return;
                }
                String mode = position == 0 ? BackgroundManager.MODE_CROP : BackgroundManager.MODE_STRETCH;
                if (mode.equals(BackgroundManager.scaleMode(AppearanceActivity.this))) {
                    return;
                }
                BackgroundManager.setScaleMode(AppearanceActivity.this, mode);
                notifyBackgroundChangedIfAlive();
                updateBgStatus();
                toast("背景显示模式已切换");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    /** 选择背景图片 + 恢复默认背景。 */
    private void setupPickReset() {
        Button btnPick = findViewById(R.id.btnPickBackground);
        btnPick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickImageLauncher.launch("image/*");
            }
        });
        Button btnReset = findViewById(R.id.btnResetBackground);
        btnReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BackgroundManager.clear(AppearanceActivity.this);
                notifyBackgroundChangedIfAlive();
                updateBgStatus();
                toast("已恢复默认背景");
            }
        });
    }

    /** 字体颜色：点击行弹出调色盘（HSV 选择器），选择后即时刷新周/今日视图。 */
    private void setupFontColor() {
        View rowFontColor = findViewById(R.id.rowFontColor);
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
                                notifyBackgroundChangedIfAlive();
                                toast("字体颜色已更新");
                            }
                        });
            }
        });
    }

    /** 课程字体：默认 / 宋体 / 黑体 / 仿宋 / 楷体（持久化，即时刷新周/今日视图）。 */
    private void setupFontFamily() {
        final String[] fontKeys = {
                SessionStore.FONT_DEFAULT,
                SessionStore.FONT_SERIF,
                SessionStore.FONT_SANS,
                SessionStore.FONT_FANGSONG,
                SessionStore.FONT_KAITI
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"默认", "宋体", "黑体", "仿宋", "楷体"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spFontFamily.setAdapter(adapter);
        spFontFamily.setSelection(indexOfString(fontKeys, store.getCourseFont()));
        spFontFamily.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (fontKeys[position].equals(store.getCourseFont())) {
                    return;
                }
                store.setCourseFont(fontKeys[position]);
                notifyBackgroundChangedIfAlive();
                toast("课程字体已更新");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    /** 课程块透明度：实时持久化并刷新周/今日视图，实现拖动即预览。 */
    private void setupBlockAlpha() {
        sbBlockAlpha.setProgress(BackgroundManager.blockAlphaPercent(this));
        tvBlockAlpha.setText(BackgroundManager.blockAlphaPercent(this) + "%");
        sbBlockAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvBlockAlpha.setText(progress + "%");
                BackgroundManager.setBlockAlphaPercent(AppearanceActivity.this, progress);
                notifyBackgroundChangedIfAlive();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        TextView btnReset = findViewById(R.id.btnResetBlockAlpha);
        btnReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sbBlockAlpha.setProgress(BackgroundManager.DEFAULT_BLOCK_ALPHA_PERCENT);
                toast("课程块透明度已恢复默认（85%）");
            }
        });
    }

    /** SAF 选图结果：保存为背景并刷新课表页。 */
    private void handleBackgroundPicked(Uri uri) {
        boolean ok = BackgroundManager.save(this, uri);
        if (ok) {
            notifyBackgroundChangedIfAlive();
            updateBgStatus();
            toast("背景图片已应用");
        } else {
            toast("背景图片设置失败，请换一张图片重试");
        }
    }

    private void updateBgStatus() {
        boolean has = BackgroundManager.hasBackground(this);
        String mode = BackgroundManager.MODE_CROP.equals(BackgroundManager.scaleMode(this))
                ? "等比例裁切" : "拉伸铺满";
        tvBgStatus.setText(has ? "背景：已启用自定义背景（" + mode + "）" : "背景：默认（" + mode + "）");
    }

    /** 主界面仍在运行（由设置页进入本页时必然存在）则通知其刷新背景与主题色。 */
    private void notifyBackgroundChangedIfAlive() {
        MainActivity main = MainActivity.instance();
        if (main != null) {
            main.notifyBackgroundChanged();
        }
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

    /** 更新颜色预览块。 */
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

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(dp(20));
        root.setPadding(pad, pad, pad, pad);

        final View preview = new View(this);
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

        new AlertDialog.Builder(this)
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
        final TextView tv = new TextView(this);
        tv.setText(label + "：" + progress);
        tv.setTextColor(0xFF333333);
        tv.setTextSize(14);
        root.addView(tv);

        SeekBar sb = new SeekBar(this);
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

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
