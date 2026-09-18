# -*- coding: utf-8 -*-
"""Kivy 界面：渐变顶栏 / 周次与日期表头 / 7 列课表网格 / 底部 Tab 栏 / 今日清单。

参照参考截图重做（v1.0.1）：
    顶部蓝色渐变栏（上一周 / 周次 + 日期区间 / 下一周 + 同步 / 设置）
      → 白色圆角面板（星期 + 日期表头，今天红色加粗）
      → 左侧节次轴 + 7 列课程网格（6 色课程卡，跨小节合并、同格并排）
      → 底部白色 Tab 栏（今日 / 课表 / 紧凑 / 我的，选中项蓝色高亮）

课表网格沿用"手工定位"（FloatLayout + relayout）而非 GridLayout：课程会跨 2~4 个小节
合并成一格，且同一格可能并排两门课，手工定位能精确控制合并高度与并排宽度。

所有尺寸一律走 u()（按屏宽等比缩放，设计稿宽 392），不再用 dp()：
dp() 随设备像素密度变化，不同手机上同一 dp 值占屏比例相差极大 —— 这正是
"手机端显示比例异常"的根因；u() 只与屏宽挂钩，任何机型上各区域占比都与设计稿一致。
"""

from __future__ import annotations

from datetime import date
from typing import Any, Dict, List, Optional, Tuple

from kivy.graphics import Color, Ellipse, Line, Rectangle, RoundedRectangle, Triangle
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.floatlayout import FloatLayout
from kivy.uix.label import Label
from kivy.uix.scrollview import ScrollView
from kivy.uix.widget import Widget

import tt_model
from tt_theme import (THEME, WEEKDAY_CN, course_palette, font_name, mix, readable_on_light,
                      rgba, u, unit_scale)

WHITE = "#FFFFFF"


class Ctx:
    """界面上下文：配置 + 派生值 + 字号/字体换算（等价桌面版的 fnt()/tcol()）。"""

    def __init__(self, cfg: Dict[str, Any], courses: List[Dict[str, Any]], version: str = ""):
        self.cfg = cfg
        self.courses = courses
        self.version = version
        self.scale = float(cfg.get("font_scale", 1.0) or 1.0)
        raw_ink = str(cfg.get("font_color") or "").strip()
        # 浅色外观下只接受较深的文字颜色：#ffe066 这类给深色底配的亮色在白色卡片上
        # 基本看不清，自动忽略并回落主题墨色（自检报告里会记录 ink_ignored）。
        self.ink = raw_ink if (raw_ink and readable_on_light(raw_ink)) else ""
        self.ink_ignored = bool(raw_ink and not self.ink)
        self.font = font_name()
        self.week = tt_model.current_week(cfg)
        self.term = str(cfg.get("term") or "")
        self.today = date.today().isoweekday()
        self._color_idx: Dict[str, int] = {}

    def color_index(self, name: str) -> int:
        """课程名 → 稳定的色板下标（同一课程在周视图/今日视图颜色一致）。"""
        key = str(name or "")
        if key not in self._color_idx:
            self._color_idx[key] = len(self._color_idx)
        return self._color_idx[key]

    def fs(self, base: float) -> float:
        """设计单位字号 → 像素（同样只跟屏宽挂钩）。"""
        return max(u(6), u(base) * self.scale)

    def text_color(self, key: str = "text") -> List[float]:
        """字体颜色只覆盖 text / sub / dim 三档（与桌面版口径一致）。"""
        if self.ink and key in ("text", "sub", "dim"):
            return rgba(self.ink)
        return rgba(THEME.get(key, THEME["text"]))

    def panel_alpha(self) -> float:
        """设了自定义背景图时面板略透明，让背景图仍能透出来。"""
        return 0.92 if str(self.cfg.get("bg_image") or "").strip() else 1.0


