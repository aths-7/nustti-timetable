# -*- coding: utf-8 -*-
"""南泰课表 · Kivy 版（Android + Windows 桌面双端）

与桌面版（tkinter + PyInstaller 的单文件 exe）功能对齐：
    * 三种视图：整周课表 / 今日 / 本周紧凑，标题栏按钮循环切换
    * 自定义背景图：铺满窗口 + 蒙版（变暗）+ 模糊（照片退后），可清除
    * 字体可调：字体族（本机字体文件解析）/ 0.8~1.6 倍字号 / 字体颜色（预设 + 自定义）
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
from typing import Any, Dict, List, Optional

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
import tt_model
import tt_theme
from tt_bg import BackgroundLayer
from tt_settings import CaptchaDialog, SettingsOverlay
from tt_theme import THEME, rgba
from tt_views import Ctx, HeaderBar, TodayView, WeekView, _label

DEFAULT_VERSION = "1.0.0-kivy"
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
        self.header: Optional[HeaderBar] = None
        self.view_widget: Optional[Widget] = None
        self.mode = "week"
        self._settings: Optional[SettingsOverlay] = None
        self._sc_steps: List[Any] = []
        self._sc_index = 0
        self._sc_dir = ""
        self._sc_report: Dict[str, Any] = {}
        self._sc_done = True
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
        tt_theme.apply_font(str(self.cfg.get("font_family") or ""))
        self.ctx = Ctx(self.cfg, self.courses, self.version)
        if not IS_ANDROID:
            Window.size = DESKTOP_WINDOW
        Window.clearcolor = rgba("#0f1320", 1)
        self.density = calibrate_metrics()

        root = FloatLayout()
        self.bg = BackgroundLayer(bg_image=str(self.cfg.get("bg_image") or ""),
                                  veil=int(self.cfg.get("bg_veil", 60) or 0),
                                  blur=int(self.cfg.get("bg_blur", 8) or 0))
        root.add_widget(self.bg)
        self.content = BoxLayout(orientation="vertical")
        root.add_widget(self.content)
        self.rebuild()
        return root

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
    def make_ctx(self) -> Ctx:
        return Ctx(self.cfg, self.courses, self.version)

    def rebuild(self) -> None:
        """按当前配置整体重建界面（字体族/字号/颜色变更后立即生效）。"""
        self.ctx = self.make_ctx()
        if self.content is None:
            return
        self.content.clear_widgets()
        self.header = HeaderBar(self.ctx, {
            "switch_view": self.cycle_view,
            "sync": self.open_sync,
            "settings": self.open_settings,
            "quit": self.quit_app,
        })
        self.content.add_widget(self.header)
        self.header.mark_active(self.mode)
        self.view_widget = self._build_view(self.mode)
        self.content.add_widget(self.view_widget)
        if self.bg is not None:
            self.header.size_hint_y = None          # 保证标题栏高度固定

    def _build_view(self, mode: str) -> Widget:
        if mode == "today":
            return TodayView(self.ctx)
        if mode == "week_compact":
            return WeekView(self.ctx, compact=True)
        return WeekView(self.ctx, compact=False)

    def set_view(self, mode: str) -> None:
        if mode not in ("week", "today", "week_compact"):
            return
        self.mode = mode
        self.cfg["view_mode"] = mode
        if self.content is None:
            return
        if self.view_widget is not None:
            self.content.remove_widget(self.view_widget)
        self.view_widget = self._build_view(mode)
        self.content.add_widget(self.view_widget)
        if self.header is not None:
            self.header.mark_active(mode)

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
            ("today", lambda: self.set_view("today")),
            ("week_compact", lambda: self.set_view("week_compact")),
            ("settings", self.open_settings),
        ]
        Clock.schedule_once(self._sc_run_step, 1.2)
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
        # （提前返回、上下文未就绪等），此处最多重试 3 次，并把字节数写进报告，
        # 避免"文件存在但内容为空"被当成截图成功。
        for attempt in range(3):
            try:
                real = Window.screenshot(name=os.path.join(self._sc_dir, f"{name}.png")) or ""
                if isinstance(real, str) and real:
                    shot = real                      # Kivy 会给缺省模板补 0001 等序号
                time.sleep(0.3)
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
        self._sc_report.setdefault("steps", []).append(entry)
        if name == "settings":
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
            "term": self.cfg.get("term", ""),
            "font": tt_theme.font_info(),
            "scale": float(self.cfg.get("font_scale", 1.0) or 1.0),
            "ink": str(self.cfg.get("font_color") or ""),
            "background": self.bg.described_state() if self.bg else {},
            "steps": self._sc_report.get("steps", []),
            "errors": self._sc_report.get("errors", []),
        }
        week_steps = [s for s in report["steps"] if s.get("mode") == "week"]
        report["assertions"] = {
            "views_rendered": len(report["steps"]) == len(self._sc_steps),
            "screenshots_saved": all(s.get("bytes", 0) > 0 for s in report["steps"]),
            "metrics_ok": bool(dp(1) > 0),
            "week_blocks_positive": bool(week_steps and week_steps[0].get("blocks", 0) > 0),
            "background_loaded": bool(report["background"].get("image_ok")),
            # 只认"真的注册了字体文件"：内置 Roboto 不含中日韩字形，文本会变空心方块
            "font_applied": report["font"].get("font_name") == "ttfont",
            "no_errors": not report["errors"],
        }
        path = os.path.join(self._sc_dir, "selfcheck_report.json")
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(report, fh, ensure_ascii=False, indent=2)
        print("[SELFCHECK] " + json.dumps({"report": path,
                                           "assertions": report["assertions"],
                                           "courses": report["courses"],
                                           "font": report["font"]},
                                          ensure_ascii=False), flush=True)
        try:
            self.stop()
        except Exception as exc:      # 关窗时 SDL2/ctypes 噪声，不影响已落盘产物
            print(f"[SELFCHECK] 退出噪声：{type(exc).__name__}: {exc}", flush=True)


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
