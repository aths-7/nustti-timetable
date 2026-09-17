# -*- coding: utf-8 -*-
"""课表解析：教务系统课表页面(HTML) / Excel / CSV / 纯文本  →  统一课程列表。

统一课程数据模型（每条）::

    {
        "name": "高等数学A",
        "teacher": "张三",
        "room": "教1-101",
        "weeks": [1, 2, ..., 16],   # 有课的周次
        "week_raw": "1-16周",
        "weekday": 1,               # 1=星期一 ... 7=星期日
        "sessions": [1, 2],         # 第几小节
        "raw": "单元格原始文本"
    }

解析策略（按优先级自动降级）：
    HTML : 强智(jsxsd) 课表页 table#kbtable  →  任意包含"星期一"表头的表格
    Excel: 含"星期一"表头的表格（支持纵向合并单元格）
    文本 : 表头驱动 / 按内容嗅探列含义 / 宽松单行解析
"""

from __future__ import annotations

import csv
import html as _html
import io
import os
import re
from typing import Any, Dict, List, Optional, Sequence, Tuple

WEEKDAY_CN = ["星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"]
_SEP_TOKEN = "\x00SEP\x00"

_BR_RE = re.compile(r"<\s*br\s*/?\s*>", re.I)
_HR_RE = re.compile(r"<\s*hr[^>]*>", re.I)
_BLOCK_RE = re.compile(r"<\s*/?\s*(?:p|div|li|h[1-6]|tr|table|section|article|tbody)\b[^>]*>", re.I)
_TAG_RE = re.compile(r"<[^>]+>")
_TABLE_RE = re.compile(r"<table[^>]*>.*?</table>", re.I | re.S)
_TR_RE = re.compile(r"<tr[^>]*>(.*?)</tr>", re.I | re.S)
_TD_RE = re.compile(r"<t[dh]([^>]*)>(.*?)</t[dh]>", re.I | re.S)
_ROWSPAN_RE = re.compile(r"rowspan\s*=\s*[\"']?(\d+)", re.I)
_COLSPAN_RE = re.compile(r"colspan\s*=\s*[\"']?(\d+)", re.I)

_WEEK_RANGE_RE = re.compile(r"(\d{1,2})\s*[-~—至]\s*(\d{1,2})\s*[（(\[]?\s*周")
_WEEK_SINGLE_RE = re.compile(r"(\d{1,2})\s*[（(\[]?\s*周")
# "2,4,6,8,10,12(双周)" / "1,3,5,7周" 这类顿号枚举写法
_WEEK_LIST_RE = re.compile(r"\d{1,2}(?:\s*[,，、]\s*\d{1,2})+\s*[（(\[]?\s*[单双]?\s*周")
_WEEK_ODD_RE = re.compile(r"[（(]\s*单\s*[)）]|单周")
_WEEK_EVEN_RE = re.compile(r"[（(]\s*双\s*[)）]|双周")
_SESS_RANGE_RE = re.compile(r"(\d{1,2})\s*[-~—至]\s*(\d{1,2})\s*节")
_SESS_SINGLE_RE = re.compile(r"(\d{1,2})\s*节")
_TIME_RANGE_RE = re.compile(r"(\d{1,2}:\d{2})\s*[-~—至]\s*(\d{1,2}:\d{2})")

_CN_NUM = {"一": 1, "二": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8,
           "九": 9, "十": 10, "十一": 11, "十二": 12}

_ROOM_HINTS = ("室", "楼", "馆", "场", "机房", "实验", "中心", "校区", "号", "阶梯")
_ROOM_SHAPE_RE = re.compile(r"^[A-Za-z]?\d{1,3}([-#栋号]\d{0,4})?[A-Za-z]?$")

# 正方教务 xskb 课表单元格：
#   <div class="kbcontent1">课程名<br/><font title='周次(节次)'>1-8(周)</font>
#        <br/><font title='教室'>2207</font></div>                      ← 简版（界面默认显示）
#   <div class="kbcontent" style="display:none">课程名<br/><font title='老师'>王昊</font>
#        <br/><font title='周次(节次)'>1-8(周)[01-02节]</font>
#        <br/><font title='教室'>2207</font></div>                      ← 完整版（含老师/节次）
# 同一格的简版与完整版内容重复，必须二选一，否则字段会串行错位。
_KB_DIV_RE = re.compile(r"<div\b([^>]*)>(.*?)</div>", re.I | re.S)
_KB_CLASS_RE = re.compile(r"class\s*=\s*[\"']([^\"']*)[\"']", re.I)
_KB_SEP_RE = re.compile(r"-{3,}")
_FONT_TITLE_RE = re.compile(r"<font\b[^>]*title\s*=\s*[\"']([^\"']*)[\"'][^>]*>(.*?)</font>",
                            re.I | re.S)
_KB_SESS_BRACKET_RE = re.compile(r"[\[【（(]([^\]】)）]*节[^\]】)）]*)[\]】)）]")


