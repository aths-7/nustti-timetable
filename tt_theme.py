# -*- coding: utf-8 -*-
"""主题配色、字体族解析、自适应度量（Kivy 版）。

改版（v1.0.1，对齐参考截图）：
    * 配色：浅灰页面底 + 白色面板 + 蓝色渐变顶栏（#0EA5E9 → #06B6D4）
            + 6 色课程卡（黄/粉/绿/橙/蓝/紫，白字）
    * 度量：新增 u() —— 按屏宽等比缩放（设计稿宽度 392 单位），替代 dp()
      dp() 依赖设备上报的像素密度：同一份界面在不同手机上占屏比例能差近一倍，
      这正是"手机端显示比例异常"的根因。u(1) = 屏宽 / 392 像素，
      任何屏幕宽度下各区域占比都与设计稿一致，与设备密度彻底解耦。
    * 字体族解析 / 字号缩放 / 颜色校验沿用改版前实现（族名 → 字体文件 → Kivy 字体名）。
"""

from __future__ import annotations

import os
from typing import Dict, List, Optional, Tuple

from kivy.core.text import LabelBase
from kivy.utils import platform

# --------------------------------------------------------------------------- #
# 配色（浅色卡片风，对齐参考截图）
# --------------------------------------------------------------------------- #
THEME: Dict[str, str] = {
    "bg": "#F1F5F9",            # 页面底（浅灰）
    "panel": "#FFFFFF",         # 面板/卡片底
    "panel2": "#F8FAFC",        # 次级面板（节次轴、今日列）
    "panel3": "#EEF3F9",        # 输入框/按钮底
    "border": "#E2E8F0",        # 描边
    "line": "#EDF1F6",          # 网格线
    "text": "#0F172A",          # 主文字（深墨）
    "sub": "#64748B",           # 次要文字
    "dim": "#94A3B8",           # 暗淡文字
    "accent": "#0EA5E9",        # 主色（渐变起点 / 选中蓝）
    "accent2": "#06B6D4",       # 主色（渐变终点）
    "accent_soft": "#E0F2FE",   # 主色浅底
    "today": "#EF4444",         # 今天（红）
    "today_bg": "#F5FAFF",      # 今天所在列底色（沿用旧键名，兼容设置页）
    "today_col": "#F5FAFF",     # 今天所在列底色
    "on_accent": "#FFFFFF",     # 渐变底上的文字
    "warn": "#F59E0B",
    "ok": "#10B981",
}

# 课程色板（背景色, 文字色）：按课程名稳定分配，与今日视图共用同一套
COURSE_COLORS: List[Tuple[str, str]] = [
    ("#F59E0B", "#FFFFFF"),      # 黄 —— 传感器类
    ("#EC4899", "#FFFFFF"),      # 粉 —— 数字信号类
    ("#10B981", "#FFFFFF"),      # 绿 —— 通信原理类
    ("#F97316", "#FFFFFF"),      # 橙 —— FPGA 类
    ("#3B82F6", "#FFFFFF"),      # 蓝 —— 概率统计类
    ("#8B5CF6", "#FFFFFF"),      # 紫 —— 思政类
    ("#0EA5E9", "#FFFFFF"),      # 青
    ("#14B8A6", "#FFFFFF"),      # 蓝绿
]

PRESET_FONT_COLORS: List[Tuple[str, str]] = [
    ("默认主题色", ""), ("深墨", "#0F172A"), ("墨蓝", "#1E3A8A"), ("藏青", "#164E63"),
    ("深绿", "#166534"), ("深紫", "#5B21B6"), ("酒红", "#9F1239"), ("纯白", "#ffffff"),
]

WEEKDAY_CN = ["一", "二", "三", "四", "五", "六", "日"]

FONT_SCALE_MIN, FONT_SCALE_MAX = 0.8, 1.6

# --------------------------------------------------------------------------- #
# 自适应度量：u() —— 设计稿宽 392 单位，按实际屏宽等比缩放
# --------------------------------------------------------------------------- #
DESIGN_W = 392.0
_UNIT_CACHE: Dict[str, float] = {"w": -1.0, "k": 1.0}


def window_width() -> float:
    """当前窗口宽度（Kivy 坐标单位 = 像素）。窗口尚未创建时返回设计稿宽度。"""
    try:
        from kivy.core.window import Window
        width = float(getattr(Window, "width", 0) or 0)
    except Exception:
        width = 0.0
    return width if width > 1 else DESIGN_W