def _label(text: str, ctx: Ctx, size: float = 12, color_key: str = "text",
           bold: bool = False, halign: str = "left", color: Optional[str] = None,
           alpha: float = 1.0, **kwargs) -> Label:
    lbl = Label(text=text, font_name=ctx.font, bold=bold,
                font_size=ctx.fs(size),
                color=rgba(color, alpha) if color else ctx.text_color(color_key),
                halign=halign, valign="middle", **kwargs)
    lbl.bind(size=lambda w, *_: setattr(w, "text_size", (w.width, w.height)))
    return lbl


# --------------------------------------------------------------------------- #
# 基础块
# --------------------------------------------------------------------------- #
class Panel(BoxLayout):
    """白色圆角面板（顶部两角圆，底部与 Tab 栏衔接）。"""

    def __init__(self, color: str = THEME["panel"], alpha: float = 1.0,
                 radius: float = 0.0, **kwargs):
        super().__init__(**kwargs)
        r = u(radius)
        with self.canvas.before:
            self._color = Color(*rgba(color, alpha))
            self._rect = RoundedRectangle(radius=[r, r, 0, 0], pos=self.pos, size=self.size)
        self.bind(pos=self._sync, size=self._sync)

    def _sync(self, *_):
        self._rect.pos = self.pos
        self._rect.size = self.size


class GradientBar(BoxLayout):
    """横向渐变底（用若干竖条拼出渐变，避免依赖 Mesh / 纹理）。"""

    STRIPS = 72

    def __init__(self, colors: Tuple[str, str] = (THEME["accent"], THEME["accent2"]),
                 **kwargs):
        super().__init__(**kwargs)
        self.colors = colors
        self.bind(pos=self._paint, size=self._paint)
        self._paint()

    def _paint(self, *_):
        if self.width <= 1 or self.height <= 1:
            return
        c1, c2 = self.colors
        self.canvas.before.clear()
        with self.canvas.before:
            step = self.width / float(self.STRIPS)
            for i in range(self.STRIPS):
                Color(*rgba(mix(c1, c2, i / float(self.STRIPS - 1))))
                Rectangle(pos=(self.x + step * i, self.y), size=(step + 1.0, self.height))


class ArrowButton(Button):
    """自绘三角箭头（不依赖字体字形，任何机型都不会出现豆腐块）。"""

    def __init__(self, direction: str = "right", color: str = WHITE, box: Optional[float] = None,
                 alpha: float = 0.95, **kwargs):
        side = box if box else u(32)
        super().__init__(background_normal="", background_color=(0, 0, 0, 0),
                         size_hint=(None, None), size=(side, side), **kwargs)
        self.direction = direction
        with self.canvas.after:
            self._color = Color(*rgba(color, alpha))
            self._tri = Triangle(points=[0, 0, 0, 0, 0, 0])
        self.bind(pos=self._sync, size=self._sync)
        self._sync()

    def _sync(self, *_):
        w, h = self.size
        cx, cy = self.center
        s = min(w, h) * 0.24
        if self.direction == "left":
            self._tri.points = [cx + s * 0.6, cy + s, cx + s * 0.6, cy - s, cx - s * 0.7, cy]
        else:
            self._tri.points = [cx - s * 0.6, cy + s, cx - s * 0.6, cy - s, cx + s * 0.7, cy]


def _chip_button(text: str, ctx: Ctx, cb, width: float = 44.0, height: float = 24.0) -> Button:
    """顶栏右侧半透明白色胶囊按钮。"""
    btn = Button(text=text, font_name=ctx.font, font_size=ctx.fs(11),
                 size_hint=(None, None), size=(u(width), u(height)),
                 background_normal="", background_color=rgba(WHITE, 0.22))
    btn.color = rgba(WHITE)
    if cb:
        btn.bind(on_release=lambda *_: cb())
    return btn


