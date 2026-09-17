# -*- coding: utf-8 -*-
"""Kivy 界面：标题栏 / 整周课表 / 今日视图 / 紧凑周视图。

布局方式说明：课表网格用"手工定位"（FloatLayout + relayout）而非 GridLayout，
原因是课程会跨 2~4 个小节合并成一格，且同一格可能并排两门课，
手工定位能精确控制合并高度与并排宽度，窗口尺寸变化时整块重排。
"""

from __future__ import annotations

from datetime import date
from typing import Any, Dict, List, Optional

from kivy.graphics import Color, Rectangle, RoundedRectangle
from kivy.metrics import dp
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.floatlayout import FloatLayout
from kivy.uix.label import Label
from kivy.uix.scrollview import ScrollView
from kivy.uix.widget import Widget

import tt_model
from tt_theme import THEME, WEEKDAY_CN, course_palette, font_name, mix, rgba


class Ctx:
    """界面上下文：配置 + 派生值 + 字号/字体换算（等价桌面版的 fnt()/tcol()）。"""

    def __init__(self, cfg: Dict[str, Any], courses: List[Dict[str, Any]], version: str = ""):
        self.cfg = cfg
        self.courses = courses
        self.version = version
        self.scale = float(cfg.get("font_scale", 1.0) or 1.0)
        self.ink = str(cfg.get("font_color") or "").strip()
        self.font = font_name()
        self.week = tt_model.current_week(cfg)
        self.term = str(cfg.get("term") or "")
        self._color_idx: Dict[str, int] = {}

    def color_index(self, name: str) -> int:
        """课程名 → 稳定的色板下标（同一课程在周视图/今日视图颜色一致）。"""
        key = str(name or "")
        if key not in self._color_idx:
            self._color_idx[key] = len(self._color_idx)
        return self._color_idx[key]

    def fs(self, base: float) -> float:
        return max(8.0, base * self.scale)

    def text_color(self, key: str = "text") -> List[float]:
        """字体颜色只覆盖 text / sub / dim 三档（与桌面版口径一致）。"""
        if self.ink and key in ("text", "sub", "dim"):
            return rgba(self.ink)
        return rgba(THEME.get(key, THEME["text"]))


def _label(text: str, ctx: Ctx, size: float = 12, color_key: str = "text",
           bold: bool = False, halign: str = "left", **kwargs) -> Label:
    lbl = Label(text=text, font_name=ctx.font, bold=bold,
                font_size=ctx.fs(size), color=ctx.text_color(color_key),
                halign=halign, valign="middle", **kwargs)
    lbl.bind(size=lambda w, *_: setattr(w, "text_size", (w.width, w.height)))
    return lbl


class CourseBox(BoxLayout):
    """单个课程色块（圆角 + 深色文字，颜色按课程名稳定分配）。"""

    def __init__(self, course: Dict[str, Any], color_index: int, ctx: Ctx,
                 compact: bool = False, **kwargs):
        super().__init__(orientation="vertical", padding=(dp(4), dp(2)),
                         spacing=dp(1), size_hint=(None, None), **kwargs)
        bg, fg = course_palette(color_index)
        self.course = course
        self.compact = compact
        self._bg, self._fg = bg, fg
        self.card_color = bg
        with self.canvas.before:
            self._color = Color(*rgba(bg))
            self._rect = RoundedRectangle(radius=[dp(6)], pos=self.pos, size=self.size)
        self.bind(pos=self._sync, size=self._fit)
        self._subs: List[Label] = []

        name = str(course.get("name") or "")
        title = _label(name, ctx, 12.5 if not compact else 11.5, bold=True, halign="center")
        title.color = rgba(fg)
        title.font_name = ctx.font
        self.add_widget(title)
        if not compact:
            room = str(course.get("room") or "")
            teacher = str(course.get("teacher") or "")
            sub = " ".join([x for x in (room, teacher) if x])
            if sub:
                line = _label(sub, ctx, 10.5, halign="center")
                line.color = rgba(fg, 0.85)
                self.add_widget(line)
                self._subs.append(line)
            raw = str(course.get("week_raw") or "")
            if raw:
                tiny = _label(raw, ctx, 9.5, halign="center")
                tiny.color = rgba(fg, 0.7)
                self.add_widget(tiny)
                self._subs.append(tiny)
        self._fit()

    def _fit(self, *_):
        """色块变窄/变矮时收敛内边距并隐藏次要文字，避免出现非正的子控件尺寸。"""
        wide = self.width >= dp(34)
        self.padding = (dp(4) if wide else dp(1), dp(2) if self.height >= dp(30) else dp(1))
        for lbl in self._subs:
            lbl.opacity = 1.0 if (wide and self.height >= dp(34)) else 0.0

    def _sync(self, *_):
        self._rect.pos = self.pos
        self._rect.size = self.size

    def describe(self) -> Dict[str, Any]:
        return {"name": self.course.get("name"), "teacher": self.course.get("teacher"),
                "room": self.course.get("room"), "color": self.card_color,
                "size": (round(self.width), round(self.height))}