# --------------------------------------------------------------------------- #
# 文本清洗
# --------------------------------------------------------------------------- #
def _plain(fragment: str) -> str:
    """HTML 片段 → 单行纯文本。"""
    text = _HR_RE.sub(" ", fragment)
    text = _BR_RE.sub(" ", text)
    text = _BLOCK_RE.sub(" ", text)
    text = _TAG_RE.sub("", text)
    text = _html.unescape(text).replace("\xa0", " ")
    return re.sub(r"\s+", " ", text).strip()


def html_cell_to_segments(fragment: str) -> List[List[str]]:
    """HTML 单元格 → 段落列表；<hr> 视为不同课程的硬分隔，<br>/<p> 视为换行。"""
    text = _HR_RE.sub(_SEP_TOKEN, fragment)
    text = _BR_RE.sub("\n", text)
    text = _BLOCK_RE.sub("\n", text)
    text = _TAG_RE.sub("", text)
    text = _html.unescape(text).replace("\xa0", " ")
    return _lines_to_segments(text.replace(_SEP_TOKEN, "\n" + _SEP_TOKEN + "\n").split("\n"))


def text_to_segments(text: str) -> List[List[str]]:
    """纯文本单元格 → 段落列表（同时支持 <hr> 与 --- 分隔线）。"""
    if text is None:
        return []
    text = str(text)
    if "<" in text and ">" in text:
        return html_cell_to_segments(text)
    return _lines_to_segments(str(text).split("\n"))


def _lines_to_segments(lines: Sequence[str]) -> List[List[str]]:
    segments: List[List[str]] = []
    current: List[str] = []
    seen_week = False
    for raw in lines:
        line = str(raw).replace("\xa0", " ").strip()
        if not line:
            continue
        if _SEP_TOKEN in line or re.fullmatch(r"[-—=*_·]{3,}", line):
            if current:
                segments.append(current)
                current = []
            seen_week = False
            continue
        # 一个单元格里塞了两门课（常见于教务系统课表）：第二个"周次"行视为新课程起点
        if seen_week and parse_weeks(line):
            segments.append(current)
            current = []
            seen_week = False
        current.append(line)
        if parse_weeks(line):
            seen_week = True
    if current:
        segments.append(current)
    return segments


def _join(cell: Optional[Sequence[Any]]) -> str:
    """把单元格（可能是"行列表"或"段落列表"）拍平成一整行文本。"""
    if not cell:
        return ""
    parts: List[str] = []
    for item in cell:
        if isinstance(item, (list, tuple)):
            parts.extend(str(x) for x in item)
        else:
            parts.append(str(item))
    return " ".join(p for p in parts if p)


def _first_line(cell: Optional[Sequence[Any]]) -> str:
    """取单元格的第一行非空文本（用于识别表头）。"""
    if not cell:
        return ""
    for item in cell:
        if isinstance(item, (list, tuple)):
            for x in item:
                if str(x).strip():
                    return str(x).strip()
        elif str(item).strip():
            return str(item).strip()
    return ""


# --------------------------------------------------------------------------- #
# 语义解析
# --------------------------------------------------------------------------- #
def parse_weeks(text: str) -> List[int]:
    """从"1-16周(单)"这类文本中解析出具体周次列表。"""
    if not text:
        return []
    weeks = set()
    parity = None
    if _WEEK_ODD_RE.search(text):
        parity = "odd"
    elif _WEEK_EVEN_RE.search(text):
        parity = "even"
    for m in _WEEK_RANGE_RE.finditer(text):
        a, b = int(m.group(1)), int(m.group(2))
        if a > b:
            a, b = b, a
        weeks.update(range(a, b + 1))
    if not weeks:
        for m in _WEEK_LIST_RE.finditer(text):
            weeks.update(int(x) for x in re.findall(r"\d{1,2}", m.group(0)))
    if not weeks:
        for m in _WEEK_SINGLE_RE.finditer(text):
            weeks.add(int(m.group(1)))
    if parity == "odd":
        weeks = {w for w in weeks if w % 2 == 1}
    elif parity == "even":
        weeks = {w for w in weeks if w % 2 == 0}
    return sorted(w for w in weeks if 1 <= w <= 60)


def _weeks_note(text: str) -> str:
    text = text or ""
    m = re.search(r"\d{1,2}\s*[-~—至]?\s*\d{0,2}\s*[（(\[]?\s*周[^)）]*[)）]?", text)
    if m:
        return m.group(0).strip()
    m = re.search(r"(?:\d{1,2}\s*[,，、]\s*)+\d{1,2}\s*[（(\[]?\s*[单双]?周[^)）]*[)）]?", text)
    return m.group(0).strip() if m else ""


def parse_sessions(text: str) -> List[int]:
    """从"(1-2节)"/"第3-4节"中解析小节序号。"""
    if not text:
        return []
    out = set()
    for m in _SESS_RANGE_RE.finditer(text):
        a, b = int(m.group(1)), int(m.group(2))
        if a > b:
            a, b = b, a
        out.update(range(a, b + 1))
    if not out:
        for m in _SESS_SINGLE_RE.finditer(text):
            out.add(int(m.group(1)))
    return sorted(s for s in out if 1 <= s <= 20)


