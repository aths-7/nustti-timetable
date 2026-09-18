# -*- coding: utf-8 -*-
"""设置面板（账号 / 课表 / 字体 / 背景）+ 验证码弹窗 + 背景图选择器。

字段名与桌面版 config.json 完全一致，保存后桌面版与手机版读同一份配置。
排版用 Kivy 原生控件手工拼装，不依赖 KV 文件，便于打包与自检。
"""

from __future__ import annotations

import base64
import os
import threading
from typing import Any, Callable, Dict, List, Optional

from kivy.clock import Clock
from kivy.core.window import Window
from kivy.graphics import Color, Rectangle, RoundedRectangle
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.dropdown import DropDown
from kivy.uix.floatlayout import FloatLayout
from kivy.uix.image import Image
from kivy.uix.label import Label
from kivy.uix.popup import Popup
from kivy.uix.scrollview import ScrollView
from kivy.uix.slider import Slider
from kivy.uix.spinner import Spinner, SpinnerOption
from kivy.uix.switch import Switch
from kivy.uix.textinput import TextInput
from kivy.uix.widget import Widget

import store
import tt_bg
import tt_bgpick
import tt_datepick
import tt_model
import tt_theme
from tt_theme import (THEME, PRESET_FONT_COLORS, available_families, is_hex_color, rgba, u,
                      unit_scale)
from tt_views import Ctx, _label

# 仅作历史保留：真正的图片后缀白名单在 tt_bgpick.IMAGE_EXTS（选择器统一走那边）
IMAGE_FILTERS = ["*.jpg", "*.jpeg", "*.png", "*.webp", "*.bmp", "*.gif"]

# --------------------------------------------------------------------------- #
# 尺寸规范：一律走 u()（按屏宽等比缩放，与设备像素密度无关）
#
# 这里原先清一色用 dp() 写死（行高 34dp、分组标题 26dp、按钮宽 74dp…）。dp 随设备
# 像素密度缩放，在 1080 及以上的高分屏上实际占屏比例明显偏小 —— 设置页 / 我的页
# 各功能项上下挤成一坨、输入框与按钮几乎贴住，正是本版要修的问题。
# --------------------------------------------------------------------------- #
ROW_H = 42.0          # 普通设置行（标签 + 输入框/开关/按钮）占高
ROW_GAP = 9.0         # 行与行之间的上下间距
SEC_H = 40.0          # 分组标题占高（文字贴底，把上下两组明显隔开）
LABEL_W = 92.0        # 左侧标签列宽
CTRL_H = 38.0         # 输入框 / 下拉框 / 按钮高度
HINT_H = 20.0         # 小字说明占高（固定高度，避免挤掉行距）

# 字体族下拉列表（P2：原 v1.0.2 用 Kivy 默认 SpinnerOption，尺寸跟着设备 dp 走，
# 在高分屏手机上选项文字挤成一团、几乎看不清）。这里把列表项字号/行高都纳入 u() 体系。
FAMILY_OPT_FS = 14.0  # 下拉选项字号（设计单位，约为主控件 11.5 的 1.2 倍）
FAMILY_OPT_H = 46.0   # 下拉选项行高（≥ 字号的 3 倍，避免多行重叠）
FAMILY_DD_MAX = 340.0  # 下拉列表最大高度（超出即可滚动，不遮住整屏）


class _FontDropDown(DropDown):
    """字体族下拉容器：浅色底 + 限高可滚动（原来顶满整屏、把设置页压成一片深色）。"""

    def __init__(self, **kwargs):
        kwargs.setdefault("max_height", u(FAMILY_DD_MAX))
        super().__init__(**kwargs)

    def open(self, widget):
        """展开后补一次重算。

        Kivy 的 ``_reposition`` 只按"展开这一刻"的控件尺寸算宽度，若设置页刚好还没走完
        布局（控件还是默认 100px），下拉宽度就会被算成 0——整排选项在屏幕上等于空白。
        真机上手指点开时布局通常已就位，这里仍补一次下一帧的重算兜底。
        """
        super().open(widget)
        Clock.schedule_once(self._reposition_later, 0)

    def _reposition_later(self, *_largs) -> None:
        try:
            self._reposition()
        except Exception:
            pass


