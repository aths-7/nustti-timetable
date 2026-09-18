# -*- coding: utf-8 -*-
"""日期选择器（自研日历弹窗，替代"学期首日只能手动敲键盘"）。

为什么要自己画日历：
    * Kivy 没有内置日期控件；``TextInput(input_type='date')`` 在 Android 上仍要
      主人一个个敲数字，正是主人截图里抱怨的"只能手动输入"。
    * 手机上的心智是"点一下日期"，跟挑图片、挑目录一样。
    * 纯 Kivy 控件拼装，桌面与 Android 行为一致，且能在命令行 --selfcheck 里
      用桌面窗口断言（原生 DatePickerDialog 无法在自检里判定）。

对外 API：
    * ``normalize_date(text)`` 把各种写法（2026/9/1、2026-09-01 09:00:00…）
      归一到 ``YYYY-MM-DD``；解析不了返回 ""。
    * ``month_matrix(year, month)`` 6×7 日号矩阵（0 = 补位格），周一开头。
    * ``DatePickerDialog`` 弹窗：年月加减、今天、月历格子、确定/取消。
      ``described()`` 暴露可断言的布局数值（格数、首格偏移、当前选中值）。
"""

from __future__ import annotations

import calendar
from datetime import date, datetime
from typing import Any, Callable, Dict, List, Optional

from kivy.graphics import Color, RoundedRectangle
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.gridlayout import GridLayout
from kivy.uix.popup import Popup
from kivy.uix.widget import Widget

from tt_theme import THEME, rgba, u
from tt_views import Ctx, _label

WEEK_CN = ["一", "二", "三", "四", "五", "六", "日"]
DATE_FMT = "%Y-%m-%d"
CELL_H = 34.0           # 日期格子高度（设计单位）
HEAD_H = 34.0           # 年月标题行高度
CELLS = 42              # 6 行 × 7 列


# --------------------------------------------------------------------------- #
# 纯逻辑（可单独自检）
# --------------------------------------------------------------------------- #
def parse_date(text: Any) -> Optional[date]:
    """宽松解析：2026-09-01 / 2026/9/1 / 2026.9.1 / 20260901 都认。"""
    raw = str(text or "").strip().replace("/", "-").replace(".", "-")
    if not raw:
        return None
    if " " in raw:
        raw = raw.split(" ", 1)[0]
    for fmt in (DATE_FMT, "%Y-%m-%d", "%Y%m%d", "%Y-%m"):
        try:
            return datetime.strptime(raw, fmt).date()
        except ValueError:
            continue
    return None


def normalize_date(text: Any) -> str:
    """归一到 YYYY-MM-DD；解析不了回 ""（调用方保持原值即可）。"""
    value = parse_date(text)
    return value.strftime(DATE_FMT) if value else ""


def month_offset(year: int, month: int) -> int:
    """该月 1 号在"周一开头"的日历里排第几格（0 = 周一）。"""
    return date(int(year), int(month), 1).weekday()


def month_days(year: int, month: int) -> int:
    return calendar.monthrange(int(year), int(month))[1]


def month_matrix(year: int, month: int) -> List[List[int]]:
    """6×7 日号矩阵（0 = 不属于本月的补位格）。"""
    offset = month_offset(year, month)
    cells: List[int] = [0] * offset + list(range(1, month_days(year, month) + 1))
    cells += [0] * (CELLS - len(cells))
    return [cells[i:i + 7] for i in range(0, CELLS, 7)]


def weekday_header() -> List[str]:
    return list(WEEK_CN)


# --------------------------------------------------------------------------- #
# 弹窗
# --------------------------------------------------------------------------- #
def _day_button(text: str, ctx: Ctx, width_hint: float = 1.0) -> Button:
    btn = Button(text=text, font_name=ctx.font, font_size=ctx.fs(12.5),
                 size_hint_y=None, height=u(CELL_H),
                 size_hint_x=width_hint, background_normal="",
                 background_color=rgba(THEME["panel3"]))
    btn.color = ctx.text_color("text")
    return btn