class HeaderBar(BoxLayout):
    """标题栏：标题 + 三个视图按钮 + 同步/设置/退出。"""

    def __init__(self, ctx: Ctx, callbacks: Dict[str, Any], **kwargs):
        super().__init__(orientation="horizontal", size_hint_y=None, height=dp(46),
                         padding=(dp(10), dp(6)), spacing=dp(6), **kwargs)
        with self.canvas.before:
            self._color = Color(*rgba(THEME["panel"], 0.92))
            self._rect = Rectangle(pos=self.pos, size=self.size)
        self.bind(pos=self._sync, size=self._sync)

        title = f"南泰课表 v{ctx.version}" if ctx.version else "南泰课表"
        self.title_label = _label(title, ctx, 14, bold=True)
        self.title_label.size_hint_x = None
        self.title_label.width = dp(150)
        self.add_widget(self.title_label)
        self.add_widget(Widget(size_hint_x=1))

        self.view_buttons: Dict[str, Button] = {}
        for key, text in (("week", "整周"), ("today", "今日"), ("week_compact", "紧凑")):
            btn = self._mk_button(text, ctx, callbacks.get("switch_view"))
            btn.view_key = key
            self.view_buttons[key] = btn
            self.add_widget(btn)

        for text, cb in (("同步", callbacks.get("sync")),
                         ("设置", callbacks.get("settings")),
                         ("退出", callbacks.get("quit"))):
            if cb:
                self.add_widget(self._mk_button(text, ctx, cb))

    def _mk_button(self, text: str, ctx: Ctx, cb) -> Button:
        btn = Button(text=text, font_name=ctx.font, font_size=ctx.fs(12),
                     size_hint_x=None, width=dp(52), background_normal="",
                     background_color=rgba(THEME["panel3"]))
        btn.color = ctx.text_color("text")
        if cb:
            btn.bind(on_release=lambda *_: cb())
        return btn

    def mark_active(self, mode: str) -> None:
        for key, btn in self.view_buttons.items():
            btn.background_color = rgba(THEME["accent"] if key == mode else THEME["panel3"])

    def _sync(self, *_):
        self._rect.pos = self.pos
        self._rect.size = self.size


class WeekView(FloatLayout):
    """整周课表网格：左侧节次轴 + 7 列星期；支持合并小节与同格并排。"""

    def __init__(self, ctx: Ctx, compact: bool = False, week: Optional[int] = None, **kwargs):
        super().__init__(**kwargs)
        self.ctx = ctx
        self.compact = compact
        self.week = int(week or ctx.week)
        self.boxes: List[CourseBox] = []
        self._axis: List[Label] = []
        self._heads: List[Label] = []
        self.grid = tt_model.build_grid(ctx.courses, self.week)
        self.grid["today"] = (tt_model.current_week(ctx.cfg) == self.week and
                              date.today().isoweekday() or 0)
        self._build()
        self.bind(pos=self.relayout, size=self.relayout)

    # -- 构建 ---------------------------------------------------------- #
    def _build(self) -> None:
        ctx = self.ctx
        self.axis_w = 0 if self.compact else dp(44)
        self.head_h = dp(22) if self.compact else dp(26)

        if not self.compact:
            corner = _label("节次", ctx, 10, "sub", halign="center", size_hint=(None, None))
            self.add_widget(corner)
            self._axis.append(corner)
        for i, cn in enumerate(WEEKDAY_CN):
            text = cn if self.compact else f"周{cn}"
            head = _label(text, ctx, 11 if self.compact else 12,
                          "accent" if i + 1 == self.grid.get("today") else "sub",
                          bold=True, halign="center", size_hint=(None, None))
            self.add_widget(head)
            self._heads.append(head)

        times = tt_model.session_times(ctx.cfg)
        for idx, pair in enumerate(times, start=1):
            axis = _label(f"{idx}\n{pair[0]}" if not self.compact else str(idx),
                          ctx, 9 if not self.compact else 8.5, "dim", halign="center",
                          size_hint=(None, None))
            self.add_widget(axis)
            self._axis.append(axis)

        for day in self.grid["days"]:
            for block in day["blocks"]:
                box = CourseBox(block["course"],
                                ctx.color_index(block["course"].get("name")),
                                ctx, compact=self.compact)
                box.lane, box.lanes = block["lane"], block["lanes"]
                box.weekday, box.start, box.end = day["weekday"], block["start"], block["end"]
                self.add_widget(box)
                self.boxes.append(box)
        self.relayout()

    # -- 排布 ---------------------------------------------------------- #
    def relayout(self, *_):
        if not self.children:
            return
        w, h = self.width, self.height
        if w <= 1 or h <= 1:
            return
        axis_w, head_h = self.axis_w, self.head_h
        col_w = max(dp(24), (w - axis_w) / 7.0)
        row_h = max(dp(18), (h - head_h) / float(tt_model.SLOT_COUNT))

        if not self.compact:
            self._axis[0].pos = (0, h - head_h)
            self._axis[0].size = (axis_w, head_h)
        for i, head in enumerate(self._heads):
            head.pos = (axis_w + i * col_w, h - head_h)
            head.size = (col_w, head_h)
        for i in range(tt_model.SLOT_COUNT):
            axis = self._axis[i + (0 if self.compact else 1)]
            top = h - head_h - i * row_h
            axis.pos = (0, top - row_h)
            axis.size = (axis_w, row_h)

        for box in self.boxes:
            lane_w = max(dp(14), col_w / float(box.lanes))
            x = axis_w + (box.weekday - 1) * col_w + box.lane * lane_w
            top = h - head_h - (box.start - 1) * row_h
            height = (box.end - box.start + 1) * row_h
            box.size = (max(dp(14), lane_w - dp(3)), max(dp(16), height - dp(3)))
            box.pos = (x + dp(1.5), top - height + dp(1.5))

    def describe(self) -> Dict[str, Any]:
        return {"compact": self.compact, "week": self.week,
                "blocks": len(self.boxes), "lanes": self.grid.get("lanes", 1),
                "courses": [b.describe() for b in self.boxes]}


