# -*- coding: utf-8 -*-
"""配置与课表数据的本地持久化。

存放位置（用户级目录，不写入程序目录，避免权限问题）：
    %APPDATA%\\NUSTTI-Timetable\\config.json     程序配置（含学号/密码）
    %APPDATA%\\NUSTTI-Timetable\\timetable.json  课表数据

安全说明：
    密码仅做 Base64 轻微混淆（满足"本地保存、程序界面内输入"的要求），
    并非加密，请勿把 config.json 分享给他人。程序内不含任何硬编码账号凭据。
"""

from __future__ import annotations

import base64
import copy
import json
import os
from datetime import datetime
from typing import Any, Dict, List, Optional, Sequence

APP_DIR_NAME = "NUSTTI-Timetable"
CONFIG_NAME = "config.json"
TIMETABLE_NAME = "timetable.json"
DEBUG_DIR_NAME = "debug"
_PWD_TAG = "b64:"

# 默认作息时间（12 小节，可在"设置"中逐项修改为学校真实作息）
DEFAULT_SESSION_TIMES: List[List[str]] = [
    ["08:00", "08:45"],
    ["08:50", "09:35"],
    ["09:50", "10:35"],
    ["10:40", "11:25"],
    ["13:30", "14:15"],
    ["14:20", "15:05"],
    ["15:20", "16:05"],
    ["16:10", "16:55"],
    ["18:30", "19:15"],
    ["19:20", "20:05"],
    ["20:10", "20:55"],
    ["21:00", "21:45"],
]

def merge_session_times(times: Any, base: Optional[Sequence[Sequence[str]]] = None
                        ) -> List[List[str]]:
    """把教务解析出的逐小节时间并入 12 段作息表。

    教务课表常按"大节"标注（如 1,2节 08:00-09:40），逐小节时间是均分近似值；
    教务未覆盖的小节沿用 base（默认作息），尾段则按上一段时长顺延，
    避免残留在真实作息之外的旧默认值。
    """
    if base and len(list(base)) == len(DEFAULT_SESSION_TIMES):
        out = [[str(v[0]), str(v[1])] for v in base]
    else:
        out = [list(pair) for pair in DEFAULT_SESSION_TIMES]
    parsed: Dict[int, List[str]] = {}
    for key, value in (times or {}).items():
        try:
            idx = int(key)
        except (TypeError, ValueError):
            continue
        if 1 <= idx <= len(out) and value and len(list(value)) >= 2:
            parsed[idx] = [str(list(value)[0]), str(list(value)[1])]
    if not parsed:
        return out

    def to_min(text: str) -> int:
        hh, mm = str(text).split(":")
        return int(hh) * 60 + int(mm)

    for idx, pair in parsed.items():
        out[idx - 1] = pair
    last = max(parsed)
    if last < len(out):
        span = max(1, to_min(parsed[last][1]) - to_min(parsed[last][0]))
        cursor = to_min(parsed[last][1])
        for idx in range(last + 1, len(out) + 1):
            begin = cursor + 5
            out[idx - 1] = [f"{begin // 60:02d}:{begin % 60:02d}",
                            f"{(begin + span) // 60:02d}:{(begin + span) % 60:02d}"]
            cursor = begin + span
    return out


DEFAULT_CONFIG: Dict[str, Any] = {
    "student_id": "",
    "password": "",                 # 混淆后的密码
    "remember_password": True,
    "jwgl_base": "https://jwgl.nustti.edu.cn",
    "term": "",                     # 学期标识，如 2026-2027-1
    "term_start": "",               # 第 1 周星期一，格式 YYYY-MM-DD
    "total_weeks": 20,
    "session_times": DEFAULT_SESSION_TIMES,
    "opacity": 0.94,
    "always_on_top": True,
    "rounded": True,
    "view_mode": "week",            # week / today / week_compact
    "mini": False,
    "lesson_reminder": True,        # 上课提醒（提前 5 分钟）
    "window_x": None,
    "window_y": None,
    "bg_image": "",                 # 自定义背景图片的绝对路径（空 = 使用默认深色外观）
    "bg_veil": 60,                  # 背景蒙版强度 0~100：越大越暗，课程文字越清晰
    "bg_blur": 8,                   # 背景模糊半径 0~30（像素）：越大照片越"退后"
    "font_family": "",              # 界面字体族（空 = 内置默认 Microsoft YaHei UI）
    "font_scale": 1.0,              # 字号缩放 0.8~1.6（1.0 = 内置默认字号）
    "font_color": "",               # 文字颜色（空 = 跟随主题配色；非空则覆盖正文/次要/暗淡文字）
    "minimize_to_tray": True,       # 是否启用系统托盘（关闭时托盘按钮与托盘图标都不出现）
    # 同一格撞上多门课（重修/分班）时该格显示哪一门：{"星期-起节-止节": 课程名}
    # 由课表页点课程卡选择并写入，跨周共用同一个选择（课程学期内不变）
    "cell_picks": {},
}