class DatePickerDialog:
    """点选式日期弹窗；``on_pick`` 回调收到 ``YYYY-MM-DD`` 字符串。"""

    def __init__(self, ctx: Ctx, value: str = "", title: str = "选择日期",
                 on_pick: Optional[Callable[[str], None]] = None,
                 on_close: Optional[Callable[[], None]] = None):
        self.ctx = ctx
        self.on_pick_cb = on_pick
        self.on_close_cb = on_close
        today = date.today()
        parsed = parse_date(value) or today
        self.year = int(parsed.year)
        self.month = int(parsed.month)
        self.day = int(parsed.day)
        self.value = self._format()
        self._day_buttons: Dict[int, Button] = {}
        self._cells = CELLS
        self._title_text = title
        self._build()

    # ------------------------------------------------------------------ #
    def _format(self) -> str:
        return f"{self.year:04d}-{self.month:02d}-{self.day:02d}"

    def _build(self) -> None:
        ctx = self.ctx
        self.popup = Popup(title=self._title_text, size_hint=(0.9, 0.78),
                           title_font=ctx.font, title_size=ctx.fs(13), separator_height=0)
        try:
            self.popup.background_color = (0, 0, 0, 0)
            self.popup.title_color = ctx.text_color("text")
        except Exception:
            pass

        box = BoxLayout(orientation="vertical", padding=u(12), spacing=u(8))
        with box.canvas.before:
            Color(*rgba(THEME["panel"], 0.99))
            panel = RoundedRectangle(radius=[u(12)], pos=box.pos, size=box.size)
        box.bind(pos=lambda *_: setattr(panel, "pos", box.pos),
                 size=lambda *_: setattr(panel, "size", box.size))
        self.box = box

        # 年月标题行：‹ 年 ›  ‹ 月 ›  + 今天
        head = BoxLayout(orientation="horizontal", size_hint_y=None, height=u(HEAD_H),
                         spacing=u(6))
        self.month_label = _label("", ctx, 13, bold=True, halign="center")
        for text, delta, tag in (("«", -12, "year-"), ("‹", -1, "month-"),
                                 ("›", 1, "month+"), ("»", 12, "year+")):
            btn = _day_button(text, ctx, 0.0)
            btn.width = u(38)
            btn.bind(on_release=lambda _b, d=delta: self.shift_month(d))
            head.add_widget(btn)
        head.add_widget(self.month_label)
        self.today_btn = _day_button("今天", ctx, 0.0)
        self.today_btn.width = u(52)
        self.today_btn.bind(on_release=lambda *_: self.go_today())
        head.add_widget(self.today_btn)
        box.add_widget(head)

        # 星期表头
        week = GridLayout(cols=7, size_hint_y=None, height=u(HEAD_H), spacing=u(2))
        for name in WEEK_CN:
            week.add_widget(_label(name, ctx, 11, "sub", halign="center"))
        box.add_widget(week)

        # 日期格子（6×7）
        self.grid = GridLayout(cols=7, size_hint_y=None, spacing=u(2))
        self.grid.bind(minimum_height=self.grid.setter("height"))
        box.add_widget(self.grid)

        self.hint = _label("", ctx, 10.5, "dim")
        self.hint.size_hint_y = None
        self.hint.height = u(24)
        box.add_widget(self.hint)

        foot = BoxLayout(orientation="horizontal", size_hint_y=None, height=u(42),
                         spacing=u(10))
        foot.add_widget(Widget())
        ok = _day_button("确定", ctx, 0.0)
        ok.width = u(72)
        ok.bind(on_release=lambda *_: self._confirm())
        cancel = _day_button("取消", ctx, 0.0)
        cancel.width = u(72)
        cancel.bind(on_release=lambda *_: self.dismiss())
        foot.add_widget(ok)
        foot.add_widget(cancel)
        box.add_widget(foot)

        self.popup.content = box
        self._rebuild_grid()

    # ------------------------------------------------------------------ #
    def _rebuild_grid(self) -> None:
        """按当前年月重画 6×7 格（本月日期可点，补位格禁用）。"""
        ctx = self.ctx
        self.grid.clear_widgets()
        self._day_buttons = {}
        rows = month_matrix(self.year, self.month)
        count = 0
        for row in rows:
            for day in row:
                if not day:
                    blank = _label("", ctx, 12, "dim", halign="center")
                    blank.size_hint_y = None
                    blank.height = u(CELL_H)
                    self.grid.add_widget(blank)
                    continue
                count += 1
                btn = _day_button(str(day), ctx)
                btn.bind(on_release=lambda _b, d=day: self.pick_day(d))
                self.grid.add_widget(btn)
                self._day_buttons[day] = btn
        self._cells = len(rows) * 7
        self._highlight()
        self.month_label.text = f"{self.year} 年 {self.month} 月"
        self.hint.text = ("点一个日期选中（当前 1 号是周%s），选完点「确定」"
                          % WEEK_CN[month_offset(self.year, self.month)])

    def _highlight(self) -> None:
        accent = rgba(THEME["accent"])
        normal = rgba(THEME["panel3"])
        on_accent = rgba(THEME["on_accent"])
        for day, btn in self._day_buttons.items():
            chosen = int(day) == int(self.day)
            btn.background_color = accent if chosen else normal
            btn.color = on_accent if chosen else self.ctx.text_color("text")

    # ------------------------------------------------------------------ #
    def set_month(self, year: int, month: int) -> None:
        """跳到指定年月（日号超出该月天数时收敛到最后一天）。"""
        self.year, self.month = int(year), int(month)
        while self.month > 12:
            self.year, self.month = self.year + 1, self.month - 12
        while self.month < 1:
            self.year, self.month = self.year - 1, self.month + 12
        self.day = max(1, min(int(self.day), month_days(self.year, self.month)))
        self.value = self._format()
        self._rebuild_grid()

    def shift_month(self, delta: int) -> None:
        total = (self.year * 12 + (self.month - 1)) + int(delta)
        self.set_month(total // 12, total % 12 + 1)

    def go_today(self) -> None:
        today = date.today()
        self.set_month(today.year, today.month)
        self.pick_day(today.day)

    def pick_day(self, day: int) -> str:
        """选中某一天（供点击与自检直接调用）。"""
        self.day = max(1, min(int(day), month_days(self.year, self.month)))
        self.value = self._format()
        self._highlight()
        return self.value

    # ------------------------------------------------------------------ #
    def open(self) -> None:
        self.popup.open()

    def dismiss(self) -> None:
        try:
            self.popup.dismiss()
        except Exception:
            pass
        if self.on_close_cb is not None:
            self.on_close_cb()

    def _confirm(self) -> None:
        self.value = self._format()
        if self.on_pick_cb is not None:
            self.on_pick_cb(self.value)
        self.dismiss()

    # ------------------------------------------------------------------ #
    def described(self) -> Dict[str, Any]:
        """自检用：格数 / 首格偏移 / 当前选中日期等可断言字段。"""
        return {"title": self._title_text, "year": self.year, "month": self.month,
                "day": self.day, "value": self.value, "cells": self._cells,
                "day_buttons": len(self._day_buttons), "offset": month_offset(self.year, self.month),
                "days_in_month": month_days(self.year, self.month),
                "header": list(WEEK_CN), "hint": self.hint.text,
                "popup_open": bool(self.popup._window is not None)}
