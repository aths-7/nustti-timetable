# -*- coding: utf-8 -*-
"""南泰课表 · Kivy 版（Android + Windows 桌面双端）

与桌面版（tkinter + PyInstaller 的单文件 exe）功能对齐：
    * 界面：蓝色渐变顶栏（周次 + 日期区间 + 前/后翻周）+ 7 列课表网格 + 底部 Tab 栏
    * 三种视图：整周课表 / 今日 / 本周紧凑，底部 Tab 切换（「我的」= 设置面板）
    * 自定义背景图：铺满窗口 + 蒙版（浅色外观下是白纱）+ 模糊（照片退后），可清除
    * 字体可调：字体族（本机字体文件解析）/ 0.8~1.6 倍字号 / 字体颜色（预设 + 自定义）
    * 尺寸自适应：全部尺寸走 u()（按屏宽等比缩放，设计稿宽 392），不再用 dp()，
      保证任何像素密度的手机上各区域占比一致（修复"手机端比例异常"）
    * 本地配置：Windows 用 %APPDATA%\\NUSTTI-Timetable（与桌面版共用同一份），
                Android 用应用私有目录，同一套字段名
    * 在线同步：复用桌面版 jwgl_client / kb_parser 数据层，验证码弹窗输入
    * 命令行入口：--sync / --version / --selfcheck / --demo / --view

数据层（store.py / kb_parser.py / jwgl_client.py）直接沿用桌面版，仅 store.data_dir()
做了跨端等价改造，解析逻辑零改动。

运行（桌面）：
    python main.py                 正常启动
    python main.py --demo          载入演示课表
    python main.py --version       打印版本与路径
    python main.py --sync          无界面同步（需已保存学号密码）
    python main.py --selfcheck DIR 无人值守自检：渲染各视图 + 截图 + 落 report.json
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import threading
import time
import traceback
from datetime import datetime
from typing import Any, Dict, List, Optional, Tuple

IS_ANDROID = bool(os.environ.get("ANDROID_PRIVATE") or os.environ.get("ANDROID_ARGUMENT"))


def _desktop_scale() -> int:
    """Windows 显示缩放倍数（200% -> 2），用于让桌面预览与手机 dp/px 关系一致。"""
    if sys.platform != "win32":
        return 1
    try:
        import ctypes
        try:
            factor = int(ctypes.windll.shcore.GetScaleFactorForDevice(0)) // 100
        except Exception:
            factor = int(round(ctypes.windll.user32.GetDpiForSystem() / 96.0))
        return max(1, min(4, factor))
    except Exception:
        return 1


# --- 命令行参数：关掉 Kivy 自带的参数解析器 -------------------------------------
# Kivy 启动时会抢先解析 sys.argv，遇到 --selfcheck/--demo/--sync 这类自定义参数会直接
# 报 "option --selfcheck not recognized" 并退出(rc=2)，导致 python main.py --selfcheck DIR
# 无法直接使用（只能靠 "-- " 分隔或人工设 KIVY_NO_ARGS=1）。这里在导入 kivy 前定死，
# 让下面 argparse 独占参数解析。
os.environ.setdefault("KIVY_NO_ARGS", "1")

# --- 关键：必须在导入 kivy 之前定好像素度量 ------------------------------------
# 1) 部分 Windows 机器窗口 DPI 探测返回 0，Kivy 会把 Metrics.density 算成 0，
#    于是 dp(x) == 0 —— 所有 dp 尺寸归零，文本纹理退化为 0 像素并抛 MemoryError；
# 2) 运行期再改 Metrics.density 会联动 SDL2 窗口重排并抛 ctypes.ArgumentError，
#    所以一律在导入 kivy 之前用环境变量定死（Android 保持系统值，密度由平台提供）。
if not IS_ANDROID:
    _SCALE = _desktop_scale()
    os.environ.setdefault("KIVY_METRICS_DENSITY", str(_SCALE))
    os.environ.setdefault("KIVY_DPI", str(96 * _SCALE))

from kivy.app import App
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.logger import Logger
from kivy.metrics import dp
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.floatlayout import FloatLayout
from kivy.uix.label import Label
from kivy.uix.popup import Popup
from kivy.uix.widget import Widget

import store
import tt_bg
import tt_bgpick
import tt_model
import tt_theme
from tt_bg import BASE_BG, BackgroundLayer
from tt_settings import CaptchaDialog, SettingsOverlay
from tt_theme import THEME, rgba
from tt_views import (CellPickDialog, Ctx, Panel, TabBar, TodayView, TopBar, WeekView,
                      _label)

DEFAULT_VERSION = "1.0.2-kivy"
SELFCHECK = False

# 桌面预览窗口（手机比例），保证 Windows 上实跑与 Android 观感一致
DESKTOP_WINDOW = (520, 1000)


def calibrate_metrics() -> float:
    """读取当前像素密度并留痕。

    density 已由导入前的 KIVY_METRICS_DENSITY/KIVY_DPI 定好；此处只做健康检查，
    不再运行期修改 Metrics.density（会联动 SDL2 窗口重排并抛 ctypes.ArgumentError）。
    若仍为 0（例如被外部环境清掉配置），只能就地兜底为 1，否则 dp() 全为 0。
    """
    from kivy.metrics import Metrics
    density = float(Metrics.density)
    if density <= 0:
        Metrics.density = 1.0
        density = 1.0
        Logger.warning("main: 检测到 density=0，已强制回落到 1.0")
    Logger.info(f"main: dpi={Metrics.dpi} density={density} dp(12)={dp(12)}")
    return density


def read_version() -> str:
    """版本号优先读 VERSION 文件（与桌面版一致的外置迭代接口）。"""
    for base in (os.path.dirname(os.path.abspath(__file__)), os.getcwd(), store.data_dir()):
        path = os.path.join(base, "VERSION")
        try:
            with open(path, "r", encoding="utf-8") as fh:
                text = fh.readline().strip()
            if text:
                return text
        except OSError:
            continue
    return DEFAULT_VERSION


VERSION = read_version()


def looks_like_time(text: str) -> bool:
    """是否是 '08:00-09:35' 这类"上课时间"文本（自检判据用）。"""
    parts = str(text or "").split("-")
    return len(parts) == 2 and all(":" in p.strip() for p in parts)


class TimetableApp(App):
    title = "南泰课表"

    def __init__(self, demo: bool = False, view: str = "", **kwargs):
        super().__init__(**kwargs)
        self.version = VERSION
        self.demo = demo
        self.start_view = view
        self.cfg: Dict[str, Any] = {}
        self.courses: List[Dict[str, Any]] = []
        self.ctx: Optional[Ctx] = None
        self.bg: Optional[BackgroundLayer] = None
        self.content: Optional[BoxLayout] = None
        self.topbar: Optional[TopBar] = None
        self.panel: Optional[Panel] = None
        self.tabbar: Optional[TabBar] = None
        self.view_widget: Optional[Widget] = None
        self.mode = "week"
        self.display_week = 0          # 顶栏上正在查看的周（可前/后翻，默认当前周）
        self._ui_width = 0.0           # 当前界面尺寸基准（窗口宽度），用于识别尺寸变化
        self._resize_guard = False
        self._settings: Optional[SettingsOverlay] = None
        self._pick_dialog: Optional[Any] = None          # 同格多课的"显示哪门"弹窗
        self._sc_checks: Dict[str, Any] = {}             # 自检里的功能性检查结果
        self._sc_picks_backup: Optional[Dict[str, str]] = None
        self._sc_steps: List[Any] = []
        self._sc_index = 0
        self._sc_dir = ""
        self._sc_report: Dict[str, Any] = {}
        self._sc_done = True
        self._sc_bg_test_image = ""             # 自检里当"被选中的背景图"用的临时图片
        self._sc_bg_before = None               # 自检前主人原本的背景图（收尾还原）
        self.density = 0.0

    # ------------------------------------------------------------------ #
    # 生命周期
    # ------------------------------------------------------------------ #
    def build(self) -> Widget:
        self.cfg = store.load_config()
        data = store.load_timetable()
        self.courses = list(data.get("courses") or [])
        if self.demo or not self.courses:
            if self.demo:
                self.courses = tt_model.demo_courses()
            else:
                Logger.info("main: 本地暂无课表数据，自动载入演示课表（可在设置里同步）")
                self.courses = tt_model.demo_courses()
                if not self.cfg.get("term_start"):
                    self.cfg["term_start"] = datetime.now().strftime("%Y-%m-%d")
        self.mode = self.start_view or str(self.cfg.get("view_mode") or "week")
        if self.mode not in ("week", "today", "week_compact"):
            self.mode = "week"
        if not IS_ANDROID:
            # 先定窗口尺寸再算界面尺寸：u() 按窗口宽度换算，顺序反了会用到旧的宽度
            Window.size = self.desired_window_size()
        Window.clearcolor = rgba(BASE_BG, 1)
        self.density = calibrate_metrics()
        tt_theme.apply_font(str(self.cfg.get("font_family") or ""))
        self.ctx = self.make_ctx()
        self.display_week = int(self.ctx.week or 1)

        root = FloatLayout()
        self.bg = BackgroundLayer(bg_image=str(self.cfg.get("bg_image") or ""),
                                  veil=int(self.cfg.get("bg_veil", 60) or 0),
                                  blur=int(self.cfg.get("bg_blur", 8) or 0),
                                  scale=int(self.cfg.get("bg_scale", 100) or 100),
                                  alpha=int(self.cfg.get("bg_alpha", 100) or 100))
        root.add_widget(self.bg)
        self.content = BoxLayout(orientation="vertical")
        root.add_widget(self.content)
        self.rebuild()
        self.watch_window_size()
        return root

    # ------------------------------------------------------------------ #
    # 尺寸基准：u() 按窗口宽度换算，窗口尺寸变化后必须重建，否则各区域占比失衡
    # ------------------------------------------------------------------ #
    def watch_window_size(self) -> None:
        Window.bind(size=self._on_window_size)
        # 首帧时 SDL 窗口尺寸往往还没生效（仍是默认 800x600），延迟再校验一次
        Clock.schedule_once(lambda *_: self._on_window_size(), 0.35)

    def _on_window_size(self, *_args) -> None:
        if self.content is None:
            return
        width = float(Window.width or 0)
        if width <= 1:
            return
        base = float(self._ui_width or 0)
        if base > 1 and abs(width - base) / base < 0.05:
            return
        self._ui_width = width
        if self._resize_guard:
            return
        self._resize_guard = True
        Clock.schedule_once(self._rebuild_after_resize, 0)

    def _rebuild_after_resize(self, _dt: float) -> None:
        self._resize_guard = False
        try:
            self.rebuild()
        except Exception as exc:                              # pragma: no cover
            Logger.warning(f"main: 尺寸变化后重建失败 {exc}")

    def on_stop(self) -> None:
        try:                                     # 记住窗口尺寸（桌面端）
            if not IS_ANDROID:
                self.cfg["window_x"], self.cfg["window_y"] = int(Window.left), int(Window.top)
                store.save_config(self.cfg)
        except Exception:
            pass

    # ------------------------------------------------------------------ #
    # 界面组装
    # ------------------------------------------------------------------ #
    @staticmethod
    def desired_window_size() -> Tuple[int, int]:
        """桌面预览窗口尺寸；TT_WINDOW_SIZE=1080x2400 可用来复现高分屏排布（自检用）。"""
        raw = str(os.environ.get("TT_WINDOW_SIZE") or "").strip().lower()
        if "x" in raw:
            try:
                w, h = (int(float(v)) for v in raw.split("x", 1))
                if w > 0 and h > 0:
                    return (w, h)
            except Exception as exc:
                Logger.warning(f"main: TT_WINDOW_SIZE 解析失败 {raw}: {exc}")
        return DESKTOP_WINDOW

    def make_ctx(self) -> Ctx:
        ctx = Ctx(self.cfg, self.courses, self.version)
        # 同一格撞多门课（重修/分班）时，"这格显示哪门"由主人的点选决定，
        # 记录写在配置里（cell_picks），换周/重启/换机型后依然生效。
        ctx.picks = self.cell_picks()
        ctx.pick_cb = self.change_cell_pick
        return ctx

    def cell_picks(self) -> Dict[str, str]:
        """每格选课记录 { '星期-起节-止节': 课程名 }（配置损坏时回落空表）。"""
        raw = self.cfg.get("cell_picks")
        if not isinstance(raw, dict):
            return {}
        return {str(k): str(v) for k, v in raw.items() if str(v or "")}

    def change_cell_pick(self, box) -> None:
        """点"共 N 门"的课程卡 → 弹窗让主人选这一格显示哪门课（选完即持久化）。"""
        options = list(getattr(box, "options", None) or [])
        key = str(getattr(box, "pick_key", "") or "")
        if len(options) < 2 or not key or SELFCHECK:
            return
        self._pick_dialog = CellPickDialog(key, options, str(getattr(box, "chosen", "")),
                                           self.ctx, self.apply_cell_pick)
        self._pick_dialog.open()

    def apply_cell_pick(self, key: str, name: str) -> None:
        """把"这一格显示哪门课"写进配置并立即重建界面。"""
        picks = self.cell_picks()
        picks[str(key)] = str(name)
        self.cfg["cell_picks"] = picks
        store.save_config(self.cfg)
        self.rebuild()
        self.toast(f"这一格已改为显示「{name}」")

    def rebuild(self) -> None:
        """按当前配置整体重建界面（字体族/字号/颜色/换周后立即生效）。

        整屏结构（对应参考截图）：
            TopBar（蓝渐变：品牌 + 周次 + 日期区间 + 同步/设置）
            Panel （白色圆角面板内嵌当前视图）
            TabBar（底部 Tab：今日 / 课表 / 紧凑 / 我的）
        """
        self.ctx = self.make_ctx()
        if not self.display_week:
            self.display_week = int(self.ctx.week or 1)
        if self.content is None:
            return
        self.content.clear_widgets()
        self.topbar = TopBar(self.ctx, self._nav_callbacks(), self.display_week)
        self.content.add_widget(self.topbar)

        self.panel = Panel(color=THEME["panel"], alpha=self.ctx.panel_alpha(), radius=18,
                           orientation="vertical")
        self.view_widget = self._build_view(self.mode)
        self.panel.add_widget(self.view_widget)
        self.content.add_widget(self.panel)

        self.tabbar = TabBar(self.ctx, self.mode, self.select_tab)
        self.content.add_widget(self.tabbar)

    def _nav_callbacks(self) -> Dict[str, Any]:
        return {"prev_week": lambda: self.step_week(-1),
                "next_week": lambda: self.step_week(1),
                "this_week": self.goto_this_week,
                "sync": self.open_sync,
                "settings": self.open_settings}

    def _build_view(self, mode: str) -> Widget:
        if mode == "today":
            return TodayView(self.ctx)
        if mode == "week_compact":
            return WeekView(self.ctx, compact=True, week=self.display_week)
        return WeekView(self.ctx, compact=False, week=self.display_week)

    def set_view(self, mode: str) -> None:
        if mode not in ("week", "today", "week_compact"):
            return
        self.mode = mode
        self.cfg["view_mode"] = mode
        if self.panel is None:
            return
        if self.view_widget is not None:
            self.panel.remove_widget(self.view_widget)
        self.view_widget = self._build_view(mode)
        self.panel.add_widget(self.view_widget)
        if self.tabbar is not None:
            self.tabbar.set_active(mode)

    def step_week(self, delta: int) -> None:
        """顶栏左右箭头：前/后翻周（1 ~ 总周数）。"""
        total = tt_model.total_weeks(self.cfg)
        week = max(1, min(total, int(self.display_week or 1) + int(delta)))
        if week != self.display_week:
            self.refresh_week(week)

    def goto_this_week(self) -> None:
        if self.display_week != self.ctx.week:
            self.refresh_week(self.ctx.week)

    def refresh_week(self, week: int) -> None:
        self.display_week = int(week)
        self.rebuild()

    def select_tab(self, key: str) -> None:
        """底部 Tab：今日 / 课表 / 紧凑 / 我的（我的 = 打开设置面板）。"""
        if key == "mine":
            self.open_settings()
            return
        self.set_view(key)

    def cycle_view(self) -> None:
        order = ["week", "today", "week_compact"]
        idx = order.index(self.mode) if self.mode in order else 0
        self.set_view(order[(idx + 1) % len(order)])

    # ------------------------------------------------------------------ #
    # 背景 / 配置
    # ------------------------------------------------------------------ #
    def apply_veil(self, value: int) -> None:
        self.cfg["bg_veil"] = int(value)
        if self.bg is not None:
            self.bg.set_veil(value)

    def apply_blur(self, value: int) -> None:
        self.cfg["bg_blur"] = int(value)
        if self.bg is not None:
            self.bg.set_blur(value)

    def apply_bg_image(self, path: str) -> None:
        self.cfg["bg_image"] = path or ""
        if self.bg is not None:
            self.bg.set_image(path or "")

    def apply_bg_scale(self, value: int) -> None:
        self.cfg["bg_scale"] = int(value)
        if self.bg is not None:
            self.bg.set_scale(value)

    def apply_bg_alpha(self, value: int) -> None:
        self.cfg["bg_alpha"] = int(value)
        if self.bg is not None:
            self.bg.set_alpha(value)

    def save_cfg(self, cfg: Dict[str, Any]) -> None:
        merged = dict(self.cfg)
        merged.update(cfg)
        self.cfg = merged
        store.save_config(self.cfg)
        if self.bg is not None:
            if self.bg.bg_image != str(self.cfg.get("bg_image") or ""):
                self.bg.set_image(str(self.cfg.get("bg_image") or ""))
            self.bg.set_veil(int(self.cfg.get("bg_veil", 60) or 0))
            self.bg.set_blur(int(self.cfg.get("bg_blur", 8) or 0))
            self.bg.set_scale(int(self.cfg.get("bg_scale", 100) or 100))
            self.bg.set_alpha(int(self.cfg.get("bg_alpha", 100) or 100))
        tt_theme.apply_font(str(self.cfg.get("font_family") or ""))
        self.rebuild()
        self.close_settings()
        self.toast("设置已保存")

    def toast(self, text: str) -> None:
        popup = Popup(title="提示", content=Label(text=text, font_name=self.ctx.font
                                                  if self.ctx else "Roboto"),
                      size_hint=(0.6, 0.26), auto_dismiss=True)
        popup.open()
        Clock.schedule_once(lambda *_: popup.dismiss(), 1.8)

    # ------------------------------------------------------------------ #
    # 设置面板 / 同步
    # ------------------------------------------------------------------ #
    def open_settings(self) -> None:
        if self._settings is not None:
            return
        self._settings = SettingsOverlay(self, on_save=self.save_cfg,
                                       on_close=self.close_settings,
                                       on_sync=self.open_sync)
        self.root.add_widget(self._settings)

    def close_settings(self) -> None:
        if self._settings is not None:
            self.root.remove_widget(self._settings)
            self._settings = None

    def open_sync(self) -> None:
        cfg = dict(self.cfg)
        if self._settings is not None:                # 先把设置页当前值落盘，避免白填
            cfg.update(self._collect_settings())
            self.cfg = cfg
            store.save_config(cfg)
            self.close_settings()
            tt_theme.apply_font(str(cfg.get("font_family") or ""))
            self.rebuild()
        cfg = self.cfg
        if not cfg.get("student_id") or not store.deobfuscate(str(cfg.get("password") or "")):
            self.toast("请先在「设置」里填写学号与密码")
            return
        self.toast("正在同步课表，请稍候 ...")
        threading.Thread(target=self._sync_worker, daemon=True).start()

    def _collect_settings(self) -> Dict[str, Any]:
        """从设置面板读取当前值（点「同步课表」时先落盘，避免白填）。"""
        panel = self._settings
        if panel is None:
            return {}
        return {
            "student_id": panel.id_text.text.strip(),
            "password": store.obfuscate(panel.pwd_text.text) if panel.remember.active else "",
            "remember_password": bool(panel.remember.active),
            "term_start": panel.term_text.text.strip(),
            "total_weeks": int(panel.weeks.value),
            "font_family": panel.family.text,
            "font_scale": tt_theme.clamp_scale(panel.scale.value),
            "font_color": (panel.color_text.text or "").strip()
            if tt_theme.is_hex_color((panel.color_text.text or "").strip()) else "",
            "bg_image": panel._bg_path,
            "bg_veil": int(panel.veil.value),
            "bg_blur": int(panel.blur.value),
            "bg_scale": int(panel.bg_scale.value),
            "bg_alpha": int(panel.bg_alpha.value),
        }

    def _captcha_provider(self, image_bytes: bytes) -> str:
        """同步线程 → 主线程弹验证码框（阻塞 worker 直到用户提交）。"""
        done = threading.Event()
        holder = {"code": ""}

        def submit(code: str) -> None:
            holder["code"] = code
            done.set()

        def show(*_):
            try:
                CaptchaDialog(image_bytes, self.ctx, submit).open()
            except Exception as exc:                            # pragma: no cover
                Logger.warning(f"main: 验证码弹窗失败 {exc}")
                submit("")

        Clock.schedule_once(show, 0)
        done.wait(timeout=180)
        return holder["code"]

    def _sync_worker(self) -> None:
        from jwgl_client import JwglClient, JwglError  # 延迟导入，避免无网环境启动异常
        cfg = self.cfg
        logs: List[str] = []
        try:
            client = JwglClient(base_url=str(cfg.get("jwgl_base") or
                                             "https://jwgl.nustti.edu.cn"), log=logs.append)
            client.login(str(cfg.get("student_id")), store.deobfuscate(str(cfg.get("password") or "")),
                         captcha_provider=None if SELFCHECK else self._captcha_provider)
            result = client.fetch_timetable(str(cfg.get("term") or "") or None)
            courses = result.get("courses") or []
            store.save_timetable({"courses": courses, "term": result.get("term", ""),
                                  "source": "教务系统在线同步（Kivy 客户端）"})
            meta = result.get("meta") or {}
            if meta.get("session_times"):
                cfg["session_times"] = store.merge_session_times(meta["session_times"],
                                                                 cfg.get("session_times"))
            if meta.get("term_start"):
                cfg["term_start"] = str(meta["term_start"])
            cfg["term"] = result.get("term", cfg.get("term", ""))
            store.save_config(cfg)
            Clock.schedule_once(lambda *_: self._after_sync(courses, None), 0)
        except Exception as exc:
            message = f"{exc}"
            if not isinstance(exc, JwglError):
                message = f"{message}\n{traceback.format_exc(limit=3)}"
            Clock.schedule_once(lambda *_: self._after_sync([], message), 0)

    def _after_sync(self, courses: List[Dict[str, Any]], error: Optional[str]) -> None:
        if error:
            self.toast(f"同步失败：{error.splitlines()[0][:80]}")
            Logger.warning(f"main: 同步失败 {error}")
            return
        self.courses = courses
        self.rebuild()
        self.toast(f"同步完成，共 {len(courses)} 条课程记录")

    def quit_app(self) -> None:
        self.stop()

    # ------------------------------------------------------------------ #
    # 自检模式
    # ------------------------------------------------------------------ #
    def run_selfcheck(self, outdir: str) -> None:
        global SELFCHECK
        SELFCHECK = True
        self._sc_dir = outdir
        self._sc_done = False
        os.makedirs(outdir, exist_ok=True)
        self._sc_steps = [
            ("week", lambda: self.set_view("week")),
            # 同格撞多课：程序化把第一处撞课切到"另一门"再重建，截图验证"一格一门 + 换课生效"
            ("week_switched", self._sc_cell_pick_sim),
            # 点"共 N 门"的卡片时弹出来的"选一门显示"弹窗
            ("cell_pick_dialog", self._sc_open_pick_dialog),
            ("today", lambda: self.set_view("today")),
            ("week_compact", lambda: self.set_view("week_compact")),
            ("settings", self.open_settings),
            # 设置页滚到底部再截一张：1080+ 高分屏最容易在页脚附近堆叠重叠
            ("settings_bottom", self._sc_scroll_settings),
            # 背景选择器：修复前这里是"弹窗里一片空白、选不到图"（FileChooserListView 在
            # 手机端列不出文件），现在换成自研选择器，本步直接打开并截图，列表有没有图、
            # 能不能选中、选中后背景层是否真的换图，全由 _sc_bg_picker_check() 给 bool 判据。
            ("bg_picker", self._sc_open_bg_picker),
            # 缩放 / 透明度调到极端再各截一张，肉眼可见背景图"放大 + 变透"
            ("bg_zoom", self._sc_bg_zoom),
            # 设置页滚到「自定义背景」一节：截下"从相册选择 / 图片缩放 / 透明度"三件套
            ("settings_bg", self._sc_settings_bg),
        ]
        Clock.schedule_once(self._sc_run_step, 2.4)
        Clock.schedule_once(self._sc_watchdog, 90)      # 兜底：任何一步卡死也能收尾

    def _sc_watchdog(self, _dt: float) -> None:
        if self._sc_done:
            return
        self._sc_report.setdefault("errors", []).append(
            f"watchdog: 自检超时（已完成 {self._sc_index}/{len(self._sc_steps)} 步）")
        self._sc_finish()

    def _sc_run_step(self, _dt: float) -> None:
        if self._sc_index >= len(self._sc_steps):
            self._sc_finish()
            return
        name, action = self._sc_steps[self._sc_index]
        print(f"[SC] step {self._sc_index + 1}/{len(self._sc_steps)}: {name}", flush=True)
        try:
            action()
        except Exception as exc:
            self._sc_report.setdefault("errors", []).append(f"{name}: {exc}")
        Clock.schedule_once(lambda *_: self._sc_capture(name), 0.8)

    def _sc_capture(self, name: str) -> None:
        shot = os.path.join(self._sc_dir, f"{name}.png")
        entry: Dict[str, Any] = {"screenshot": shot, "mode": self.mode}
        # Kivy 的 Window.screenshot 走 SDL2 glReadPixels，偶发只在磁盘上留下 0 字节文件
        # （提前返回、上下文未就绪等），此处最多重试 4 次，并把字节数写进报告，
        # 避免"文件存在但内容为空"被当成截图成功。
        for attempt in range(4):
            try:
                real = Window.screenshot(name=os.path.join(self._sc_dir, f"{name}.png")) or ""
                if isinstance(real, str) and real:
                    shot = real                      # Kivy 会给缺省模板补 0001 等序号
                time.sleep(0.5)
            except Exception as exc:
                self._sc_report.setdefault("errors", []).append(f"screenshot {name}: {exc}")
                break
            if os.path.isfile(shot) and os.path.getsize(shot) > 0:
                break
            print(f"[SC] 截图 {name} 第 {attempt + 1} 次落盘为空，重试", flush=True)
        entry["screenshot"] = shot
        entry["exists"] = os.path.isfile(shot)
        entry["bytes"] = os.path.getsize(shot) if os.path.isfile(shot) else 0
        widget = self.view_widget
        try:
            if isinstance(widget, WeekView):
                entry.update(widget.describe())
            elif isinstance(widget, TodayView):
                entry.update(widget.describe())
        except Exception as exc:
            entry["describe_error"] = str(exc)
        if name == "settings":
            # 设置页 / 我的页：把行高与行距量出来，作为"1080+ 不堆叠重叠"的客观判据
            try:
                entry["settings_spacing"] = self._settings_spacing_check()
            except Exception as exc:
                entry["settings_spacing"] = {"ok": False, "error": str(exc)}
        if name == "bg_picker":
            # 在关掉弹窗之前把"列表是否列得出图 / 能否选中 / 缩放与透明度是否生效"都量下来
            try:
                entry["bg_picker_check"] = self._sc_bg_picker_check()
            except Exception as exc:
                entry["bg_picker_check"] = {"ok": False, "error": f"{type(exc).__name__}: {exc}"}
        self._sc_report.setdefault("steps", []).append(entry)
        if name.startswith("cell_pick_dialog") and self._pick_dialog is not None:
            try:
                self._pick_dialog.popup.dismiss()
            except Exception as exc:
                self._sc_report.setdefault("errors", []).append(f"close pick dialog: {exc}")
            self._pick_dialog = None
        if name == "bg_picker":
            dialog = getattr(self._settings, "bg_picker", None) if self._settings else None
            if dialog is not None:
                try:
                    dialog.dismiss()
                except Exception as exc:
                    self._sc_report.setdefault("errors", []).append(f"close bg picker: {exc}")
        if name.startswith("settings"):
            try:
                self.close_settings()
            except Exception as exc:
                self._sc_report.setdefault("errors", []).append(f"close settings: {exc}")
        self._sc_index += 1
        Clock.schedule_once(self._sc_run_step, 0.2)

    def _sc_finish(self) -> None:
        if self._sc_done:
            return
        self._sc_done = True
        # 自检步骤里"模拟换课"只改了内存配置，收尾时还原，避免写进主人的真实配置
        if self._sc_picks_backup is not None:
            self.cfg["cell_picks"] = dict(self._sc_picks_backup)
            self._sc_picks_backup = None
        # 自检期间为了截图把背景换成了测试图，这里还原主人原本的背景（只在内存里改，
        # 自检从不调用 save_cfg，所以主人的 config.json 不会被写脏）
        if self._sc_bg_before is not None:
            self.apply_bg_image(self._sc_bg_before)
            self._sc_bg_before = None
        self._sc_checks.setdefault("cell_pick", self._cell_pick_check())
        for step in self._sc_report.get("steps", []):
            if step.get("settings_spacing"):
                self._sc_checks["settings_spacing"] = step["settings_spacing"]
            if step.get("bg_picker_check"):
                self._sc_checks["bg_picker"] = step["bg_picker_check"]
        layout = self._describe_layout()
        report = {
            "app": "NUSTTI_Timetable_Kivy",
            "version": self.version,
            "generated_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "python": sys.version.split()[0],
            "kivy": __import__("kivy").__version__,
            "window": [int(Window.width), int(Window.height)],
            "density": getattr(self, "density", None),
            "data_dir": store.data_dir(),
            "courses": len(self.courses),
            "demo": bool(self.demo),
            "week": tt_model.current_week(self.cfg),
            "display_week": self.display_week,
            "term": self.cfg.get("term", ""),
            "font": tt_theme.font_info(),
            "scale": float(self.cfg.get("font_scale", 1.0) or 1.0),
            "ink": str(self.cfg.get("font_color") or ""),
            "ink_effective": self.ctx.ink if self.ctx else "",
            "ink_ignored": bool(self.ctx.ink_ignored) if self.ctx else False,
            "layout": layout,
            "background": self.bg.described_state() if self.bg else {},
            "steps": self._sc_report.get("steps", []),
            "errors": self._sc_report.get("errors", []),
            "checks": self._sc_checks,
        }
        week_steps = [s for s in report["steps"] if s.get("mode") == "week"]
        # 课程卡副行里是否真的带上了"教室 / 上课时间"（本轮修复点之一）
        cards = [c for s in report["steps"] for c in (s.get("courses") or [])]
        room_ok = any(str(c.get("room") or "").strip() for c in cards)
        time_ok = any(any(looks_like_time(ln) for ln in (c.get("lines") or []))
                      for c in cards)
        cell = self._sc_checks.get("cell_pick") or {}
        spacing = self._sc_checks.get("settings_spacing") or {}
        bg = self._sc_checks.get("bg_picker") or {}
        report["assertions"] = {
            "views_rendered": len(report["steps"]) == len(self._sc_steps),
            "screenshots_saved": all(s.get("bytes", 0) > 0 for s in report["steps"]),
            "metrics_ok": bool(dp(1) > 0),
            "week_blocks_positive": bool(week_steps and week_steps[0].get("blocks", 0) > 0),
            "background_loaded": bool(report["background"].get("image_ok")),
            # 只认"真的注册了字体文件"：内置 Roboto 不含中日韩字形，文本会变空心方块
            "font_applied": report["font"].get("font_name") == "ttfont",
            "no_errors": not report["errors"],
            # 比例自检：顶栏/底栏/表头/网格列宽占屏比必须落在设计稿口径内，
            # 防止再次出现"手机端显示比例异常"（dp 随像素密度变化导致的失衡）。
            "layout_ratios_ok": bool(layout.get("ratios_ok")),
            "grid_columns_ok": bool(layout.get("columns_ok")),
            # ---- 本轮三处修复的回归判据 ----
            # ① 同一格多门课：一格只画一张卡，且点选能换课、选择能持久化
            "cell_single_card_ok": bool(cell.get("single_card_ok")),
            "cell_pick_switch_ok": bool(cell.get("switch_ok")),
            "cell_pick_persist_ok": bool(cell.get("persist_ok")),
            # ② 课程卡副行同时给出教室与上课时间（第 X-Y 节的起止时刻）
            "card_room_ok": bool(room_ok),
            "card_time_ok": bool(time_ok),
            # ③ 1080+ 分辨率下设置页/我的页各项行高与行距足够，不会堆叠重叠
            "settings_spacing_ok": bool(spacing.get("ok")),
            # ---- 本轮"自定义背景"修复 + 扩展的回归判据 ----
            # ④ 背景选择器：弹窗开得起来、列表真的列得出图片（原来是空白列表）
            "bg_picker_opened_ok": bool(bg.get("popup_open")),
            "bg_picker_listed_ok": bool(bg.get("listed_ok")),
            "bg_picker_rows_ok": bool(int(bg.get("images") or 0) >= 1 and int(bg.get("rows") or 0) >= 1),
            "bg_picker_hint_ok": bool(bg.get("hint_ok")),
            # ⑤ 选中一行 + 「确定」后，设置页与底层背景都要真的换到这张图
            "bg_picker_select_ok": bool(bg.get("select_ok")),
            "bg_picker_apply_ok": bool(bg.get("pick_ok") and bg.get("apply_ok")),
            # ⑥ 缩放 / 透明度滑块必须真的改变绘制结果（50% 缩一半、200% 放大一倍；0% 全透）
            "bg_zoom_ok": bool(bg.get("zoom_ok")),
            "bg_alpha_ok": bool(bg.get("alpha_ok")),
        }
        report["assertions"]["all_bg_ok"] = bool(
            report["assertions"]["bg_picker_opened_ok"]
            and report["assertions"]["bg_picker_listed_ok"]
            and report["assertions"]["bg_picker_rows_ok"]
            and report["assertions"]["bg_picker_select_ok"]
            and report["assertions"]["bg_picker_apply_ok"]
            and report["assertions"]["bg_zoom_ok"]
            and report["assertions"]["bg_alpha_ok"])
        report["assertions"]["all_layout_ok"] = bool(
            report["assertions"]["layout_ratios_ok"] and report["assertions"]["grid_columns_ok"]
            and report["assertions"]["metrics_ok"])
        report["assertions"]["all_fixes_ok"] = bool(
            report["assertions"]["cell_single_card_ok"]
            and report["assertions"]["cell_pick_switch_ok"]
            and report["assertions"]["cell_pick_persist_ok"]
            and report["assertions"]["card_room_ok"]
            and report["assertions"]["card_time_ok"]
            and report["assertions"]["settings_spacing_ok"])
        # 一票总判：界面布局 + 历史三处修复 + 本轮「自定义背景」全部达标
        report["assertions"]["all_ok"] = bool(report["assertions"]["all_layout_ok"]
                                              and report["assertions"]["all_fixes_ok"]
                                              and report["assertions"]["all_bg_ok"])
        path = os.path.join(self._sc_dir, "selfcheck_report.json")
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(report, fh, ensure_ascii=False, indent=2)
        print("[SELFCHECK] " + json.dumps({"report": path,
                                           "assertions": report["assertions"],
                                           "courses": report["courses"],
                                           "font": report["font"],
                                           "layout": layout},
                                          ensure_ascii=False), flush=True)
        try:
            self.stop()
        except Exception as exc:      # 关窗时 SDL2/ctypes 噪声，不影响已落盘产物
            print(f"[SELFCHECK] 退出噪声：{type(exc).__name__}: {exc}", flush=True)

    def _cell_pick_check(self) -> Dict[str, Any]:
        """同格多课的回归判据：①一格只出一张卡；②点选切换能生效；③选择能持久化。"""
        week = int(self.display_week or tt_model.current_week(self.cfg) or 1)
        base = tt_model.build_grid(self.courses, week, {})
        out: Dict[str, Any] = {"conflicts": len(base.get("conflicts") or []),
                               "single_card_ok": True, "switch_ok": False,
                               "persist_ok": False, "key": "", "target": ""}
        # ① 一格一门：同一次渲染里不存在两张卡共用同一个格子键
        keys = [str(b.get("key")) for day in base["days"] for b in day["blocks"]]
        out["cells"] = len(keys)
        out["single_card_ok"] = bool(keys) and len(keys) == len(set(keys))
        conflicts = base.get("conflicts") or []
        if not conflicts:
            return out
        item = conflicts[0]
        key = str(item.get("key") or "")
        others = [n for n in (item.get("options") or []) if n != item.get("chosen")]
        if not key or not others:
            return out
        out["key"], out["target"] = key, others[-1]
        # ② 换课生效：传入 picks 后，这格必须显示被选中的那门
        switched = tt_model.build_grid(self.courses, week, {key: out["target"]})
        for day in switched["days"]:
            for block in day["blocks"]:
                if str(block.get("key")) == key:
                    out["switch_ok"] = str(block.get("chosen")) == out["target"]
        # ③ 持久化：写盘 → 重新读配置，确认选择还在（测完把原配置还原，不污染主人数据）
        original = store.load_config()
        try:
            cfg = dict(original)
            picks = dict(cfg.get("cell_picks") or {})
            picks[key] = out["target"]
            cfg["cell_picks"] = picks
            store.save_config(cfg)
            out["persist_ok"] = str((store.load_config().get("cell_picks") or {}).get(key)
                                    or "") == out["target"]
        finally:
            store.save_config(original)
        out["ok"] = bool(out["single_card_ok"] and out["switch_ok"] and out["persist_ok"])
        return out

    def _sc_cell_pick_sim(self) -> None:
        """自检用：把第一处撞课的格子切到另一门并重建（截图留证，收尾时还原内存配置）。"""
        check = self._cell_pick_check()
        self._sc_checks["cell_pick"] = check
        if not (check.get("key") and check.get("target")):
            return
        self._sc_picks_backup = dict(self.cell_picks())
        picks = self.cell_picks()
        picks[str(check["key"])] = str(check["target"])
        self.cfg["cell_picks"] = picks
        self.rebuild()

    def _sc_open_pick_dialog(self) -> None:
        """自检用：打开"这一格显示哪门课"弹窗并截图（模拟主人点卡片的效果）。"""
        self.set_view("week")
        week = int(self.display_week or tt_model.current_week(self.cfg) or 1)
        base = tt_model.build_grid(self.courses, week, self.cell_picks())
        conflicts = base.get("conflicts") or []
        if not conflicts:
            return
        item = conflicts[0]
        day = next((d for d in base["days"] if d["weekday"] == item["weekday"]), None)
        block = next((b for b in (day or {}).get("blocks", [])
                      if str(b.get("key")) == str(item["key"])), None)
        if not block:
            return
        self._pick_dialog = CellPickDialog(block["key"], block["options"],
                                           str(block.get("chosen") or ""), self.ctx,
                                           lambda *_: None)
        self._pick_dialog.open()

    def _sc_scroll_settings(self) -> None:
        """自检用：打开设置页并滚到底部（拍下半屏各项间距）。"""
        self.open_settings()
        panel = self._settings
        scroll = getattr(panel, "scroll", None)
        if scroll is not None:
            scroll.scroll_y = 0.0

    # ------------------------------------------------------------------ #
    # 自检：自定义背景（选择器 / 缩放 / 透明度）
    # ------------------------------------------------------------------ #
    def _sc_make_bg_image(self) -> str:
        """造一张自检用的背景图（放在自检目录的 bgtest 子目录里，不碰主人的任何文件）。

        选择器按"当前已选图片所在目录"扫描，把测试图放进 bgtest 子目录后，
        打开选择器时它必然出现在候选目录里 —— 列表能不能列出来就成了硬判据。
        单独开一个子目录是为了不让截图目录里成百上千张 png 混进候选列表。
        """
        folder = os.path.join(self._sc_dir, "bgtest")
        target = os.path.join(folder, "bg_pick_test.png")
        try:
            os.makedirs(folder, exist_ok=True)
            from PIL import Image, ImageDraw
            img = Image.new("RGB", (900, 1600), (32, 96, 168))
            draw = ImageDraw.Draw(img)
            for i in range(0, 1600, 100):           # 画点条纹，缩放/透明度变化肉眼可辨
                draw.rectangle([0, i, 900, i + 50], fill=(240, 244, 250))
            img.save(target)
            return target if os.path.isfile(target) else ""
        except Exception as exc:                    # pragma: no cover
            Logger.warning(f"main: 自检背景图生成失败（{exc}）")
        # 退路：主人配置里已有的背景图（如果存在）
        current = str(self.cfg.get("bg_image") or "")
        if current and os.path.isfile(current):
            return current
        return ""

    def _sc_open_bg_picker(self) -> None:
        """自检用：打开设置页的「选择背景图」弹窗（原 bug 现场：列表空白、选不到图）。"""
        self._sc_bg_before = str(self.cfg.get("bg_image") or "")
        self._sc_bg_test_image = self._sc_make_bg_image()
        extra = [os.path.dirname(self._sc_bg_test_image)] if self._sc_bg_test_image else None
        self.open_settings()
        panel = self._settings
        if panel is None:
            return
        panel._choose_bg(extra_dirs=extra)

    def _sc_bg_picker_check(self) -> Dict[str, Any]:
        """背景选择器 + 缩放 + 透明度的回归判据（全部给 bool，不靠肉眼）。"""
        panel = self._settings
        dialog = getattr(panel, "bg_picker", None) if panel is not None else None
        if dialog is None:
            return {"ok": False, "error": "背景选择器没打开"}
        target = str(self._sc_bg_test_image or "")
        state = dialog.described()
        paths = [os.path.abspath(str(p)) for p in (state.get("paths") or [])]
        listed = bool(target) and os.path.abspath(target) in paths
        images = int(state.get("images") or 0)
        rows = int(state.get("rows") or 0)
        hint = str(state.get("hint") or "").strip()

        # ① 选中一行 + 点「确定」→ 设置页与底层背景都要真的换图
        dialog._pick({"path": target})
        select_ok = bool(target) and os.path.abspath(str(dialog.selected or "")) == os.path.abspath(target)
        dialog._confirm()
        pick_ok = bool(dialog.picked) and str(self.cfg.get("bg_image") or "") == target
        bg_state = self.bg.described_state() if self.bg else {}
        apply_ok = bool(bg_state.get("image_ok") and str(bg_state.get("bg_image") or "") == target)

        # ② 缩放：50% / 100% / 200% 三档的绘制宽度必须近似成比例（真的在缩放，不是摆设）
        sizes: Dict[str, float] = {}
        layer: List[float] = []
        texture: Any = None
        if self.bg is not None:
            for value in (100, tt_bg.SCALE_MIN, tt_bg.SCALE_MAX):
                self.bg.set_scale(value)
                state_i = self.bg.described_state()
                size = state_i.get("photo_size") or [0, 0]
                sizes[str(value)] = round(float(size[0] or 0), 1)
                layer = state_i.get("layer_size") or layer
                texture = state_i.get("texture") or texture
        base_w = sizes.get("100") or 0.0
        zoom_ok = bool(base_w > 0
                       and sizes.get(str(tt_bg.SCALE_MIN), 0.0) < base_w * 0.65
                       and sizes.get(str(tt_bg.SCALE_MAX), 0.0) > base_w * 1.5)

        # ③ 透明度：100% / 40% / 0% 的颜色通道 alpha 必须落到位
        alphas: Dict[str, Any] = {}
        if self.bg is not None:
            for value in (100, 40, tt_bg.ALPHA_MIN):
                self.bg.set_alpha(value)
                alphas[str(value)] = self.bg.described_state().get("photo_alpha")
        try:
            alpha_ok = bool(float(alphas.get("100")) == 1.0
                            and float(alphas.get(str(tt_bg.ALPHA_MIN))) == 0.0
                            and 0.3 <= float(alphas.get("40")) <= 0.5)
        except Exception:
            alpha_ok = False

        # 收尾：缩放/透明度还原成配置里的值（背景图先留着，后面两步截图要展示效果，
        # 真正还原主人原背景图放在 _sc_finish 里做，避免自检改动被持久化）
        if self.bg is not None:
            self.bg.set_scale(int(self.cfg.get("bg_scale", 100) or 100))
            self.bg.set_alpha(int(self.cfg.get("bg_alpha", 100) or 100))
        kept_image = str(self.cfg.get("bg_image") or "") == target

        return {"ok": bool(listed and rows >= 1 and images >= 1 and pick_ok and apply_ok
                           and zoom_ok and alpha_ok),
                "popup_open": bool(state.get("popup_open")),
                "listed_ok": listed, "test_image": target,
                "images": images, "rows": rows, "thumbs": int(state.get("thumbs") or 0),
                "dirs": int(state.get("dirs") or 0),
                "paths_head": [os.path.basename(p) for p in paths[:6]],
                "candidates": state.get("candidates") or [],
                "hint": hint, "hint_ok": bool(hint),
                "gallery_button": bool(state.get("gallery_button")),
                "select_ok": select_ok, "pick_ok": pick_ok, "apply_ok": apply_ok,
                "zoom_ok": zoom_ok, "photo_sizes": sizes,
                "layer_size": layer, "texture": texture,
                "alpha_ok": alpha_ok, "photo_alphas": alphas,
                "kept_test_image": kept_image}

    def _sc_bg_zoom(self) -> None:
        """自检用：把背景放大到上限、调透到 45% 再截一张（肉眼可见变化）。"""
        if self._settings is not None:
            try:
                self.close_settings()
            except Exception:
                pass
        self.set_view("week")
        if self.bg is not None and self.bg.image_ok:
            self.bg.set_veil(12)                     # 蒙版调淡，让缩放/透明度变化看得出来
            self.bg.set_scale(tt_bg.SCALE_MAX)
            self.bg.set_alpha(45)

    def _sc_settings_bg(self) -> None:
        """自检用：设置页滚到「自定义背景」一节（截下相册入口 + 缩放/透明度滑块）。"""
        if self.bg is not None:                      # 先把上一步的极端值还原回配置口径
            self.bg.set_veil(int(self.cfg.get("bg_veil", 60) or 0))
            self.bg.set_scale(int(self.cfg.get("bg_scale", 100) or 100))
            self.bg.set_alpha(int(self.cfg.get("bg_alpha", 100) or 100))
        self.open_settings()
        panel = self._settings
        if panel is None:
            return
        try:
            panel.scroll_to_bg()
        except Exception as exc:
            self._sc_report.setdefault("errors", []).append(f"scroll to bg: {exc}")

    def _settings_spacing_check(self) -> Dict[str, Any]:
        """设置页 / 我的页行高与行距是否拉开（1080+ 高分屏堆叠重叠的回归判据）。"""
        panel = self._settings
        if panel is None:
            return {"ok": False, "error": "设置面板未打开"}
        body = getattr(panel, "body", None)
        if body is None:
            return {"ok": False, "error": "拿不到设置页行容器"}
        unit = max(1.0, float(tt_theme.unit_scale()))
        heights = [float(w.height) for w in body.children if float(w.height) > 0]
        gap = float(body.spacing)
        min_row = min(heights) if heights else 0.0
        return {"ok": bool(gap >= unit * 6 and min_row >= unit * 18),
                "rows": len(body.children), "min_row": round(min_row, 1),
                "gap": round(gap, 1), "unit": round(unit, 4),
                "min_row_required": round(unit * 18, 1), "gap_required": round(unit * 6, 1)}

    def _describe_layout(self) -> Dict[str, Any]:
        """各区域高度/宽度占屏比（比例异常的客观判据）。"""
        h = max(1.0, float(Window.height or 0))
        w = max(1.0, float(Window.width or 0))
        top_h = float(self.topbar.height) if self.topbar else 0.0
        tab_h = float(self.tabbar.height) if self.tabbar else 0.0
        panel_h = float(self.panel.height) if self.panel else 0.0
        view = self.view_widget
        head_h = float(getattr(view, "header", None).height) if hasattr(view, "header") else 0.0
        metrics = {}
        # 注意：自检结束时 view_widget 停在最后一步（紧凑视图），整周网格口径要从
        # 已归档的 step 记录里取，避免把"紧凑视图无节次轴"误当成整周视图的列宽。
        for step in self._sc_report.get("steps", []):
            if step.get("compact") is False and step.get("metrics"):
                metrics = step["metrics"]
                break
        return {
            "unit": round(tt_theme.unit_scale(), 4),
            "design_w": tt_theme.DESIGN_W,
            "window": [int(w), int(h)],
            "density": getattr(self, "density", None),
            "topbar_h": round(top_h, 1), "topbar_frac": round(top_h / h, 4),
            "panel_frac": round(panel_h / h, 4),
            "tabbar_h": round(tab_h, 1), "tabbar_frac": round(tab_h / h, 4),
            "weekdays_header_h": round(head_h, 1),
            "weekdays_header_frac": round(head_h / h, 4),
            "grid": metrics,
            # 参考截图口径：顶栏约 8~16%、底栏约 4~9%、星期表头 ≤ 8%
            "ratios_ok": bool(0.07 <= top_h / h <= 0.17 and 0.03 <= tab_h / h <= 0.10
                              and head_h / h <= 0.08),
            # 7 列网格：每列占屏宽应约 (1 - 节次轴占比)/7
            "columns_ok": bool(metrics and 0.10 <= metrics.get("col_w_frac", 0) <= 0.15),
        }


# --------------------------------------------------------------------------- #
# 无界面同步（--sync）
# --------------------------------------------------------------------------- #
def cli_sync(captcha: str = "") -> int:
    from jwgl_client import JwglClient, JwglError
    cfg = store.load_config()
    student_id = str(cfg.get("student_id") or "")
    password = store.deobfuscate(str(cfg.get("password") or ""))
    if not student_id or not password:
        print("[SYNC] 配置里没有学号/密码，请先在图形界面或 config.json 里填写")
        return 2

    def provider(_bytes: bytes) -> str:
        if captcha:
            return captcha
        if sys.stdin and sys.stdin.isatty():
            return input("请输入验证码：")
        return ""

    logs: List[str] = []
    try:
        client = JwglClient(base_url=str(cfg.get("jwgl_base") or "https://jwgl.nustti.edu.cn"),
                            log=lambda text: (logs.append(text), print("[SYNC] " + text)))
        client.login(student_id, password, captcha_provider=provider)
        result = client.fetch_timetable(str(cfg.get("term") or "") or None)
    except JwglError as exc:
        print(f"[SYNC] 失败：{exc}")
        return 1
    courses = result.get("courses") or []
    store.save_timetable({"courses": courses, "term": result.get("term", ""),
                          "source": "教务系统在线同步（Kivy 命令行）"})
    meta = result.get("meta") or {}
    if meta.get("session_times"):
        cfg["session_times"] = store.merge_session_times(meta["session_times"],
                                                        cfg.get("session_times"))
    if meta.get("term_start"):
        cfg["term_start"] = str(meta["term_start"])
    cfg["term"] = result.get("term", cfg.get("term", ""))
    store.save_config(cfg)
    print(f"[SYNC] 完成：{len(courses)} 条课程记录 → {store.timetable_path()}")
    return 0


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="南泰课表 · Kivy 版")
    parser.add_argument("--version", action="store_true", help="打印版本号与数据目录")
    parser.add_argument("--sync", action="store_true", help="无界面同步课表")
    parser.add_argument("--captcha", default="", help="--sync 时直接提供验证码")
    parser.add_argument("--demo", action="store_true", help="载入演示课表")
    parser.add_argument("--view", default="", help="启动视图：week / today / week_compact")
    parser.add_argument("--selfcheck", default="", help="自检并把截图与报告写入指定目录")
    args = parser.parse_args(argv)

    if args.version:
        print(f"南泰课表(Kivy) v{VERSION}")
        print(f"数据目录：{store.data_dir()}")
        print(f"配置：{store.config_path()}")
        print(f"课表：{store.timetable_path()}")
        print(f"平台：{'android' if IS_ANDROID else sys.platform}")
        return 0
    if args.sync:
        return cli_sync(args.captcha)

    app = TimetableApp(demo=args.demo, view=args.view)
    if args.selfcheck:
        app.run_selfcheck(args.selfcheck)
    try:
        app.run()
    except Exception as exc:                      # 退出时 SDL2 关窗噪声，不影响产物
        if not args.selfcheck:
            raise
        print(f"[SELFCHECK] 退出噪声（不影响截图与报告）：{type(exc).__name__}: {exc}",
              flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