class TopBar(GradientBar):
    """顶部：品牌 + 同步/设置；周次标题 + 上一周/下一周。"""

    def __init__(self, ctx: Ctx, callbacks: Dict[str, Any], week: int, **kwargs):
        super().__init__(orientation="vertical", size_hint_y=None, height=u(96),
                         padding=(u(12), u(8), u(12), u(8)), spacing=u(2), **kwargs)
        self.ctx = ctx
        week = int(week or ctx.week)

        row1 = BoxLayout(orientation="horizontal", size_hint_y=None, height=u(26), spacing=u(6))
        row1.add_widget(_label("南泰课表", ctx, 13.5, bold=True, color=WHITE))
        row1.add_widget(Widget())
        if week != ctx.week:
            row1.add_widget(_chip_button("回到本周", ctx, callbacks.get("this_week"), width=62))
        row1.add_widget(_chip_button("同步", ctx, callbacks.get("sync")))
        row1.add_widget(_chip_button("设置", ctx, callbacks.get("settings")))
        self.add_widget(row1)

        row2 = BoxLayout(orientation="horizontal", size_hint_y=1.0, spacing=u(4))
        prev_btn = ArrowButton("left", box=u(34))
        next_btn = ArrowButton("right", box=u(34))
        if callbacks.get("prev_week"):
            prev_btn.bind(on_release=lambda *_: callbacks["prev_week"]())
        if callbacks.get("next_week"):
            next_btn.bind(on_release=lambda *_: callbacks["next_week"]())
        row2.add_widget(prev_btn)

        center = BoxLayout(orientation="vertical", spacing=u(1))
        title = _label(f"第 {week} 周", ctx, 20, bold=True, halign="center", color=WHITE)
        center.add_widget(title)
        center.add_widget(_label(tt_model.week_range_text(ctx.cfg, week) or "未设置学期首日",
                                 ctx, 10.5, halign="center", color=WHITE, alpha=0.85))
        row2.add_widget(center)
        row2.add_widget(next_btn)
        self.add_widget(row2)


class WeekdaysHeader(Widget):
    """星期 + 日期表头：今天一列红色加粗，左上角显示月份。"""

    def __init__(self, ctx: Ctx, dates: List[Optional[date]], today_wd: int = 0,
                 compact: bool = False, **kwargs):
        super().__init__(size_hint_y=None, height=u(32) if compact else u(42), **kwargs)
        self.ctx = ctx
        self.dates = dates
        self.today_wd = int(today_wd or 0)
        self.compact = compact
        self.axis_w = 0.0 if compact else u(34)
        self.cells: List[BoxLayout] = []

        first = dates[0] if dates else None
        corner_text = f"{first.month}月" if first else "月"
        self.corner = _label(corner_text if not compact else "", ctx, 9.5, "dim",
                             halign="center", size_hint=(None, None))
        self.add_widget(self.corner)

        for i in range(7):
            hot = (i + 1 == self.today_wd)
            cell = BoxLayout(orientation="vertical", size_hint=(None, None), padding=(0, u(3)))
            day = _label(f"周{WEEKDAY_CN[i]}", ctx, 9.5 if not compact else 9,
                         "today" if hot else "sub", bold=hot, halign="center")
            day.size_hint_y = None
            day.height = u(15)
            num = _label(self._date_text(i), ctx, 13.5 if not compact else 12.5,
                         "today" if hot else "text", bold=True, halign="center")
            cell.add_widget(day)
            cell.add_widget(num)
            self.add_widget(cell)
            self.cells.append(cell)

        with self.canvas.before:
            Color(*rgba(THEME["border"]))
            self._line = Rectangle(pos=(self.x, self.y), size=(self.width, 1))
        self.bind(pos=self.relayout, size=self.relayout)
        self.relayout()

    def _date_text(self, i: int) -> str:
        d = self.dates[i] if i < len(self.dates) else None
        if not d:
            return "--"
        return f"{d.month}/{d.day}" if self.compact else f"{d.day}日"

    def relayout(self, *_):
        w, h = self.width, self.height
        if w <= 1 or h <= 1:
            return
        col_w = (w - self.axis_w) / 7.0
        # Kivy 不做父级平移：手工定位的子部件必须用绝对坐标（self.x / self.y 起算）
        self.corner.pos = (self.x, self.y)
        self.corner.size = (self.axis_w, h)
        for i, cell in enumerate(self.cells):
            cell.pos = (self.x + self.axis_w + i * col_w, self.y)
            cell.size = (col_w, h)
        self._line.pos = (self.x, self.y)
        self._line.size = (w, max(1.0, u(0.6)))