def font_option_cls(ctx: Ctx) -> type:
    """生成绑定当前 ctx 的下拉项类。

    Kivy 只会用 ``cls(text=value)`` 构造选项（见 Spinner._update_dropdown），
    所以 ctx 必须闭包进来，不能当构造参数传。
    """

    class _FontOption(SpinnerOption):
        """字体族下拉列表项：大字号 + 固定行高 + 左对齐（P2：展开后小字看不清）。"""

        def __init__(self, **kwargs):
            kwargs.pop("font_size", None)
            super().__init__(**kwargs)
            self.font_name = ctx.font
            self.font_size = ctx.fs(FAMILY_OPT_FS)
            self.size_hint_y = None
            self.height = u(FAMILY_OPT_H)
            self.halign = "left"
            self.valign = "middle"
            self.padding = [u(10), 0]
            self.background_normal = ""
            self.background_down = ""
            self.background_color = rgba(THEME["panel3"])
            self.color = ctx.text_color("text")
            self.bind(size=lambda w, *_: setattr(
                w, "text_size", (max(u(20), w.width - u(16)), None)))

    return _FontOption


def _row(label_text: str, ctx: Ctx, widget: Widget, label_w: float = LABEL_W) -> BoxLayout:
    """一行设置项：左侧标签 + 右侧控件（高度固定，父级 minimum_height 才算得准）。"""
    row = BoxLayout(orientation="horizontal", size_hint_y=None, height=u(ROW_H),
                    spacing=u(8))
    lbl = _label(label_text, ctx, 11.5, "sub")
    lbl.size_hint_x = None
    lbl.size_hint_y = None
    lbl.width = u(label_w)
    lbl.height = u(ROW_H)
    row.add_widget(lbl)
    row.add_widget(widget)
    return row


def _section(title: str, ctx: Ctx) -> Label:
    """分组标题：占 SEC_H 高、文字贴底，把上下两组功能项明显分开。"""
    lbl = _label(title, ctx, 12.5, "accent", bold=True)
    lbl.size_hint_y = None
    lbl.height = u(SEC_H)
    lbl.valign = "bottom"
    return lbl


def _hint(text: str, ctx: Ctx, size: float = 10) -> Label:
    lbl = _label(text, ctx, size, "dim")
    lbl.size_hint_y = None
    lbl.height = u(HINT_H)
    return lbl


def _input(text: str, ctx: Ctx, password: bool = False) -> TextInput:
    return TextInput(text=text or "", password=password, multiline=False,
                     font_name=ctx.font, font_size=ctx.fs(12),
                     size_hint_y=None, height=u(CTRL_H), pos_hint={"center_y": 0.5},
                     background_color=rgba(THEME["panel3"]), foreground_color=rgba(THEME["text"]),
                     cursor_color=rgba(THEME["accent"]), padding=[u(8), u(8)])


def _button(text: str, ctx: Ctx, cb: Callable, width: float = 78) -> Button:
    btn = Button(text=text, font_name=ctx.font, font_size=ctx.fs(11.5),
                 size_hint_x=None, size_hint_y=None, width=u(width), height=u(CTRL_H),
                 pos_hint={"center_y": 0.5}, background_normal="",
                 background_color=rgba(THEME["panel3"]))
    btn.color = ctx.text_color("text")
    btn.bind(on_release=lambda *_: cb())
    return btn