def unit_scale() -> float:
    """1 个设计单位对应多少像素（= 屏宽 / 392，带上下限保护）。"""
    width = window_width()
    if abs(width - _UNIT_CACHE["w"]) < 0.5:
        return _UNIT_CACHE["k"]
    scale = max(0.4, min(6.5, width / DESIGN_W))
    _UNIT_CACHE.update({"w": width, "k": scale})
    return scale


def u(value: float) -> float:
    """设计单位 → 像素（替代 dp()，与设备像素密度无关）。"""
    return float(value) * unit_scale()


def rgba(color: str, alpha: float = 1.0) -> List[float]:
    """'#rrggbb' → [r, g, b, a]（Kivy 用 0~1 浮点）。"""
    text = (color or "").strip()
    if not is_hex_color(text):
        text = "#ffffff"
    return [int(text[1:3], 16) / 255.0, int(text[3:5], 16) / 255.0,
            int(text[5:7], 16) / 255.0, float(alpha)]


def is_hex_color(text: str) -> bool:
    if len(text) != 7 or not text.startswith("#"):
        return False
    try:
        int(text[1:], 16)
    except ValueError:
        return False
    return True


def lum(color: str) -> float:
    """感知亮度 0~1（用于判断文字颜色在浅底上是否可读）。"""
    r, g, b, _ = rgba(color)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def readable_on_light(color: str) -> bool:
    """浅色外观下是否可读：颜色足够深才算可读。

    历史上配置里存的文字颜色（如 #ffe066 亮黄）是给深色外观配的，
    直接拿到浅色卡片上会看不清 —— 此时自动忽略该值，回落主题墨色。
    """
    return lum(color) < 0.62


def mix(c1: str, c2: str, k: float) -> str:
    """线性混色，k=0 取 c1，k=1 取 c2。"""
    a, b = rgba(c1), rgba(c2)
    return "#%02x%02x%02x" % tuple(round((a[i] + (b[i] - a[i]) * k) * 255) for i in range(3))


def clamp_scale(value: float) -> float:
    try:
        return round(min(FONT_SCALE_MAX, max(FONT_SCALE_MIN, float(value))), 2)
    except (TypeError, ValueError):
        return 1.0


def course_palette(index: int) -> Tuple[str, str]:
    return COURSE_COLORS[index % len(COURSE_COLORS)]


# --------------------------------------------------------------------------- #
# 字体族解析（族名 → 字体文件 → Kivy 字体名）
# --------------------------------------------------------------------------- #
WINDOWS_FONTS_DIR = os.path.join(os.environ.get("WINDIR", r"C:\Windows"), "Fonts")
ANDROID_FONTS_DIR = "/system/fonts"

_FAMILY_FILES: Dict[str, List[str]] = {
    "Microsoft YaHei UI": ["msyh.ttc", "msyhl.ttc", "msyh.ttf"],
    "微软雅黑": ["msyh.ttc", "msyh.ttf"],
    "SimHei": ["simhei.ttf"],
    "黑体": ["simhei.ttf"],
    "Microsoft JhengHei": ["msjh.ttc"],
    "SimSun": ["simsun.ttc"],
    "宋体": ["simsun.ttc"],
    "KaiTi": ["simkai.ttf"],
    "楷体": ["simkai.ttf"],
    "FangSong": ["simfang.ttf"],
    "DengXian": ["Deng.ttf", "dengxian.ttf"],
    "等线": ["Deng.ttf"],
    "Consolas": ["consola.ttf", "consolab.ttf"],
    "Segoe UI": ["segoeui.ttf"],
    "Arial": ["arial.ttf"],
    "Tahoma": ["tahoma.ttf"],
    "Verdana": ["verdana.ttf"],
    "Times New Roman": ["times.ttf"],
    # tkinter 里常见但本机未必有真实字库的族名 → 回落到本机默认可显示中文字体
    "Modern": ["__kivy_roboro__"],
}

# Android 上按优先级寻找中文字库
_ANDROID_FONT_GLOBS = [
    "NotoSansCJK-Regular.ttc", "NotoSansCJKsc-Regular.otf", "NotoSansSC-Regular.otf",
    "DroidSansFallback.ttf", "DroidSansFallbackFull.ttf", "NotoSansCJK.ttc",
]