def _cn_big_sessions(text: str) -> List[int]:
    """解析"第一大节"/"第三节"这类中文节次标签。"""
    if not text:
        return []
    if "大节" in text:
        for cn, num in _CN_NUM.items():
            if f"第{cn}大节" in text:
                return [2 * num - 1, 2 * num]
    for cn, num in _CN_NUM.items():
        if f"第{cn}节" in text:
            return [num]
    return []


def weekday_from_text(text: str) -> Optional[int]:
    """表头/单元格文本 → 星期几(1-7)。"""
    if not text:
        return None
    text = text.strip()
    for i, name in enumerate(WEEKDAY_CN, start=1):
        if name in text or name.replace("星期", "周") in text:
            return i
    m = re.search(r"周\s*([一二三四五六日天])", text)
    if m:
        ch = m.group(1)
        return 7 if ch in ("日", "天") else "一二三四五六".index(ch) + 1
    m = re.search(r"星期\s*([1-7])", text)
    if m:
        return int(m.group(1))
    if re.fullmatch(r"[1-7]", text):
        return int(text)
    return None


def looks_like_room(text: str) -> bool:
    if not text:
        return False
    if any(h in text for h in _ROOM_HINTS):
        return True
    if _ROOM_SHAPE_RE.match(text.strip()):
        return True
    # "教1-101" / "B305" / "3-201" 这类含数字的短文本，基本可认定是教室
    if re.search(r"\d", text) and not re.search(r"[周节时天]", text):
        return True
    return False


def _split_time_range(start: str, end: str, count: int) -> List[List[str]]:
    """把一个大节的时间段按覆盖的小节数均分（如 "1,2节 08:00-09:40" → 2 段）。"""
    if count <= 1:
        return [[start, end]]

    def to_min(text: str) -> int:
        hh, mm = text.split(":")
        return int(hh) * 60 + int(mm)

    def to_str(value: int) -> str:
        return f"{value // 60:02d}:{value % 60:02d}"

    begin, finish = to_min(start), to_min(end)
    step = (finish - begin) / float(count)
    out: List[List[str]] = []
    for k in range(count):
        out.append([to_str(begin + int(round(step * k))),
                    to_str(begin + int(round(step * (k + 1))))])
    return out


def session_times_from_html(html: str) -> Dict[int, List[str]]:
    """若课表表格左侧给出了每一小节的起止时间，抽取出来供界面使用。

    强智/正方界面常按"大节"标注（如 "1,2节 08:00-09:40"），此处按覆盖的小节数
    均分展开为逐小节时间；无小节号的表按出现顺序编号。
    """
    for table in _TABLE_RE.findall(html or ""):
        grid = _table_to_grid(table)
        if not grid:
            continue
        result: Dict[int, List[str]] = {}
        order = 0
        for row in grid:
            if not row:
                continue
            label = _plain(row[0].get("html", ""))
            times = _TIME_RANGE_RE.findall(label)
            if not times:
                continue
            order += 1
            start, end = times[0][0], times[0][1]
            head = label.split(start)[0]
            nums = _expand_num_ranges(head) or [order]
            for num, pair in zip(nums, _split_time_range(start, end, len(nums))):
                if 1 <= num <= 20:
                    result[num] = pair
        if result:
            return result
    return result


# --------------------------------------------------------------------------- #
# 单元格 → 课程
# --------------------------------------------------------------------------- #
def courses_from_cell(segments: List[List[str]], weekday: int,
                      default_sessions: Sequence[int]) -> List[Dict[str, Any]]:
    courses: List[Dict[str, Any]] = []
    for lines in segments or []:
        lines = [ln.strip() for ln in lines if ln and ln.strip()]
        if not lines:
            continue
        week_idx: Optional[int] = None
        week_text = ""
        for i, line in enumerate(lines):
            if parse_weeks(line):
                week_idx, week_text = i, line
                break
        name = lines[0]
        if week_idx == 0:
            name = re.sub(r"[（(]?[\d,\-\s~—至]*周[^)）]*[)）]?", "", name).strip(" ,，")
        if not name:
            name = lines[0]
        rest = [ln for i, ln in enumerate(lines) if i not in (0, week_idx)]
        room, teacher = "", ""
        for line in rest:
            parts = [p for p in line.split() if p]
            if len(parts) > 1 and any(looks_like_room(p) for p in parts):
                for part in parts:                       # "张三 教1-101" → 教师/教室分开
                    if not room and looks_like_room(part):
                        room = part
                    elif not teacher and part != room:
                        teacher = part
                continue
            if not room and looks_like_room(line):
                room = line
            elif not teacher and line != room:
                teacher = line
        sessions = parse_sessions(week_text) or list(default_sessions)
        courses.append({
            "name": name,
            "teacher": teacher,
            "room": room,
            "weeks": parse_weeks(week_text),
            "week_raw": _weeks_note(week_text),
            "weekday": weekday,
            "sessions": sessions,
            "raw": " / ".join(lines),
        })
    return courses


def _expand_num_ranges(text: str) -> List[int]:
    """把 "05-06-07-08" / "1-4,6" 这类节次串展开成小节号列表。"""
    out: set = set()
    for m in re.finditer(r"(\d{1,2})\s*[-~—至]\s*(\d{1,2})", text or ""):
        a, b = int(m.group(1)), int(m.group(2))
        if a > b:
            a, b = b, a
        out.update(range(a, b + 1))
    for m in re.finditer(r"\d{1,2}", text or ""):
        out.add(int(m.group(0)))
    return sorted(n for n in out if 1 <= n <= 20)