class SettingsOverlay(FloatLayout):
    """全屏设置浮层；on_save(cfg) / on_close() 由主程序注入。

    注意：类名不能叫 SettingsPanel —— kivy.uix.settings 里已有同名类，且其 kv 规则
    从 style.kv 全局生效（规则里引用了 self.minimum_height），会与本类冲突并抛
    BuilderException。故统一加 TT 前缀避免与 Kivy 内置控件同名。
    """

    def __init__(self, app, on_save: Callable[[Dict[str, Any]], None],
                 on_close: Callable[[], None], on_sync: Callable[[], None], **kwargs):
        super().__init__(**kwargs)
        self.app = app
        self.cfg = dict(app.cfg)
        self.ctx = app.ctx
        self.on_save_cb = on_save
        self.on_close_cb = on_close
        self.on_sync_cb = on_sync
        with self.canvas.before:
            Color(0, 0, 0, 0.62)
            self._scrim = Rectangle(pos=self.pos, size=self.size)
        self.bind(pos=lambda *_: setattr(self._scrim, "pos", self.pos),
                  size=lambda *_: setattr(self._scrim, "size", self.size))
        self._ink = str(self.cfg.get("font_color") or "")
        self._bg_path = str(self.cfg.get("bg_image") or "")
        self.bg_picker = None       # 自研背景选择器实例（自检要读它的状态）
        self.bg_anchor = None       # 「自定义背景」一节的锚点（自检滚动截图用）
        self.date_picker = None     # 自研日期选择器实例（自检要读它的状态）
        self.family_options: List[Any] = []   # 字体族下拉项（自检量字号/行高）
        self._build()

    # ------------------------------------------------------------------ #
    def _build(self) -> None:
        ctx = self.ctx
        panel = BoxLayout(orientation="vertical", size_hint=(0.95, 0.95),
                          pos_hint={"center_x": 0.5, "center_y": 0.5},
                          padding=(u(12), u(12)), spacing=u(10))
        with panel.canvas.before:
            Color(*rgba(THEME["panel"], 0.98))
            rect = RoundedRectangle(radius=[u(12)], pos=panel.pos, size=panel.size)
        panel.bind(pos=lambda *_: setattr(rect, "pos", panel.pos),
                   size=lambda *_: setattr(rect, "size", panel.size))

        head = BoxLayout(orientation="horizontal", size_hint_y=None, height=u(34))
        head.add_widget(_label("设置", ctx, 14, bold=True))
        head.add_widget(_button("关闭", ctx, self.on_close_cb, 58))
        panel.add_widget(head)

        scroll = ScrollView(do_scroll_x=False)
        body = BoxLayout(orientation="vertical", size_hint_y=None, spacing=u(ROW_GAP))
        body.bind(minimum_height=body.setter("height"))

        # --- 账号 ---
        body.add_widget(_section("账号（教务系统）", ctx))
        self.id_text = _input(self.cfg.get("student_id", ""), ctx)
        body.add_widget(_row("学号", ctx, self.id_text))
        pwd = store.deobfuscate(str(self.cfg.get("password") or ""))
        self.pwd_text = _input(pwd, ctx, password=True)
        body.add_widget(_row("密码", ctx, self.pwd_text))
        self.remember = Switch(active=bool(self.cfg.get("remember_password", True)))
        body.add_widget(_row("记住密码", ctx, self.remember))

        # --- 课表 ---
        body.add_widget(_section("课表", ctx))
        # P3：学期首日原来只能手敲（截图 3）。现在输入框旁边给一个「选择」按钮，
        # 点开自研日历（tt_datepick）按格子点日期，回写右上角格式的文本便于查看与手改。
        self.term_text = _input(self.cfg.get("term_start", ""), ctx)
        self.term_text.size_hint_x = 1
        term_box = BoxLayout(orientation="horizontal", size_hint_y=None, height=u(CTRL_H),
                             pos_hint={"center_y": 0.5}, spacing=u(6))
        term_box.add_widget(self.term_text)
        self.term_btn = _button("选择日期", ctx, self._pick_term_start, 74)
        term_box.add_widget(self.term_btn)
        body.add_widget(_row("学期首日", ctx, term_box))
        body.add_widget(_hint("点「选择日期」按日历点选；也可直接输入 YYYY-MM-DD（第 1 周星期一）", ctx))
        self.weeks = self._slider_row(body, "总周数", ctx, 1, 30, 1,
                                      self.cfg.get("total_weeks", 20), "{:.0f} 周")
        self.term_label = _label(f"当前学期：{self.cfg.get('term') or '未设置'}   "
                                 f"第 {tt_model.current_week(self.cfg)} 周", ctx, 10.5, "dim")
        self.term_label.size_hint_y = None
        self.term_label.height = u(26)
        body.add_widget(self.term_label)

        # --- 字体 ---
        body.add_widget(_section("字体", ctx))
        families = available_families()
        current = str(self.cfg.get("font_family") or "")
        self.family = Spinner(text=current or families[0], values=families,
                              option_cls=font_option_cls(ctx), dropdown_cls=_FontDropDown,
                              font_name=ctx.font, font_size=ctx.fs(12),
                              size_hint_y=None, height=u(CTRL_H),
                              pos_hint={"center_y": 0.5},
                              background_normal="", background_color=rgba(THEME["panel3"]))
        self.family.color = ctx.text_color("text")
        # 自检要逐个量展开项的字号/行高（P2：展开后小字看不清）
        self.family_options = list(getattr(self.family._dropdown, "container", None).children
                                  if getattr(self.family, "_dropdown", None) is not None else [])
        body.add_widget(_row("字体族", ctx, self.family))
        self.scale = self._slider_row(body, "字号缩放", ctx, 0.8, 1.6, 0.05,
                                      self.cfg.get("font_scale", 1.0), "{:.2f} 倍")
        color_box = BoxLayout(orientation="horizontal", size_hint_y=None, height=u(CTRL_H),
                              spacing=u(6))
        for name, value in PRESET_FONT_COLORS:
            btn = Button(text="", size_hint_x=None, size_hint_y=None, width=u(28),
                         height=u(CTRL_H), pos_hint={"center_y": 0.5}, background_normal="",
                         background_color=rgba(value or THEME["text"]))
            btn.bind(on_release=lambda _b, v=value: self._pick_color(v))
            color_box.add_widget(btn)
        self.color_preview = _label("", ctx, 10, halign="center")
        self.color_preview.size_hint_y = None
        self.color_preview.height = u(CTRL_H)
        color_box.add_widget(self.color_preview)
        body.add_widget(_row("字体颜色", ctx, color_box))
        self.color_text = _input(self._ink, ctx)
        body.add_widget(_row("自定义色", ctx, self.color_text))
        self._pick_color(self._ink)

        # --- 背景 ---
        body.add_widget(_section("自定义背景", ctx))
        self.bg_label = _label(self._bg_path or "（未设置，使用默认浅灰底）", ctx, 10, "dim")
        self.bg_label.shorten = True
        self.bg_label.shorten_from = "left"
        self.bg_label.size_hint_y = None
        self.bg_label.height = u(24)
        row = BoxLayout(orientation="horizontal", size_hint_y=None, height=u(ROW_H),
                        spacing=u(6))
        row.add_widget(_label("图片", ctx, 11.5, "sub", halign="left"))
        row.add_widget(_button("从相册选择" if tt_bgpick.IS_ANDROID else "挑图",
                               ctx, self._pick_gallery, 84 if tt_bgpick.IS_ANDROID else 56))
        row.add_widget(_button("浏览", ctx, self._choose_bg, 56))
        row.add_widget(_button("清除", ctx, self._clear_bg, 56))
        body.add_widget(row)
        body.add_widget(self.bg_label)
        self.bg_anchor = row
        self.bg_scale = self._slider_row(body, "图片缩放", ctx,
                                        tt_bg.SCALE_MIN, tt_bg.SCALE_MAX, 1,
                                        self.cfg.get("bg_scale", 100), "{:.0f}%",
                                        on_change=self._preview_scale)
        self.bg_alpha = self._slider_row(body, "透明度", ctx,
                                        tt_bg.ALPHA_MIN, tt_bg.ALPHA_MAX, 1,
                                        self.cfg.get("bg_alpha", 100), "{:.0f}%",
                                        on_change=self._preview_alpha)
        body.add_widget(_hint("100% = 原图铺满（不变形）；往下调越来越透，露出底色", ctx))
        self.veil = self._slider_row(body, "蒙版强度", ctx, 0, 100, 1,
                                     self.cfg.get("bg_veil", 60), "{:.0f}",
                                     on_change=self._preview_veil)
        self.blur = self._slider_row(body, "模糊半径", ctx, 0, tt_bg.MAX_BLUR, 1,
                                     min(self.cfg.get("bg_blur", 8), tt_bg.MAX_BLUR), "{:.0f}",
                                     on_change=self._preview_blur)

        about = _label(f"南泰课表 · Kivy 版 v{self.app.version}   数据目录：{store.data_dir()}",
                       ctx, 9.5, "dim")
        about.size_hint_y = None
        about.height = u(30)
        body.add_widget(about)

        scroll.add_widget(body)
        panel.add_widget(scroll)
        self.scroll = scroll          # 自检要滚动到底部截图（验证下半屏各项间距）
        self.body = body              # 自检要量各行高/行距（1080+ 堆叠回归判据）

        foot = BoxLayout(orientation="horizontal", size_hint_y=None, height=u(46),
                         spacing=u(10))
        foot.add_widget(_button("同步课表", ctx, self.on_sync_cb, 88))
        foot.add_widget(Widget())
        foot.add_widget(_button("保存", ctx, self._save, 88))
        panel.add_widget(foot)
        self.add_widget(panel)

    def _slider_row(self, body: BoxLayout, title: str, ctx: Ctx, lo: float, hi: float,
                    step: float, value: Any, fmt: str, on_change=None) -> Slider:
        row = BoxLayout(orientation="horizontal", size_hint_y=None, height=u(ROW_H),
                        spacing=u(8))
        lbl = _label(title, ctx, 11.5, "sub")
        lbl.size_hint_x = None
        lbl.width = u(LABEL_W)
        slider = Slider(min=lo, max=hi, step=step, value=float(value or lo))
        val_lbl = _label(fmt.format(float(value or lo)), ctx, 10.5, "dim")
        val_lbl.size_hint_x = None
        val_lbl.width = u(60)
        slider.bind(value=lambda _s, v: (val_lbl.__setattr__("text", fmt.format(v)),
                                        on_change(v) if on_change else None))
        row.add_widget(lbl)
        row.add_widget(slider)
        row.add_widget(val_lbl)
        body.add_widget(row)
        return slider

    # ------------------------------------------------------------------ #
    def _pick_color(self, value: str) -> None:
        self._ink = value or ""
        self.color_text.text = self._ink
        self.color_preview.text = "跟随主题" if not self._ink else self._ink
        self.color_preview.color = rgba(self._ink) if self._ink else rgba(THEME["text"])

    # ------------------------------------------------------------------ #
    # P3：学期首日 —— 点选式日历（原来只能手敲键盘）
    # ------------------------------------------------------------------ #
    def _pick_term_start(self) -> None:
        """打开自研日历弹窗（tt_datepick），按格子点选学期首日。"""
        old = self.date_picker                     # 连点两次会叠出两层弹窗，旧的那层再也关不掉
        if old is not None:                        # → 开新的之前先把上一个收掉
            try:
                old.popup.dismiss(animation=False)
            except Exception:
                pass
        self.date_picker = tt_datepick.DatePickerDialog(
            self.ctx, value=self.term_text.text.strip(),
            title="选择学期首日（第 1 周星期一）", on_pick=self._term_start_picked)
        self.date_picker.open()

    def _term_start_picked(self, value: str) -> None:
        """选中日期回填输入框（统一成 YYYY-MM-DD），并顺手刷新「第 N 周」小字。"""
        text = tt_datepick.normalize_date(value) or str(value or "").strip()
        self.term_text.text = text
        cfg = dict(self.cfg)
        cfg["term_start"] = text
        self.term_label.text = (f"当前学期：{self.cfg.get('term') or '未设置'}   "
                                f"第 {tt_model.current_week(cfg)} 周")

    def _choose_bg(self, extra_dirs=None) -> None:
        """打开自研背景选择器（替代手机端必然空列表的 FileChooserListView）。"""
        self.bg_picker = tt_bgpick.BackgroundPickerDialog(
            self.ctx, current=self._bg_path, on_pick=self._apply_picked_bg,
            extra_dirs=extra_dirs)
        self.bg_picker.open()

    def scroll_to_bg(self) -> bool:
        """自检用：把设置页滚到「自定义背景」一节（截图留证）。"""
        scroll = getattr(self, "scroll", None)
        anchor = getattr(self, "bg_anchor", None)
        if scroll is None or anchor is None:
            return False
        try:
            scroll.scroll_to(anchor, padding=u(12), animate=False)
            return True
        except Exception:
            return False

    def _pick_gallery(self) -> None:
        """直接走系统相册（Android）；桌面端退化为打开选择器。"""
        if not tt_bgpick.IS_ANDROID:
            self._choose_bg()
            return
        if not tt_bgpick.pick_from_gallery(self._gallery_picked):
            self._choose_bg()

    def _gallery_picked(self, path: str, error: str) -> None:
        """相册回调（可能在非 UI 线程），切回主线程再更新界面。"""
        def apply(*_):
            if error or not path:
                return
            self._apply_picked_bg(path)

        Clock.schedule_once(apply, 0)

    def _apply_picked_bg(self, path: str) -> None:
        self._bg_path = str(path or "")
        self.bg_label.text = self._bg_path or "（未设置，使用默认浅灰底）"
        self.app.apply_bg_image(self._bg_path)      # 选完立即预览，不必先保存

    def _clear_bg(self) -> None:
        self._bg_path = ""
        self.bg_label.text = "（未设置，使用默认浅灰底）"
        self.app.apply_bg_image("")

    def _preview_scale(self, value: float) -> None:
        self.app.apply_bg_scale(int(value))

    def _preview_alpha(self, value: float) -> None:
        self.app.apply_bg_alpha(int(value))

    def _preview_veil(self, value: float) -> None:
        self.app.apply_veil(int(value))

    def _preview_blur(self, value: float) -> None:
        self.app.apply_blur(int(value))

    def _save(self) -> None:
        cfg = dict(self.cfg)
        cfg["student_id"] = self.id_text.text.strip()
        if self.remember.active:
            cfg["password"] = store.obfuscate(self.pwd_text.text)
        else:
            cfg["password"] = ""
        cfg["remember_password"] = bool(self.remember.active)
        cfg["term_start"] = (tt_datepick.normalize_date(self.term_text.text)
                             or self.term_text.text.strip())
        cfg["total_weeks"] = int(self.weeks.value)
        cfg["font_family"] = self.family.text
        cfg["font_scale"] = tt_theme.clamp_scale(self.scale.value)
        ink = (self.color_text.text or "").strip()
        cfg["font_color"] = ink if is_hex_color(ink) else ""
        cfg["bg_image"] = self._bg_path
        cfg["bg_veil"] = int(self.veil.value)
        cfg["bg_blur"] = int(self.blur.value)
        cfg["bg_scale"] = int(self.bg_scale.value)
        cfg["bg_alpha"] = int(self.bg_alpha.value)
        self.on_save_cb(cfg)