def _single_line(lbl: Label) -> Label:
    """卡片内文字统一"单行 + 超长省略号"：折行的文本纹理高度不可控，会溢出卡片。"""
    lbl.shorten = True
    lbl.bind(size=lambda w, *_: setattr(w, "text_size", (max(1.0, w.width), None)))
    return lbl


class CourseBox(BoxLayout):
    """课程色块：圆角 + 白字（颜色按课程名稳定分配，与今日视图共用色板）。"""

    def __init__(self, course: Dict[str, Any], color_index: int, ctx: Ctx,
                 compact: bool = False, **kwargs):
        super().__init__(orientation="vertical", padding=(u(3), u(2)),
                         spacing=u(1), size_hint=(None, None), **kwargs)
        bg, fg = course_palette(color_index)
        self.course = course
        self.ctx = ctx
        self.compact = compact
        self._bg, self._fg = bg, fg
        self.card_color = bg
        with self.canvas.before:
            self._color = Color(*rgba(bg))
            self._rect = RoundedRectangle(radius=[u(7)], pos=self.pos, size=self.size)
        self.bind(pos=self._sync, size=self._fit)
        self._subs: List[Label] = []

        title = _label(str(course.get("name") or ""), ctx,
                       11.5 if not compact else 10.5, bold=True, halign="center",
                       color=fg)
        self.title = _single_line(title)
        self.add_widget(title)
        if not compact:
            room = str(course.get("room") or "")
            if room:
                line = _single_line(_label(room, ctx, 9.5, halign="center", color=fg, alpha=0.92))
                self.add_widget(line)
                self._subs.append(line)
            teacher = str(course.get("teacher") or "")
            if teacher:
                line = _single_line(_label(teacher, ctx, 9, halign="center", color=fg, alpha=0.82))
                self.add_widget(line)
                self._subs.append(line)
        self._fit()

    def _fit(self, *_):
        """按卡片实际尺寸分配文字：字号随高度收缩，避免文本纹理溢出卡片边界。

        Kivy 的 Label 不做裁剪 —— 字号大于控件高度时文字会画到卡片外面（看起来像
        被上边缘切掉），所以这里显式给出每行高度，并把字号限制在 高度/1.35 以内。
        """
        title = getattr(self, "title", None)
        if title is None:
            return
        wide = self.width >= u(34)
        h = self.height
        pad_x = u(3) if wide else u(1)
        pad_y = u(2) if h >= u(30) else u(1)
        self.padding = (pad_x, pad_y)
        avail = max(u(8), h - 2.0 * pad_y)
        base = 10.5 if self.compact else 11.5

        show_sub = bool(self._subs) and wide and avail >= u(40)
        text_w = max(1.0, self.width - 2.0 * pad_x)
        if show_sub:
            title_h = avail * 0.40
            sub_h = avail * 0.30
            for lbl in self._subs:
                lbl.size_hint_y = None
                lbl.height = sub_h
                lbl.opacity = 1.0
                lbl.font_size = self._fit_font(lbl.text, text_w, sub_h, self.ctx.fs(9.5))
        else:
            title_h = avail
            for lbl in self._subs:
                lbl.size_hint_y = None
                lbl.height = 0.0
                lbl.opacity = 0.0
        title.size_hint_y = None
        title.height = title_h
        title.font_size = self._fit_font(title.text, text_w, title_h, self.ctx.fs(base))

    def _fit_font(self, text: str, avail_w: float, avail_h: float, base_px: float) -> float:
        """把字号收进"卡片内可用宽 × 行高"：先按行高限制，再按文字估算宽度限制。

        Label 不会自动裁剪，字号超出可用空间时文字会画到卡片外面；这里用中文按 1em、
        西文按 0.56em 估算所需水平空间，超长时压到最小字号（再由省略号兜底）。
        """
        floor = u(7)
        size = min(base_px, avail_h / 1.35)
        units = sum(1.0 if ord(ch) > 0x2E80 else 0.56 for ch in str(text or ""))
        if units > 0:
            size = min(size, avail_w / units * 1.02)
        return max(floor, size)

    def _sync(self, *_):
        self._rect.pos = self.pos
        self._rect.size = self.size

    def describe(self) -> Dict[str, Any]:
        return {"name": self.course.get("name"), "teacher": self.course.get("teacher"),
                "room": self.course.get("room"), "color": self.card_color,
                "size": (round(self.width), round(self.height))}