def _kb_sessions(week_text: str, default_sessions: Sequence[int]) -> List[int]:
    """"[05-06-07-08节]" → [5,6,7,8]；无括号节次时用表格行标签推断的值。"""
    m = _KB_SESS_BRACKET_RE.search(week_text or "")
    if m:
        got = _expand_num_ranges(m.group(1))
        if got:
            return got
    return list(default_sessions)


def kb_courses_from_cell(fragment: str, weekday: int,
                         default_sessions: Sequence[int]) -> List[Dict[str, Any]]:
    """正方教务课表单元格（div.kbcontent / div.kbcontent1）→ 课程列表。

    同一格可含多门课，用 "-------" 分隔；优先使用含老师/节次的完整版
    div.kbcontent，仅在整格都只有简版时才退化用 div.kbcontent1。
    结构与纯文本导入格式无关，因此只有能识别出 `<font title=...>` 标记时才接管，
    否则返回空列表让上层走通用解析。
    """
    if not fragment or "kbcontent" not in fragment:
        return []
    full: List[str] = []
    brief: List[str] = []
    for attrs, inner in _KB_DIV_RE.findall(fragment):
        m = _KB_CLASS_RE.search(attrs)
        cls = (m.group(1) or "").lower() if m else ""
        if "kbcontent1" in cls:
            brief.append(inner)
        elif "kbcontent" in cls:
            full.append(inner)
    picked = full or brief
    if not picked:
        return []

    courses: List[Dict[str, Any]] = []
    for inner in picked:
        for block in _KB_SEP_RE.split(inner):
            if not block.strip():
                continue
            fields: Dict[str, str] = {}
            for title, text in _FONT_TITLE_RE.findall(block):
                key = _plain(title)
                value = _plain(text)
                if key and value and key not in fields:
                    fields[key] = value
            name = _plain(_FONT_TITLE_RE.sub(" ", block)).strip(" -")
            if not name or not any(s and s.strip() and s != "&nbsp;" for s in [name]):
                continue
            if not re.search(r"[\u4e00-\u9fa5A-Za-z]", name):
                continue
            week_text = next((v for k, v in fields.items() if "周" in k), "")
            room = next((v for k, v in fields.items()
                         if "教室" in k or "地点" in k or "场地" in k), "")
            teacher = next((v for k, v in fields.items()
                            if "老师" in k or "教师" in k or "任课" in k), "")
            courses.append({
                "name": name,
                "teacher": teacher,
                "room": room,
                "weeks": parse_weeks(week_text),
                "week_raw": _weeks_note(week_text),
                "weekday": weekday,
                "sessions": _kb_sessions(week_text, default_sessions),
                "raw": " / ".join(p for p in (name, teacher, week_text, room) if p),
            })
    return courses


# --------------------------------------------------------------------------- #
# 表格网格（支持 rowspan / colspan）
# --------------------------------------------------------------------------- #
def _table_to_grid(table_html: str) -> List[List[Dict[str, Any]]]:
    raw_rows = []
    for tr in _TR_RE.findall(table_html):
        cells = []
        for m in _TD_RE.finditer(tr):
            attrs, inner = m.group(1), m.group(2)
            rs = _ROWSPAN_RE.search(attrs)
            cs = _COLSPAN_RE.search(attrs)
            cells.append({
                "html": inner,
                "rowspan": int(rs.group(1)) if rs else 1,
                "colspan": int(cs.group(1)) if cs else 1,
            })
        if cells:
            raw_rows.append(cells)

    placed: Dict[Any, Dict[str, Any]] = {}
    max_col = 0
    for r, cells in enumerate(raw_rows):
        c = 0
        for cell in cells:
            while (r, c) in placed:
                c += 1
            for dr in range(cell["rowspan"]):
                for dc in range(cell["colspan"]):
                    if dr == 0 and dc == 0:
                        placed[(r + dr, c + dc)] = dict(cell)
                    else:
                        placed[(r + dr, c + dc)] = {"html": "", "rowspan": 1,
                                                    "colspan": 1, "spanned": True}
            c += cell["colspan"]
            max_col = max(max_col, c)
    if not placed:
        return []
    max_row = max(k[0] for k in placed)
    return [[placed.get((r, c), {"html": "", "rowspan": 1, "colspan": 1})
             for c in range(max_col)] for r in range(max_row + 1)]


def _grid_span(grid: List[List[Dict[str, Any]]]) -> List[List[int]]:
    return [[int(cell.get("rowspan", 1) or 1) for cell in row] for row in grid]