class CaptchaDialog:
    """验证码输入弹窗：同步线程里通过它向用户索取验证码。"""

    def __init__(self, image_bytes: bytes, ctx: Ctx, on_submit: Callable[[str], None]):
        path = os.path.join(store.data_dir(), "captcha.jpg")
        try:
            with open(path, "wb") as fh:
                fh.write(image_bytes)
        except OSError:
            path = ""
        box = BoxLayout(orientation="vertical", spacing=u(10), padding=u(10))
        box.add_widget(Image(source=path, nocache=True, size_hint_y=None, height=u(90)))
        entry = _input("", ctx)
        entry.hint_text = "请输入图片中的验证码"
        box.add_widget(entry)
        row = BoxLayout(orientation="horizontal", size_hint_y=None, height=u(ROW_H),
                        spacing=u(8))
        row.add_widget(Widget())
        cancel = _button("取消", ctx, lambda: self._finish(""), 70)
        ok = _button("确定", ctx, lambda: self._finish(entry.text), 70)
        row.add_widget(cancel)
        row.add_widget(ok)
        box.add_widget(row)
        self.popup = Popup(title="教务系统验证码", content=box, size_hint=(0.7, 0.42),
                           auto_dismiss=False, title_font=ctx.font)
        self.on_submit = on_submit
        entry.bind(on_text_validate=lambda *_: self._finish(entry.text))

    def open(self) -> None:
        self.popup.open()

    def _finish(self, code: str) -> None:
        try:
            self.popup.dismiss()
        except Exception:
            pass
        self.on_submit(code or "")
