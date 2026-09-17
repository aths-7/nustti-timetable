# -*- coding: utf-8 -*-
"""课表视图模型：把 store 里的课程列表换算成「周视图网格 / 今日清单」。

数据来源与桌面版完全一致（%APPDATA%\\NUSTTI-Timetable\\timetable.json），
本模块只做"课表 → 界面"的纯计算，不碰文件、不碰控件，便于单测与自检。
"""

from __future__ import annotations

from datetime import date, datetime, timedelta
from typing import Any, Dict, List, Optional, Tuple

SLOT_COUNT = 12


def parse_hhmm(text: str) -> Optional[int]:
    """'08:05' → 485（分钟）；非法输入返回 None。"""
    try:
        hh, mm = str(text).strip().replace("：", ":").split(":")
        return int(hh) * 60 + int(mm)
    except Exception:
        return None


def current_week(cfg: Dict[str, Any], today: Optional[date] = None) -> int:
    """按学期第 1 周星期一推算当前教学周（未配置则返回 1）。"""
    today = today or date.today()
    raw = str(cfg.get("term_start") or "").strip()
    for fmt in ("%Y-%m-%d", "%Y/%m/%d", "%Y.%m.%d"):
        try:
            start = datetime.strptime(raw, fmt).date()
        except ValueError:
            continue
        days = (today - start).days
        if days < 0:
            return 1
        return days // 7 + 1
    return 1


def total_weeks(cfg: Dict[str, Any]) -> int:
    try:
        return max(1, int(cfg.get("total_weeks", 20)))
    except (TypeError, ValueError):
        return 20


def session_times(cfg: Dict[str, Any]) -> List[List[str]]:
    times = cfg.get("session_times")
    if not isinstance(times, list) or len(times) != SLOT_COUNT:
        from store import DEFAULT_SESSION_TIMES  # 兜底：与桌面版共用同一套默认作息
        return [list(p) for p in DEFAULT_SESSION_TIMES]
    out: List[List[str]] = []
    for pair in times:
        try:
            out.append([str(pair[0]), str(pair[1])])
        except Exception:
            out.append(["", ""])
    return out


def session_index_of_now(cfg: Dict[str, Any], now: Optional[datetime] = None) -> Optional[int]:
    """当前正处于第几小节（课间/课后返回 None）。"""
    now = now or datetime.now()
    cur = now.hour * 60 + now.minute
    for idx, pair in enumerate(session_times(cfg), start=1):
        begin, end = parse_hhmm(pair[0]), parse_hhmm(pair[1])
        if begin is None or end is None:
            continue
        if begin <= cur <= end:
            return idx
    return None


def next_session(cfg: Dict[str, Any], now: Optional[datetime] = None) -> Optional[int]:
    """下一节待上的小节号（今天已无课则为 None）。"""
    now = now or datetime.now()
    cur = now.hour * 60 + now.minute
    for idx, pair in enumerate(session_times(cfg), start=1):
        begin = parse_hhmm(pair[0])
        if begin is not None and begin > cur:
            return idx
    return None


def in_week(course: Dict[str, Any], week: int) -> bool:
    weeks = course.get("weeks")
    if not isinstance(weeks, list) or not weeks:
        return True                     # 未标注周次 → 视为每周都有
    try:
        return int(week) in [int(w) for w in weeks]
    except (TypeError, ValueError):
        return False


def courses_of(courses: List[Dict[str, Any]], weekday: int, week: int) -> List[Dict[str, Any]]:
    out = [c for c in courses
           if int(c.get("weekday") or 0) == int(weekday) and in_week(c, week)]
    out.sort(key=lambda c: (min(c.get("sessions") or [99]), str(c.get("name") or "")))
    return out


def _slot_span(course: Dict[str, Any]) -> Tuple[int, int]:
    sessions = [int(s) for s in (course.get("sessions") or []) if str(s).isdigit()]
    if not sessions:
        sessions = [1]
    start = max(1, min(SLOT_COUNT, min(sessions)))
    end = max(start, min(SLOT_COUNT, max(sessions)))
    return start, end