# --------------------------------------------------------------------------- #
# 网格 → 课程
# --------------------------------------------------------------------------- #
def _build_from_grid(seg_grid: List[List[Optional[List[List[str]]]]],
                     span_grid: List[List[int]],
                     header_idx: int,
                     raw_grid: Optional[List[List[str]]] = None) -> List[Dict[str, Any]]:
    header = seg_grid[header_idx]
    col_weekday: Dict[int, int] = {}
    for c, cell in enumerate(header):
        wd = weekday_from_text(_first_line(cell)) if cell else None
        if wd:
            col_weekday[c] = wd
    if not col_weekday:
        return []

    data_start = header_idx + 1
    data_count = len(seg_grid) - data_start
    small_rows = data_count >= 9  # 行数多 => 每行一小节，否则每行一大节(2小节)

    def positional(idx: int) -> List[int]:
        if small_rows:
            return [idx + 1]
        return [2 * idx + 1, 2 * idx + 2]

    courses: List[Dict[str, Any]] = []
    for i in range(data_start, len(seg_grid)):
        idx = i - data_start
        row = seg_grid[i]
        raw_row = raw_grid[i] if raw_grid and i < len(raw_grid) else None
        label = _join(row[0]) if row else ""
        _time_m = _TIME_RANGE_RE.search(label)
        _head = label.split(_time_m.group(0))[0] if _time_m else label
        sess_here = (_expand_num_ranges(_head) or parse_sessions(_head)
                     or _cn_big_sessions(label) or positional(idx))
        for c, wd in col_weekday.items():
            if c >= len(row):
                continue
            cell = row[c] or []
            raw = (raw_row[c] if raw_row and c < len(raw_row) else "") or ""
            if not cell and not raw:
                continue
            span = span_grid[i][c] if i < len(span_grid) and c < len(span_grid[i]) else 1
            if span > 1:
                sess = sorted({s for k in range(idx, idx + span) for s in positional(k)})
                if any(parse_sessions(_join(cell)) for _ in [0]):
                    sess = parse_sessions(_join(cell))
            else:
                sess = sess_here
            # 正方教务单元格优先：按 div/font 语义取字段，避免简版/完整版重复导致错位
            kb_courses = kb_courses_from_cell(raw, wd, sess) if raw else []
            if kb_courses:
                courses.extend(kb_courses)
            else:
                courses.extend(courses_from_cell(cell, wd, sess))
    return merge_consecutive(courses)


