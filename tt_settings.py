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
from kivy.graphics import Color, Rectangle, RoundedRectangle
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.filechooser import FileChooserListView
from kivy.uix.floatlayout import FloatLayout
from kivy.uix.image import Image
from kivy.uix.label import Label
from kivy.uix.popup import Popup
from kivy.uix.scrollview import ScrollView
from kivy.uix.slider import Slider
from kivy.uix.spinner import Spinner
from kivy.uix.switch import Switch
from kivy.uix.textinput import TextInput
from kivy.uix.widget import Widget

import store
import tt_bg
import tt_model
import tt_theme
from tt_theme import (THEME, PRESET_FONT_COLORS, available_families, is_hex_color, rgba, u,
                      unit_scale)
from tt_views import Ctx, _label

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
        self.term_text = _input(self.cfg.get("term_start", ""), ctx)
        body.add_widget(_row("学期首日", ctx, self.term_text))
        body.add_widget(_hint("格式 YYYY-MM-DD，即第 1 周星期一", ctx))
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
                              font_name=ctx.font, font_size=ctx.fs(11.5),
                              size_hint_y=None, height=u(CTRL_H),
                              pos_hint={"center_y": 0.5},
                              background_normal="", background_color=rgba(THEME["panel3"]))
        self.family.color = ctx.text_color("text")
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
        body.add_widget(_section("背景图", ctx))
        self.bg_label = _label(self._bg_path or "（未设置，使用深色外观）", ctx, 10, "dim")
        self.bg_label.shorten = True
        self.bg_label.shorten_from = "left"
        self.bg_label.size_hint_y = None
        self.bg_label.height = u(24)
        row = BoxLayout(orientation="horizontal", size_hint_y=None, height=u(ROW_H),
                        spacing=u(6))
        row.add_widget(_label("图片", ctx, 11.5, "sub", halign="left"))
        row.add_widget(_button("选择", ctx, self._choose_bg, 60))
        row.add_widget(_button("清除", ctx, self._clear_bg, 60))
        body.add_widget(row)
        body.add_widget(self.bg_label)
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

    def _choose_bg(self) -> None:
        start = os.path.dirname(self._bg_path) or os.path.expanduser("~")
        popup = Popup(title="选择背景图片", size_hint=(0.92, 0.92), title_font=self.ctx.font)

        def picked(selection):
            if selection:
                self._bg_path = selection[0]
                self.bg_label.text = self._bg_path
            popup.dismiss()

        chooser = FileChooserListView(path=start if os.path.isdir(start) else os.path.expanduser("~"),
                                      filters=IMAGE_FILTERS)
        chooser.bind(on_submit=lambda _c, sel, *_: picked(sel))
        box = BoxLayout(orientation="vertical", spacing=u(8), padding=u(8))
        box.add_widget(chooser)
        row = BoxLayout(orientation="horizontal", size_hint_y=None, height=u(ROW_H),
                        spacing=u(8))
        row.add_widget(Widget())
        row.add_widget(_button("确定", self.ctx, lambda: picked(chooser.selection), 70))
        row.add_widget(_button("取消", self.ctx, popup.dismiss, 70))
        box.add_widget(row)
        popup.content = box
        popup.open()

    def _clear_bg(self) -> None:
        self._bg_path = ""
        self.bg_label.text = "（未设置，使用深色外观）"

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
        cfg["term_start"] = self.term_text.text.strip()
        cfg["total_weeks"] = int(self.weeks.value)
        cfg["font_family"] = self.family.text
        cfg["font_scale"] = tt_theme.clamp_scale(self.scale.value)
        ink = (self.color_text.text or "").strip()
        cfg["font_color"] = ink if is_hex_color(ink) else ""
        cfg["bg_image"] = self._bg_path
        cfg["bg_veil"] = int(self.veil.value)
        cfg["bg_blur"] = int(self.blur.value)
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