class TodayView(BoxLayout):
    """今日视图：日期/周次摘要 + 今日课程卡片列表。"""

    def __init__(self, ctx: Ctx, **kwargs):
        super().__init__(orientation="vertical", padding=(dp(10), dp(8)), spacing=dp(6),
                         **kwargs)
        self.ctx = ctx
        self.info = tt_model.today_courses(ctx.courses, ctx.cfg)
        self.cards: List[CourseBox] = []
        self._build()

    def _build(self) -> None:
        ctx, info = self.ctx, self.info
        date = info["date"]
        summary = (f"{date.year}-{date.month:02d}-{date.day:02d} 星期{WEEKDAY_CN[info['weekday'] - 1]}   "
                   f"第 {info['week']} 周 / 共 {tt_model.total_weeks(ctx.cfg)} 周")
        head = _label(summary, ctx, 12, "sub")
        head.size_hint_y = None
        head.height = dp(22)
        self.add_widget(head)

        if info["current"]:
            cur = _label(f"当前第 {info['current']} 小节", ctx, 11.5, "ok")
        else:
            nxt = tt_model.next_session(ctx.cfg)
            cur = _label("当前无课" + (f"，下一节为第 {nxt} 小节" if nxt else "，今日课程已结束"),
                         ctx, 11.5, "warn")
        cur.size_hint_y = None
        cur.height = dp(20)
        self.add_widget(cur)

        if not info["items"]:
            empty = _label("今天没有课，休息一下 ~", ctx, 13, "dim", halign="center")
            self.add_widget(empty)
            return

        scroll = ScrollView(do_scroll_x=False)
        inner = BoxLayout(orientation="vertical", size_hint_y=None, spacing=dp(6),
                          padding=(0, dp(4)))
        inner.bind(minimum_height=inner.setter("height"))
        for item in info["items"]:
            card = BoxLayout(orientation="horizontal", size_hint_y=None, height=dp(58),
                             spacing=dp(8), padding=(dp(10), dp(6)))
            bg, fg = course_palette(ctx.color_index(item["course"].get("name")))
            with card.canvas.before:
                Color(*rgba(bg))
                card_rect = RoundedRectangle(radius=[dp(8)], pos=card.pos, size=card.size)
            card.bind(pos=lambda w, *_: setattr(card_rect, "pos", w.pos),
                      size=lambda w, *_: setattr(card_rect, "size", w.size))

            left = BoxLayout(orientation="vertical")
            left.add_widget(_label(f"{item['begin']}-{item['finish']}", ctx, 11.5))
            left.add_widget(_label(f"第 {item['start']}-{item['end']} 小节", ctx, 10, halign="left"))
            for child in left.children:
                child.color = rgba(fg, 0.8)
            mid = BoxLayout(orientation="vertical")
            mid.add_widget(_label(str(item["course"].get("name") or ""), ctx, 13, bold=True))
            detail = " ".join(x for x in (item["course"].get("room"), item["course"].get("teacher")) if x)
            mid.add_widget(_label(detail, ctx, 10.5))
            for child in mid.children:
                child.color = rgba(fg, 0.85)
            right = _label(item["state"], ctx, 11, halign="center")
            right.color = rgba(fg)
            right.size_hint_x = None
            right.width = dp(52)

            card.add_widget(left)
            card.add_widget(mid)
            card.add_widget(right)
            inner.add_widget(card)
            self.cards.append(item["course"])
        scroll.add_widget(inner)
        self.add_widget(scroll)

    def describe(self) -> Dict[str, Any]:
        return {"week": self.info["week"], "weekday": self.info["weekday"],
                "items": len(self.info["items"]),
                "names": [c.get("name") for c in self.cards]}