def build_grid(courses: List[Dict[str, Any]], week: int) -> Dict[str, Any]:
    """生成整周视图网格。

    返回 {"days": [ {weekday, blocks:[...]} x7 ], "lanes": 每日最大并排数}
    每个 block: {course, start, end, lane, lanes}
    """
    days: List[Dict[str, Any]] = []
    max_lanes = 1
    color_of: Dict[str, int] = {}
    for weekday in range(1, 8):
        blocks: List[Dict[str, Any]] = []
        lane_end: List[int] = []                    # 每条泳道当前占用的最后一行
        for course in courses_of(courses, weekday, week):
            start, end = _slot_span(course)
            lane = 0
            while lane < len(lane_end) and lane_end[lane] >= start:
                lane += 1
            if lane == len(lane_end):
                lane_end.append(end)
            else:
                lane_end[lane] = end
            key = str(course.get("name") or "")
            if key not in color_of:
                color_of[key] = len(color_of)
            blocks.append({"course": course, "start": start, "end": end,
                           "lane": lane, "color": color_of[key]})
        lanes = max(1, len(lane_end))
        max_lanes = max(max_lanes, lanes)
        for block in blocks:
            block["lanes"] = lanes
        days.append({"weekday": weekday, "blocks": blocks})
    return {"days": days, "lanes": max_lanes, "week": week}


def today_courses(courses: List[Dict[str, Any]], cfg: Dict[str, Any],
                  now: Optional[datetime] = None) -> Dict[str, Any]:
    """今日课程清单（含"当前/下一节"标记）。"""
    now = now or datetime.now()
    weekday = now.isoweekday()
    week = current_week(cfg, now.date())
    items: List[Dict[str, Any]] = []
    times = session_times(cfg)
    for course in courses_of(courses, weekday, week):
        start, end = _slot_span(course)
        begin = times[start - 1][0] if start - 1 < len(times) else ""
        finish = times[end - 1][1] if end - 1 < len(times) else ""
        items.append({"course": course, "start": start, "end": end,
                      "begin": begin, "finish": finish,
                      "sections": list(course.get("sessions") or [])})
    cur = session_index_of_now(cfg, now)
    for item in items:
        item["state"] = ("现在" if cur is not None and item["start"] <= cur <= item["end"]
                         else ("已结束" if cur is not None and item["end"] < cur else "待上课"))
    return {"weekday": weekday, "week": week, "date": now.date(), "items": items,
            "current": cur}


def demo_courses() -> List[Dict[str, Any]]:
    """--demo 用的演示课表（与桌面版演示数据结构一致）。"""
    raw = [
        ("高等数学A", "王建国", "教1-101", 1, [1, 2], list(range(1, 17))),
        ("大学英语(3)", "李梅", "外语楼-302", 1, [5, 6], list(range(1, 13))),
        ("线性代数", "张伟", "教2-205", 2, [1, 2], list(range(1, 17))),
        ("大学物理", "刘洋", "理科楼-401", 2, [5, 6], list(range(1, 15))),
        ("程序设计基础", "陈斌", "机房-506", 3, [3, 4], list(range(1, 17))),
        ("数据结构", "赵敏", "教3-210", 3, [5, 6], list(range(1, 11))),
        ("体育(羽毛球)", "孙磊", "体育馆", 4, [3, 4], list(range(1, 17))),
        ("形势与政策", "周涛", "教1-208", 4, [7, 8], list(range(1, 9))),
        ("操作系统", "吴强", "教2-303", 5, [1, 2], list(range(1, 17))),
        ("计算机网络", "郑华", "教2-110", 5, [5, 6], list(range(1, 13))),
        ("马克思主义基本原理", "何芳", "教1-305", 5, [9, 10], list(range(1, 17))),
    ]
    out: List[Dict[str, Any]] = []
    for name, teacher, room, weekday, sessions, weeks in raw:
        out.append({"name": name, "teacher": teacher, "room": room,
                    "weeks": weeks, "week_raw": f"{weeks[0]}-{weeks[-1]}周",
                    "weekday": weekday, "sessions": sessions,
                    "raw": f"{name} / {teacher} / {room}"})
    return out


def week_label(cfg: Dict[str, Any], week: int) -> str:
    return f"第 {week} 周 / 共 {total_weeks(cfg)} 周"