class WeekGrid(FloatLayout):
    """左侧节次轴 + 7 列课程网格（今天所在列浅蓝底，横向细网格线）。"""

    def __init__(self, ctx: Ctx, week: int, compact: bool = False, today_wd: int = 0, **kwargs):
        super().__init__(**kwargs)
        self.ctx = ctx
        self.compact = compact
        self.week = int(week)
        self.today_wd = int(today_wd or 0)
        self.axis_w = 0.0 if compact else u(34)
        self.boxes: List[CourseBox] = []
        self._axis: List[Label] = []
        self.model = tt_model.build_grid(ctx.courses, self.week)

        times = tt_model.session_times(ctx.cfg)
        for idx, pair in enumerate(times, start=1):
            text = str(idx) if compact else f"{idx}\n{pair[0]}"
            axis = _label(text, ctx, 8.5 if compact else 9, "dim", halign="center",
                          size_hint=(None, None))
            self.add_widget(axis)
            self._axis.append(axis)

        for day in self.model["days"]:
            for block in day["blocks"]:
                box = CourseBox(block["course"], ctx.color_index(block["course"].get("name")),
                                ctx, compact=compact)
                box.lane, box.lanes = block["lane"], block["lanes"]
                box.weekday, box.start, box.end = day["weekday"], block["start"], block["end"]
                self.add_widget(box)
                self.boxes.append(box)
        self.bind(pos=self.relayout, size=self.relayout)

    def relayout(self, *_):
        w, h = self.width, self.height
        if w <= 1 or h <= 1:
            return
        axis_w = self.axis_w
        col_w = (w - axis_w) / 7.0
        row_h = h / float(tt_model.SLOT_COUNT)
        self._paint(axis_w, col_w, row_h)

        for i, axis in enumerate(self._axis):
            axis.pos = (0, h - (i + 1) * row_h)
            axis.size = (axis_w, row_h)

        for box in self.boxes:
            lane_w = col_w / float(box.lanes)
            x = axis_w + (box.weekday - 1) * col_w + box.lane * lane_w
            top = h - (box.start - 1) * row_h
            height = (box.end - box.start + 1) * row_h
            box.size = (max(u(12), lane_w - u(3)), max(u(14), height - u(3)))
            box.pos = (x + u(1.5), top - height + u(1.5))

    def _paint(self, axis_w: float, col_w: float, row_h: float) -> None:
        w, h = self.width, self.height
        line_h = max(1.0, u(0.6))
        self.canvas.before.clear()
        with self.canvas.before:
            if axis_w > 1:
                Color(*rgba(THEME["panel2"]))
                Rectangle(pos=(self.x, self.y), size=(axis_w, h))
                Color(*rgba(THEME["border"]))
                Rectangle(pos=(self.x + axis_w, self.y), size=(line_h, h))
            if self.today_wd:
                Color(*rgba(THEME["today_col"]))
                Rectangle(pos=(self.x + axis_w + (self.today_wd - 1) * col_w, self.y),
                          size=(col_w, h))
            Color(*rgba(THEME["line"]))
            for i in range(tt_model.SLOT_COUNT + 1):
                Rectangle(pos=(self.x, self.y + i * row_h), size=(w, line_h))

    def metrics(self) -> Dict[str, Any]:
        w = max(1.0, self.width)
        h = max(1.0, self.height)
        col_w = (w - self.axis_w) / 7.0
        return {"unit": round(unit_scale(), 4), "window": [round(w), round(h)],
                "axis_w": round(self.axis_w, 2), "col_w": round(col_w, 2),
                "row_h": round(h / float(tt_model.SLOT_COUNT), 2),
                "axis_w_frac": round(self.axis_w / w, 4),
                "col_w_frac": round(col_w / w, 4),
                "today_wd": self.today_wd, "blocks": len(self.boxes),
                "lanes": self.model.get("lanes", 1)}


