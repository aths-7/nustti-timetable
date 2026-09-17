# -*- coding: utf-8 -*-
"""主题配色、字体族解析、通用小工具（Kivy 版）。

与桌面版（tkinter）保持一致的三件事：
    * 配色     THEME / COURSE_COLORS 直接沿用桌面版色值，保证两端观感一致
    * 字体族   桌面版用 tkinter 字体族名，Kivy 需要真实字体文件 → 这里做"族名 → 字体文件"解析
    * 字号缩放 0.8 ~ 1.6 倍；字体颜色覆盖正文/次要/暗淡三档
"""

from __future__ import annotations

import os
from typing import Dict, List, Optional, Tuple

from kivy.core.text import LabelBase
from kivy.utils import platform

# --------------------------------------------------------------------------- #
# 配色（沿用桌面版 main.py 的 THEME / COURSE_COLORS）
# --------------------------------------------------------------------------- #
THEME: Dict[str, str] = {
    "panel": "#181b26",
    "panel2": "#212636",
    "panel3": "#2b3245",
    "border": "#2c3348",
    "text": "#e9edf7",
    "sub": "#98a2bb",
    "dim": "#6c7590",
    "accent": "#4c8dff",
    "today_bg": "#242c42",
    "warn": "#ffb454",
    "ok": "#48d597",
}

COURSE_COLORS: List[Tuple[str, str]] = [
    ("#2f4a72", "#dce9ff"), ("#3f5f4a", "#dff5e4"), ("#5a3f6b", "#f0e2ff"),
    ("#6b4a2f", "#ffe9d6"), ("#2f5f66", "#d9f5f8"), ("#6b2f45", "#ffe0ea"),
    ("#4a4a2f", "#f5f0d6"), ("#334a80", "#e0e8ff"),
]

PRESET_FONT_COLORS: List[Tuple[str, str]] = [
    ("默认主题色", ""), ("亮黄", "#ffe066"), ("纯白", "#ffffff"), ("淡青", "#9ff3ea"),
    ("暖橙", "#ffb454"), ("浅粉", "#ffc2d1"), ("草绿", "#a8e6a1"), ("淡紫", "#d6bcfa"),
]

WEEKDAY_CN = ["一", "二", "三", "四", "五", "六", "日"]

FONT_SCALE_MIN, FONT_SCALE_MAX = 0.8, 1.6

# 字体族 → 候选字体文件（"族名" 既用于设置项展示，也用于 config.json 的 font_family）
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
    # tkinter 里常见但本机未必有真实字库的族名 → 回落到 Kivy 内置 Roboto（无衬线）
    "Modern": ["__kivy_roboro__"],
}

# Android 上按优先级寻找中文字库
_ANDROID_FONT_GLOBS = [
    "NotoSansCJK-Regular.ttc", "NotoSansCJKsc-Regular.otf", "NotoSansSC-Regular.otf",
    "DroidSansFallback.ttf", "DroidSansFallbackFull.ttf", "NotoSansCJK.ttc",
]

KIVY_FONT_ALIAS = "Roboto"


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