KIVY_FONT_ALIAS = "Roboto"


def _windows_font_path(files: List[str]) -> Optional[str]:
    for name in files:
        if name.startswith("__kivy"):
            return None
        path = os.path.join(WINDOWS_FONTS_DIR, name)
        if os.path.isfile(path):
            return path
    return None


def _android_font_path(files: List[str]) -> Optional[str]:
    for name in files:
        if not name.startswith("__kivy"):
            path = os.path.join(ANDROID_FONTS_DIR, name)
            if os.path.isfile(path):
                return path
    for name in _ANDROID_FONT_GLOBS:
        path = os.path.join(ANDROID_FONTS_DIR, name)
        if os.path.isfile(path):
            return path
    try:
        for name in sorted(os.listdir(ANDROID_FONTS_DIR)):
            low = name.lower()
            if low.endswith((".ttf", ".ttc", ".otf")) and (
                    "cjk" in low or "fallback" in low or "sc" in low or "chinese" in low):
                return os.path.join(ANDROID_FONTS_DIR, name)
    except OSError:
        pass
    return None


def resolve_font_path(family: str) -> Optional[str]:
    """把配置里的字体族名解析成本机字体文件；解析不到返回 None（用内置字体）。"""
    files = _FAMILY_FILES.get((family or "").strip())
    if not files:
        return None
    if platform == "android" or os.environ.get("ANDROID_PRIVATE"):
        return _android_font_path(files)
    if os.name == "nt":
        return _windows_font_path(files)
    return None


def default_family() -> str:
    if platform == "android" or os.environ.get("ANDROID_PRIVATE"):
        return "NotoSansCJK" if _android_font_path([]) else KIVY_FONT_ALIAS
    return "Microsoft YaHei UI"


def available_families() -> List[str]:
    """本机实际可用的字体族（供设置页下拉框使用）。"""
    out: List[str] = []
    for name in _FAMILY_FILES:
        if name == "Modern" and (platform == "android" or os.name != "nt"):
            continue
        if resolve_font_path(name):
            out.append(name)
    if platform == "android" and _android_font_path([]):
        out.insert(0, "NotoSansCJK")
    if not out:
        out = [KIVY_FONT_ALIAS]
    return out


_FONT_STATE: Dict[str, str] = {"family": "", "font_name": KIVY_FONT_ALIAS,
                               "path": "", "fallback": "0"}


def apply_font(family: str) -> Dict[str, str]:
    """按族名注册 Kivy 字体并返回当前生效信息。

    返回 {"family": 生效族名, "font_name": 供 Label 使用的字体名,
          "path": 字体文件, "fallback": "1" 表示回落到内置字体}
    """
    family = (family or "").strip()
    path = resolve_font_path(family) or ""
    if not family:
        family = default_family()
        path = resolve_font_path(family) or ""
    # "Modern"（本机 config.json 里与桌面版共用的值）与 "Roboto" 只是占位族名：
    # tkinter 会把 Modern 映射到系统字体，Kivy 不会 —— 直接用内置 Roboto 渲染中文会变成
    # 一排空心方块（豆腐块）。故解析不到真实字体文件时，回落到本机的默认可显示中文字体，
    # Android 上是 NotoSansCJK，Windows 上是微软雅黑。
    if not path and family in ("Modern", KIVY_FONT_ALIAS):
        fb_family = default_family()
        fb_path = resolve_font_path(fb_family) or ""
        if fb_path:
            family, path = fb_family, fb_path
    if not path and family in ("NotoSansCJK",):
        path = _android_font_path([]) or ""
    font_name = KIVY_FONT_ALIAS
    fallback = "1"
    if path:
        try:
            LabelBase.register(name="ttfont", fn_regular=path)
            font_name, fallback = "ttfont", "0"
        except Exception:
            font_name, fallback = KIVY_FONT_ALIAS, "1"
    elif family in ("Modern", KIVY_FONT_ALIAS):
        font_name, fallback = KIVY_FONT_ALIAS, "0"
    _FONT_STATE.update({"family": family, "font_name": font_name,
                        "path": path, "fallback": fallback})
    return dict(_FONT_STATE)


def font_name() -> str:
    return _FONT_STATE["font_name"]


def font_info() -> Dict[str, str]:
    return dict(_FONT_STATE)