class WeekView(BoxLayout):
    """整周视图 = 星期/日期表头 + 课表网格。"""

    def __init__(self, ctx: Ctx, compact: bool = False, week: Optional[int] = None, **kwargs):
        super().__init__(orientation="vertical", **kwargs)
        self.ctx = ctx
        self.compact = compact
        self.week = int(week or ctx.week)
        self.dates = tt_model.week_dates(ctx.cfg, self.week)
        self.today_wd = ctx.today if self.week == ctx.week else 0
        self.header = WeekdaysHeader(ctx, self.dates, self.today_wd, compact=compact)
        self.grid = WeekGrid(ctx, self.week, compact=compact, today_wd=self.today_wd)
        self.boxes = self.grid.boxes
        self.add_widget(self.header)
        self.add_widget(self.grid)

    def describe(self) -> Dict[str, Any]:
        return {"compact": self.compact, "week": self.week,
                "dates": [d.strftime("%m-%d") if d else "" for d in self.dates],
                "today_weekday": self.today_wd,
                "blocks": len(self.grid.boxes), "lanes": self.grid.model.get("lanes", 1),
                "metrics": self.grid.metrics(),
                "courses": [b.describe() for b in self.grid.boxes]}


class TodayView(BoxLayout):
    """今日视图：日期/周次摘要 + 今日课程卡片列表（浅色卡片 + 左侧色条）。"""

    def __init__(self, ctx: Ctx, **kwargs):
        super().__init__(orientation="vertical", padding=(u(12), u(10), u(12), u(10)),
                         spacing=u(8), **kwargs)
        self.ctx = ctx
        self.info = tt_model.today_courses(ctx.courses, ctx.cfg)
        self.cards: List[Dict[str, Any]] = []
        self._build()

    def _build(self) -> None:
        ctx, info = self.ctx, self.info
        d = info["date"]
        summary = (f"{d.year}年{d.month:02d}月{d.day:02d}日  星期{WEEKDAY_CN[info['weekday'] - 1]}   "
                   f"第 {info['week']} 周 / 共 {tt_model.total_weeks(ctx.cfg)} 周")
        head = _label(summary, ctx, 12.5, bold=True)
        head.size_hint_y = None
        head.height = u(24)
        self.add_widget(head)

        if info["current"]:
            state = _label(f"正在上第 {info['current']} 小节", ctx, 11.5, "ok")
        else:
            nxt = tt_model.next_session(ctx.cfg)
            state = _label("当前无课" + (f"，下一节为第 {nxt} 小节" if nxt else "，今日课程已结束"),
                           ctx, 11.5, "warn")
        state.size_hint_y = None
        state.height = u(20)
        self.add_widget(state)

        if not info["items"]:
            self.add_widget(_label("今天没有课，休息一下 ~", ctx, 13, "dim", halign="center"))
            return

        scroll = ScrollView(do_scroll_x=False)
        inner = BoxLayout(orientation="vertical", size_hint_y=None, spacing=u(8),
                          padding=(0, u(4)))
        inner.bind(minimum_height=inner.setter("height"))
        for item in info["items"]:
            inner.add_widget(self._card(item))
            self.cards.append(item["course"])
        scroll.add_widget(inner)
        self.add_widget(scroll)

    def _card(self, item: Dict[str, Any]) -> Widget:
        """白底圆角卡片 + 左侧课程色条（与周视图课程卡同色）。"""
        ctx = self.ctx
        bg, _fg = course_palette(ctx.color_index(item["course"].get("name")))
        card = BoxLayout(orientation="horizontal", size_hint_y=None, height=u(60),
                         spacing=u(8), padding=(u(18), u(7)))
        with card.canvas.before:
            Color(*rgba(THEME["panel2"]))
            rect = RoundedRectangle(radius=[u(10)], pos=card.pos, size=card.size)
            Color(*rgba(THEME["border"]))
            Color(*rgba(bg))
            bar = RoundedRectangle(radius=[u(2)], pos=(card.x + u(10), card.y + u(10)),
                                   size=(u(4), u(40)))
        card.bind(pos=lambda w, *_: _place(card, rect, bar),
                  size=lambda w, *_: _place(card, rect, bar))

        left = BoxLayout(orientation="vertical", size_hint_x=None, width=u(70))
        left.add_widget(_single_line(_label(f"{item['begin']}", ctx, 11, "text", bold=True)))
        left.add_widget(_single_line(_label(f"第 {item['start']}-{item['end']} 节", ctx, 9.5, "sub")))
        mid = BoxLayout(orientation="vertical")
        mid.add_widget(_single_line(_label(str(item["course"].get("name") or ""), ctx, 13, bold=True)))
        detail = " ".join(x for x in (item["course"].get("room"), item["course"].get("teacher")) if x)
        mid.add_widget(_single_line(_label(detail or "-", ctx, 10.5, "sub")))
        right = _single_line(_label(item["state"], ctx, 10.5, "accent", halign="center"))
        right.size_hint_x = None
        right.width = u(44)

        card.add_widget(left)
        card.add_widget(mid)
        card.add_widget(right)
        return card

    def describe(self) -> Dict[str, Any]:
        return {"week": self.info["week"], "weekday": self.info["weekday"],
                "items": len(self.info["items"]),
                "names": [c.get("name") for c in self.cards]}