def data_dir() -> str:
    """数据目录（跨端等价实现）。

    Windows : %APPDATA%\\NUSTTI-Timetable      —— 与桌面版 exe 共用同一份配置/课表
    Android : 应用私有目录（ANDROID_PRIVATE）  —— 无需任何存储权限
    其他     : ~/.config/NUSTTI-Timetable
    """
    base = ""
    if os.environ.get("ANDROID_PRIVATE") or os.environ.get("ANDROID_ARGUMENT"):
        base = os.environ.get("ANDROID_PRIVATE") or os.environ.get("ANDROID_APP_PATH") or ""
    if not base:
        base = os.environ.get("APPDATA") or ""
    if not base:
        base = os.path.join(os.path.expanduser("~"), ".config")
    path = os.path.join(base, APP_DIR_NAME)
    os.makedirs(path, exist_ok=True)
    return path


def debug_dir() -> str:
    path = os.path.join(data_dir(), DEBUG_DIR_NAME)
    os.makedirs(path, exist_ok=True)
    return path


def config_path() -> str:
    return os.path.join(data_dir(), CONFIG_NAME)


def timetable_path() -> str:
    return os.path.join(data_dir(), TIMETABLE_NAME)


def default_config() -> Dict[str, Any]:
    cfg = copy.deepcopy(DEFAULT_CONFIG)
    cfg["session_times"] = copy.deepcopy(DEFAULT_SESSION_TIMES)
    return cfg


def load_config() -> Dict[str, Any]:
    cfg = default_config()
    try:
        with open(config_path(), "r", encoding="utf-8") as fh:
            data = json.load(fh)
        if isinstance(data, dict):
            cfg.update(data)
    except Exception:
        pass
    times = cfg.get("session_times")
    if not isinstance(times, list) or len(times) != len(DEFAULT_SESSION_TIMES):
        cfg["session_times"] = copy.deepcopy(DEFAULT_SESSION_TIMES)
    try:
        cfg["opacity"] = min(1.0, max(0.4, float(cfg.get("opacity", 0.94))))
    except Exception:
        cfg["opacity"] = 0.94
    try:
        cfg["total_weeks"] = max(1, int(cfg.get("total_weeks", 20)))
    except Exception:
        cfg["total_weeks"] = 20
    try:
        cfg["font_scale"] = min(1.6, max(0.8, float(cfg.get("font_scale", 1.0))))
    except Exception:
        cfg["font_scale"] = 1.0
    cfg["font_family"] = str(cfg.get("font_family") or "").strip()
    color = str(cfg.get("font_color") or "").strip()
    cfg["font_color"] = color if _is_hex_color(color) else ""
    cfg["minimize_to_tray"] = bool(cfg.get("minimize_to_tray", True))
    picks = cfg.get("cell_picks")
    if isinstance(picks, dict):
        cfg["cell_picks"] = {str(k): str(v).strip() for k, v in picks.items()
                             if str(k).strip() and str(v).strip()}
    else:
        cfg["cell_picks"] = {}
    return cfg


def _is_hex_color(text: str) -> bool:
    """#rrggbb 形式的颜色值校验（配置里手改过颜色时的兜底）。"""
    if len(text) != 7 or not text.startswith("#"):
        return False
    try:
        int(text[1:], 16)
    except ValueError:
        return False
    return True


def save_config(cfg: Dict[str, Any]) -> None:
    cfg = dict(cfg)
    cfg["saved_at"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    with open(config_path(), "w", encoding="utf-8") as fh:
        json.dump(cfg, fh, ensure_ascii=False, indent=2)


def obfuscate(text: str) -> str:
    if not text:
        return ""
    return _PWD_TAG + base64.b64encode(text.encode("utf-8")).decode("ascii")


def deobfuscate(token: str) -> str:
    if not token:
        return ""
    if token.startswith(_PWD_TAG):
        try:
            return base64.b64decode(token[len(_PWD_TAG):].encode("ascii")).decode("utf-8")
        except Exception:
            return ""
    return token


def load_timetable() -> Dict[str, Any]:
    try:
        with open(timetable_path(), "r", encoding="utf-8") as fh:
            data = json.load(fh)
        if isinstance(data, dict) and isinstance(data.get("courses"), list):
            data.setdefault("term", "")
            data.setdefault("source", "")
            data.setdefault("updated_at", "")
            return data
    except Exception:
        pass
    return {"courses": [], "term": "", "source": "", "updated_at": ""}


def save_timetable(data: Dict[str, Any]) -> None:
    data = dict(data)
    data["updated_at"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    with open(timetable_path(), "w", encoding="utf-8") as fh:
        json.dump(data, fh, ensure_ascii=False, indent=2)


def save_debug_html(html: str, tag: str = "page") -> str:
    """同步异常时把原始页面落盘，便于排查教务系统改版。"""
    name = f"{tag}_{datetime.now().strftime('%Y%m%d_%H%M%S')}.html"
    path = os.path.join(debug_dir(), name)
    try:
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(html)
    except Exception:
        return ""
    return path