def merge_consecutive(courses: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """同一门课被拆成相邻小节时（合并单元格/多次出现）合并为一条。"""
    buckets: Dict[Any, Dict[str, Any]] = {}
    order: List[Any] = []
    for course in courses:
        key = (course.get("name"), course.get("weekday"), course.get("room"),
               course.get("teacher"), tuple(course.get("weeks") or []))
        if key not in buckets:
            buckets[key] = dict(course)
            buckets[key]["sessions"] = list(course.get("sessions") or [])
            order.append(key)
        else:
            merged = set(buckets[key]["sessions"]) | set(course.get("sessions") or [])
            buckets[key]["sessions"] = sorted(merged)
    return [buckets[k] for k in order]


def _find_header_row(grid_seg: List[List[Optional[List[List[str]]]]]) -> Optional[int]:
    for i, row in enumerate(grid_seg):
        for cell in row:
            if cell and weekday_from_text(_first_line(cell)):
                return i
    return None


# --------------------------------------------------------------------------- #
# 对外接口
# --------------------------------------------------------------------------- #
def parse_html(html: str) -> Dict[str, Any]:
    """解析课表页面 HTML。"""
    tables = _TABLE_RE.findall(html or "")
    if not tables:
        return {"courses": [], "meta": {"error": "页面中未找到任何表格"}}

    tables.sort(key=lambda t: 0 if re.search(r"kbtable", t, re.I) else 1)
    fallback_meta: Dict[str, Any] = {}
    for table in tables:
        grid = _table_to_grid(table)
        if len(grid) < 2:
            continue
        seg_grid: List[List[Optional[List[List[str]]]]] = []
        raw_grid: List[List[str]] = []
        for row in grid:
            seg_row: List[Optional[List[List[str]]]] = []
            raw_row: List[str] = []
            # 跳过"备注/说明"这类跨列合并的脚注行（不是课表数据）
            first = row[0] if row else {}
            skip_row = (int(first.get("colspan", 1) or 1) > 1
                        or bool(re.match(r"^(备注|注[:：]|说明)", _plain(first.get("html", "")))))
            for cell in row:
                if skip_row or cell.get("spanned"):
                    seg_row.append(None)
                    raw_row.append("")
                else:
                    seg_row.append(html_cell_to_segments(cell.get("html", "")))
                    raw_row.append(cell.get("html", "") or "")
            seg_grid.append(seg_row)
            raw_grid.append(raw_row)
        header_idx = _find_header_row(seg_grid)
        if header_idx is None:
            continue
        courses = _build_from_grid(seg_grid, _grid_span(grid), header_idx, raw_grid)
        if not courses:
            continue
        weeks = [w for c in courses for w in (c.get("weeks") or [])]
        meta = {
            "tables": len(tables),
            "header_row": header_idx,
            "session_times": session_times_from_html(html),
            "min_week": min(weeks) if weeks else None,
            "max_week": max(weeks) if weeks else None,
            "columns": [_plain(c.get("html", "")) for c in grid[header_idx]],
        }
        return {"courses": courses, "meta": meta}
    return {"courses": [], "meta": fallback_meta or {"error": "未识别到课表表格结构"}}


def table_rows_to_courses(rows: Sequence[Sequence[Any]]) -> Dict[str, Any]:
    """二维表（Excel / CSV 已切分）→ 课程列表。"""
    seg_grid: List[List[Optional[List[List[str]]]]] = []
    span_grid: List[List[int]] = []
    for row in rows:
        seg_row: List[Optional[List[List[str]]]] = []
        span_row: List[int] = []
        for cell in row:
            seg_row.append(text_to_segments(cell) if cell not in (None, "") else None)
            span_row.append(1)
        seg_grid.append(seg_row)
        span_grid.append(span_row)
    header_idx = _find_header_row(seg_grid)
    if header_idx is None:
        return {"courses": [], "meta": {"error": "未找到包含\"星期\"的表头行"}}
    courses = _build_from_grid(seg_grid, span_grid, header_idx)
    weeks = [w for c in courses for w in (c.get("weeks") or [])]

    # 从首列的"第一大节 08:00-09:40"这类标签中抽取作息时间
    data_rows = list(rows[header_idx + 1:])
    small_rows = len(data_rows) >= 9
    times: Dict[int, List[str]] = {}
    for i, row in enumerate(data_rows):
        if not row:
            continue
        label = re.sub(r"\s+", " ", str(row[0] or ""))
        m = _TIME_RANGE_RE.search(label)
        if not m:
            continue
        sess = parse_sessions(label) or _cn_big_sessions(label)
        if not sess:
            sess = [i + 1] if small_rows else [2 * i + 1, 2 * i + 2]
        for s in sess:
            times[s] = [m.group(1), m.group(2)]

    return {"courses": courses, "meta": {
        "header_row": header_idx,
        "min_week": min(weeks) if weeks else None,
        "max_week": max(weeks) if weeks else None,
        "session_times": times,
    }}


# ------------------------------ 纯文本解析 --------------------------------- #
_COLUMN_KEYS = {
    "name": ("课程", "科目", "名称", "课程名"),
    "teacher": ("教师", "老师", "任课", "授课"),
    "room": ("教室", "地点", "场地", "上课地"),
    "weeks": ("周次", "周数", "上课周"),
    "weekday": ("星期", "周几", "上课星期"),
    "sessions": ("节次", "节数", "上课节", "时间"),
}


def _split_columns(line: str) -> List[str]:
    for sep in ("\t", "|", ";", "；"):
        if sep in line:
            return [p.strip() for p in line.split(sep)]
    if "," in line or "，" in line:
        return [p.strip() for p in re.split(r"[,，]", line)]
    if re.search(r"\s{2,}", line):
        return [p.strip() for p in re.split(r"\s{2,}", line)]
    return [line.strip()]


def _map_header(cols: Sequence[str]) -> Dict[int, str]:
    mapping: Dict[int, str] = {}
    for i, col in enumerate(cols):
        text = col.strip()
        for field, keys in _COLUMN_KEYS.items():
            if any(k in text for k in keys):
                mapping[i] = field
                break
    return mapping


def _courses_from_columns(cols: Sequence[str], header_map: Optional[Dict[int, str]],
                          state: Dict[str, Any]) -> List[Dict[str, Any]]:
    fields: Dict[str, str] = {}
    leftovers: List[str] = []
    if header_map:
        for i, col in enumerate(cols):
            field = header_map.get(i)
            if field:
                fields[field] = col
            elif col:
                leftovers.append(col)
    else:
        for col in cols:
            if "weeks" not in fields and parse_weeks(col):
                fields["weeks"] = col
            elif "weekday" not in fields and weekday_from_text(col):
                fields["weekday"] = col
            elif "sessions" not in fields and (parse_sessions(col) or "节" in col):
                fields["sessions"] = col
            else:
                leftovers.append(col)

    name = fields.get("name", "").strip()
    for i, col in enumerate(leftovers):
        if not name:
            name = col.strip()
            leftovers.pop(i)
            break
    room, teacher = fields.get("room", "").strip(), fields.get("teacher", "").strip()
    for col in leftovers:
        if not room and looks_like_room(col):
            room = col
        elif not teacher:
            teacher = col
        elif not room:
            room = col

    weekday = weekday_from_text(fields.get("weekday", "")) if fields.get("weekday") else None
    sessions = parse_sessions(fields.get("sessions", "")) if fields.get("sessions") else []
    if not sessions and fields.get("sessions"):
        sessions = _cn_big_sessions(fields["sessions"])
    weeks = parse_weeks(fields.get("weeks", "")) if fields.get("weeks") else []

    if not name:
        name = state.get("name", "")
    if weekday is None:
        weekday = state.get("weekday")
    if not sessions:
        sessions = list(state.get("sessions") or [])
    if not weeks:
        weeks = list(state.get("weeks") or [])

    if name:
        state["name"] = name
    if weekday:
        state["weekday"] = weekday
    if sessions:
        state["sessions"] = sessions
    if weeks:
        state["weeks"] = weeks

    if not name or not weekday or not sessions:
        return []
    return [{
        "name": name.strip(),
        "teacher": teacher.strip(),
        "room": room.strip(),
        "weeks": weeks,
        "week_raw": _weeks_note(fields.get("weeks", "")),
        "weekday": weekday,
        "sessions": sorted(sessions),
        "raw": " ".join(cols).strip(),
    }]


def _parse_loose_line(line: str) -> List[Dict[str, Any]]:
    weekday = weekday_from_text(line)
    weeks = parse_weeks(line)
    sessions = parse_sessions(line)
    residual = line
    for pattern in (_WEEK_RANGE_RE, _WEEK_SINGLE_RE, _SESS_RANGE_RE, _SESS_SINGLE_RE,
                    re.compile(r"星期[一二三四五六日天\d]"), re.compile(r"周[一二三四五六日天]")):
        residual = pattern.sub(" ", residual)
    tokens = [t for t in re.split(r"[\s,，;；|]+", residual) if t.strip()]
    if not tokens or not weekday or not sessions:
        return []
    name = tokens[0]
    rest = tokens[1:]
    room, teacher = "", ""
    for token in rest:
        if not room and looks_like_room(token):
            room = token
        elif not teacher:
            teacher = token
    return [{
        "name": name,
        "teacher": teacher,
        "room": room,
        "weeks": weeks,
        "week_raw": _weeks_note(line),
        "weekday": weekday,
        "sessions": sorted(sessions),
        "raw": line.strip(),
    }]


def _text_rows(text: str, lines: Sequence[str]) -> List[List[str]]:
    """把粘贴文本切成"行 × 单元格"。

    制表符分隔时走 csv 解析，可正确处理单元格内含换行（Excel 复制到剪贴板会把这类
    单元格用双引号包起来）；否则按每行拆分。
    """
    if "\t" in text:
        try:
            rows = [list(r) for r in csv.reader(io.StringIO(text), delimiter="\t")]
        except Exception:
            rows = [_split_columns(ln) for ln in lines]
    else:
        rows = [_split_columns(ln) for ln in lines]
    return [[(c or "").strip() for c in row] for row in rows
            if any((c or "").strip() for c in row)]


def _grid_rows_by_position(lines: Sequence[str]) -> Tuple[List[List[str]], Dict[int, int]]:
    """按"星期X"表头推断列范围，再把每段文字按实际位置归入所在列。

    适用于用空格/制表位手工对齐、列宽不固定的课表文本（按分隔符切列会错位）。
    返回 (行 × 单元格, 列序号 → 星期)。
    """
    header = lines[0]
    marks = sorted((m.start(), weekday_from_text(m.group(0)))
                   for m in re.finditer(r"星期[一二三四五六日天]", header))
    marks = [(pos, wd) for pos, wd in marks if wd]
    if len(marks) < 2:
        return [], {}

    spans: List[Tuple[int, Optional[int]]] = [(0, marks[0][0])]     # 首列：节次/时间
    for i, (pos, _) in enumerate(marks):
        spans.append((pos, marks[i + 1][0] if i + 1 < len(marks) else None))
    col_weekdays = {i + 1: wd for i, (_, wd) in enumerate(marks)}

    rows: List[List[str]] = []
    for line in lines[1:]:
        cells = [""] * (len(marks) + 1)
        for m in re.finditer(r"\S+", line):
            token, start, end = m.group(0), m.start(), m.end()
            best, best_overlap = 0, 0
            for idx, (span_start, span_end) in enumerate(spans):
                hi = span_end if span_end is not None else len(line)
                overlap = min(hi, end) - max(span_start, start)
                if overlap > best_overlap:
                    best, best_overlap = idx, overlap
            cells[best] = (cells[best] + " " + token) if cells[best] else token
        if any(cells):
            rows.append(cells)
    return rows, col_weekdays


def _courses_from_text_grid(rows: Sequence[Sequence[str]],
                            col_weekdays: Dict[int, int]) -> Tuple[List[Dict[str, Any]],
                                                                  Dict[int, List[str]]]:
    """表格型纯文本（每行一个"节次"，每列一个星期）→ 课程列表 + 作息时间。

    支持"单元格内容换行"的粘贴形式：首列为空的行视为上一行的续行，按列追加内容。
    """
    width = max([len(r) for r in rows] + [max(col_weekdays) + 1 if col_weekdays else 0])
    label_cols = [i for i in range(width) if i not in col_weekdays]

    logical: List[List[str]] = []          # [[label, cell1, cell2, ...], ...]
    for row in rows:
        cells = list(row) + [""] * (width - len(row))
        if logical and not cells[0].strip() and len(row) >= 2:
            prev = logical[-1]
            for i, cell in enumerate(cells[:width]):
                if cell:
                    prev[i] = (prev[i] + "\n" + cell) if prev[i] else cell
            continue
        logical.append(cells[:width])

    courses: List[Dict[str, Any]] = []
    times: Dict[int, List[str]] = {}
    for row_idx, cells in enumerate(logical):
        label = " ".join(cells[i] for i in label_cols if i < len(cells)).strip()
        sessions = (parse_sessions(label) or _cn_big_sessions(label)
                    or [2 * row_idx + 1, 2 * row_idx + 2])
        m = _TIME_RANGE_RE.search(label)
        if m:
            for s in sessions:
                times[s] = [m.group(1), m.group(2)]
        for col_idx, weekday in col_weekdays.items():
            if col_idx >= len(cells):
                continue
            cell = cells[col_idx].strip()
            if not cell:
                continue
            courses.extend(courses_from_cell(text_to_segments(cell), weekday, sessions))
    return courses, times


def parse_text(text: str) -> Dict[str, Any]:
    """解析粘贴的课表文本（支持逗号/制表符/竖线/空格分隔，或宽松单行）。"""
    lines = [ln.strip() for ln in (text or "").splitlines()]
    lines = [ln for ln in lines if ln]
    if not lines:
        return {"courses": [], "meta": {"error": "内容为空"}}

    # --- 表格型文本：首行形如"节次 / 星期一 / 星期二 ..."（从教务系统网页课表直接复制最常见）---
    text_rows = _text_rows(text, lines)
    col_weekdays: Dict[int, int] = {}
    if text_rows:
        for i, col in enumerate(text_rows[0]):
            wd = weekday_from_text(col)
            if wd:
                col_weekdays[i] = wd
    body_rows: List[List[str]] = text_rows[1:] if text_rows else []
    if len(lines) >= 2 and not re.search(r"[\t,，;；|]", text):
        pos_rows, pos_weekdays = _grid_rows_by_position(lines)
        if pos_weekdays and any(any(c for c in row[1:]) for row in pos_rows):
            body_rows, col_weekdays = pos_rows, pos_weekdays

    if len(col_weekdays) >= 2:
        grid_courses, grid_times = _courses_from_text_grid(body_rows, col_weekdays)
        if grid_courses:
            grid_weeks = [w for c in grid_courses for w in (c.get("weeks") or [])]
            return {"courses": merge_consecutive(grid_courses),
                    "meta": {"grid": True, "rows": len(body_rows),
                             "session_times": grid_times,
                             "min_week": min(grid_weeks) if grid_weeks else None,
                             "max_week": max(grid_weeks) if grid_weeks else None}}

    header_map: Optional[Dict[int, str]] = None
    first = lines[0]
    if ("课程" in first or "科目" in first) and ("星期" in first or "周" in first):
        header_map = _map_header(_split_columns(first))
        lines = lines[1:]

    courses: List[Dict[str, Any]] = []
    state: Dict[str, Any] = {}
    for line in lines:
        cols = _split_columns(line)
        parsed = _courses_from_columns(cols, header_map, state) if len(cols) > 1 else []
        if not parsed:
            parsed = _parse_loose_line(line)
        if not parsed:
            parsed = _courses_from_columns(cols, header_map, state)
        courses.extend(parsed)
    return {"courses": merge_consecutive(courses),
            "meta": {"lines": len(lines), "header_map": header_map}}


# ------------------------------ 文件解析 ---------------------------------- #
def parse_file(path: str) -> Dict[str, Any]:
    ext = os.path.splitext(path)[1].lower()
    if ext == ".json":
        import json
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        if isinstance(data, dict) and isinstance(data.get("courses"), list):
            return {"courses": data["courses"], "meta": {"source": "json"},
                    "term": data.get("term", "")}
        if isinstance(data, list):
            return {"courses": data, "meta": {"source": "json"}}
        raise ValueError("JSON 文件中未找到 courses 字段")

    if ext in (".html", ".htm"):
        with open(path, "r", encoding="utf-8", errors="ignore") as fh:
            return parse_html(fh.read())

    if ext in (".txt", ".csv", ".tsv"):
        with open(path, "r", encoding="utf-8", errors="ignore") as fh:
            return parse_text(fh.read())

    if ext == ".xlsx":
        try:
            import openpyxl
        except ImportError as exc:
            raise ValueError("读取 .xlsx 需要 openpyxl，请执行：pip install openpyxl") from exc
        wb = openpyxl.load_workbook(path, data_only=True)
        best: Dict[str, Any] = {"courses": [], "meta": {}}
        for ws in wb.worksheets:
            merged = list(ws.merged_cells.ranges)

            def value_at(row: int, col: int) -> Any:
                v = ws.cell(row=row, column=col).value
                if v not in (None, ""):
                    return v
                for rng in merged:
                    if rng.min_row <= row <= rng.max_row and rng.min_col <= col <= rng.max_col:
                        return ws.cell(row=rng.min_row, column=rng.min_col).value
                return None

            rows = [[value_at(r, c) for c in range(1, ws.max_column + 1)]
                    for r in range(1, ws.max_row + 1)]
            result = table_rows_to_courses(rows)
            if len(result["courses"]) > len(best["courses"]):
                result["meta"]["sheet"] = ws.title
                best = result
        if not best["courses"]:
            raise ValueError("Excel 中未识别到课表（需要包含\"星期一\"等表头）")
        return best

    if ext == ".xls":
        raise ValueError("暂不支持旧版 .xls，请在 Excel 中另存为 .xlsx 或 .csv 后再导入")

    raise ValueError(f"暂不支持的文件类型：{ext}")


def summarize(courses: Sequence[Dict[str, Any]]) -> Dict[str, Any]:
    weeks = [w for c in courses for w in (c.get("weeks") or [])]
    return {
        "count": len(courses),
        "min_week": min(weeks) if weeks else None,
        "max_week": max(weeks) if weeks else None,
    }