def _place(card: BoxLayout, rect: RoundedRectangle, bar: RoundedRectangle) -> None:
    rect.pos = card.pos
    rect.size = card.size
    bar.pos = (card.x + u(9), card.y + card.height - u(50))
    bar.size = (u(4), u(40))


# --------------------------------------------------------------------------- #
# 底部 Tab 栏
# --------------------------------------------------------------------------- #
TAB_ITEMS: List[Tuple[str, str, str]] = [
    ("today", "今日", "home"),
    ("week", "课表", "grid"),
    ("week_compact", "紧凑", "list"),
    ("mine", "我的", "user"),
]


class TabIcon(Widget):
    """Tab 图标：用画布图形绘制，不依赖字体里的符号字形。"""

    def __init__(self, kind: str, color: str, box: Optional[float] = None, **kwargs):
        side = box if box else u(19)
        super().__init__(size_hint=(None, None), size=(side, side), **kwargs)
        self.kind = kind
        self._ink = color
        self.bind(pos=self._draw, size=self._draw)
        self._draw()

    def set_color(self, color: str) -> None:
        self._ink = color
        self._draw()

    def _draw(self, *_):
        self.canvas.clear()
        w, h = self.size
        if w <= 1 or h <= 1:
            return
        x, y, c = self.x, self.y, rgba(self._ink)
        with self.canvas:
            Color(*c)
            if self.kind == "home":
                Line(points=[x + w * 0.5, y + h * 0.95, x + w * 0.97, y + h * 0.5,
                             x + w * 0.03, y + h * 0.5, x + w * 0.5, y + h * 0.95],
                     width=max(1.0, h * 0.11), joint="round")
                RoundedRectangle(pos=(x + w * 0.2, y + h * 0.06), size=(w * 0.6, h * 0.46),
                                 radius=[h * 0.08])
            elif self.kind == "grid":
                s, r = w * 0.34, h * 0.06
                for dx, dy in ((0.06, 0.52), (0.6, 0.52), (0.06, 0.06), (0.6, 0.06)):
                    RoundedRectangle(pos=(x + w * dx, y + h * dy), size=(s, h * 0.36),
                                     radius=[r])
            elif self.kind == "list":
                for dy in (0.72, 0.4, 0.08):
                    RoundedRectangle(pos=(x + w * 0.06, y + h * dy), size=(w * 0.88, h * 0.18),
                                     radius=[h * 0.09])
            else:  # user
                Ellipse(pos=(x + w * 0.3, y + h * 0.56), size=(w * 0.4, h * 0.4))
                RoundedRectangle(pos=(x + w * 0.14, y + h * 0.04), size=(w * 0.72, h * 0.44),
                                 radius=[h * 0.22])


class TabItem(BoxLayout):
    """单个 Tab：图标 + 文字，选中蓝色高亮。"""

    def __init__(self, key: str, text: str, icon: str, ctx: Ctx, on_press, **kwargs):
        super().__init__(orientation="vertical", size_hint_y=None, height=u(46),
                         padding=(0, u(5)), spacing=u(1), **kwargs)
        self.key = key
        self.on_press = on_press
        holder = BoxLayout(orientation="horizontal")
        self.icon = TabIcon(icon, THEME["dim"])
        holder.add_widget(Widget())
        holder.add_widget(self.icon)
        holder.add_widget(Widget())
        self.add_widget(holder)
        self.label = _label(text, ctx, 10, "dim", halign="center", size_hint_y=None,
                            height=u(14))
        self.add_widget(self.label)

    def on_touch_down(self, touch):
        if self.collide_point(*touch.pos):
            if self.on_press:
                self.on_press(self.key)
            return True
        return super().on_touch_down(touch)

    def set_active(self, active: bool) -> None:
        color = THEME["accent"] if active else THEME["dim"]
        self.icon.set_color(color)
        self.label.color = rgba(color)


class TabBar(BoxLayout):
    """底部 Tab 栏：白色底 + 顶部细线，当前模式高亮。"""

    def __init__(self, ctx: Ctx, active: str, on_select, **kwargs):
        super().__init__(orientation="horizontal", size_hint_y=None, height=u(56),
                         padding=(u(4), 0), **kwargs)
        self.on_select = on_select
        self.items: Dict[str, TabItem] = {}
        with self.canvas.before:
            Color(*rgba(THEME["panel"], 0.98))
            self._rect = Rectangle(pos=self.pos, size=self.size)
            Color(*rgba(THEME["border"]))
            self._line = Rectangle(pos=self.pos, size=(self.width, 1))
        self.bind(pos=self._sync, size=self._sync)
        for key, text, icon in TAB_ITEMS:
            item = TabItem(key, text, icon, ctx, self._press)
            self.add_widget(item)
            self.items[key] = item
        self.set_active(active)

    def _press(self, key: str) -> None:
        if self.on_select:
            self.on_select(key)

    def set_active(self, active: str) -> None:
        self.active = active
        for key, item in self.items.items():
            item.set_active(key == active)

    def _sync(self, *_):
        self._rect.pos = self.pos
        self._rect.size = self.size
        self._line.pos = (self.x, self.y + self.height - max(1.0, u(0.6)))
        self._line.size = (self.width, max(1.0, u(0.6)))
