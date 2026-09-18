# -*- coding: utf-8 -*-
"""背景图选择器（自研，替代手机端不可用的 ``FileChooserListView``）。

为什么要换掉 Kivy 自带的文件选择器（主人截图里的 bug 根因）：
    1. 起始目录取 ``os.path.expanduser("~")``。python-for-android 环境里 HOME 未设置，
       expanduser 会"原样返回 ~"；接着 ``os.path.isdir("~")`` 为假，退回到 ``~``，
       FileChooser 内部 ``listdir("~")`` 抛 OSError 被吞掉后 ``files[:] = []`` ——
       于是列表只剩 Kivy 自己插的一条 "../" 返回项：界面大面积空白、只留一个 "." 行。
    2. 列头 Name / Size 是 Kivy 内置模板，Size 用 halign='right' 但没绑列宽，
       右对齐的边距永远对不上。
    3. 手机用户的心智是"从相册挑一张"，不是"浏览文件系统"。

本模块的做法：
    * 「从相册选择」（Android）：走系统相册 ``Intent.ACTION_GET_CONTENT``，
      用 ContentResolver 把选中的图复制进应用私有目录 —— 不需要任何存储权限，最稳。
    * 目录列表：扫描真实存在的相册目录（Pictures / DCIM / Download / 当前背景图所在目录 /
      应用私有目录…），缩略图 + 文件名 + 大小（大小右对齐到自己的列宽）。
    * 空态：写清"哪个位置没有图片、接下来点哪里"，绝不再是一片空白。
    * 排列顺序：图片在前、子文件夹在后；点文件夹进入，点图片选中，确定后回填。
"""

from __future__ import annotations

import hashlib
import os
from datetime import datetime
from typing import Any, Callable, Dict, List, Optional, Tuple

from kivy.clock import Clock
from kivy.graphics import Color, RoundedRectangle
from kivy.logger import Logger
from kivy.uix.behaviors import ButtonBehavior
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.image import Image
from kivy.uix.label import Label
from kivy.uix.popup import Popup
from kivy.uix.scrollview import ScrollView
from kivy.uix.widget import Widget
from kivy.utils import platform

import store
from tt_theme import THEME, rgba, u
from tt_views import Ctx, _label

IS_ANDROID = platform == "android"

IMAGE_EXTS = (".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif")
ROW_H = 56.0            # 列表行高（设计单位）
BAR_H = 38.0            # 工具条/按钮高度
LIST_CAP = 40           # 列表最多显示多少张图片
THUMB_PX = 96           # 缩略图边长（像素）
THUMB_BUDGET = 24       # 单次刷新最多现场生成多少张缩略图（防大相册卡住界面）
SCAN_CAP = 4000         # 单目录扫描上限（防超大目录卡死）
REQ_GALLERY = 0x9A11    # startActivityForResult 请求码


# --------------------------------------------------------------------------- #
# 目录 / 图片扫描（纯逻辑，可单独自检）
# --------------------------------------------------------------------------- #
def is_image(path: str) -> bool:
    return os.path.splitext(str(path or ""))[1].lower() in IMAGE_EXTS


def human_size(num: Any) -> str:
    try:
        size = float(num)
    except (TypeError, ValueError):
        return ""
    for unit in ("B", "KB", "MB", "GB"):
        if size < 1024 or unit == "GB":
            if unit == "B":
                return f"{int(size)} B"
            return f"{size:.1f} {unit}"
        size /= 1024.0
    return ""


def candidate_dirs(current: str = "", extra: Optional[List[str]] = None) -> List[str]:
    """可能放着图片的目录（只保留"真的存在"的），当前背景图所在目录排第一。

    ``expanduser`` 失败（Android 下返回 "~" 本身）的路径一律丢弃 —— 那正是当初
    列表空白的源头，不允许再进入候选集。
    """
    out: List[str] = []

    def add(path: Any) -> None:
        text = str(path or "").strip()
        if not text:
            return
        try:
            text = os.path.abspath(os.path.expanduser(text))
        except Exception:
            return
        if "~" in text:                     # expanduser 没展开成功（Android 常见）
            return
        if os.path.isdir(text) and text not in out:
            out.append(text)

    for path in (extra or []):
        add(path)
    if current:
        add(os.path.dirname(os.path.abspath(str(current))))
    if IS_ANDROID:
        for path in ("/storage/emulated/0/Pictures",
                     "/storage/emulated/0/Pictures/Screenshots",
                     "/storage/emulated/0/DCIM",
                     "/storage/emulated/0/DCIM/Camera",
                     "/storage/emulated/0/Download",
                     "/storage/emulated/0/Documents",
                     "/storage/emulated/0/Pictures/WeiXin",
                     "/storage/emulated/0/Android/media"):
            add(path)
    else:
        home = os.path.expanduser("~")
        for name in ("Pictures", "Pictures/Screenshots", "Desktop", "Downloads",
                     "OneDrive/Pictures", "OneDrive/Desktop", "OneDrive/图片", "图片"):
            add(os.path.join(home, name))
    add(store.data_dir())
    return out


def list_dir(path: str) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
    """列出目录下的（子目录, 图片）。图片按修改时间新→旧，子目录按名字。"""
    subdirs: List[Dict[str, Any]] = []
    images: List[Dict[str, Any]] = []
    try:
        with os.scandir(path) as it:
            for index, entry in enumerate(it):
                if index >= SCAN_CAP:
                    break
                try:
                    if entry.name.startswith("."):
                        continue
                    if entry.is_dir():
                        subdirs.append({"path": entry.path, "name": entry.name,
                                        "isdir": True, "size": 0, "mtime": 0.0})
                    elif entry.is_file(follow_symlinks=False) and is_image(entry.name):
                        stat = entry.stat()
                        images.append({"path": entry.path, "name": entry.name, "isdir": False,
                                       "size": int(stat.st_size), "mtime": float(stat.st_mtime)})
                except OSError:
                    continue
    except OSError as exc:
        Logger.warning(f"tt_bgpick: 读不到目录 {path}（{exc}）")
    subdirs.sort(key=lambda item: str(item["name"]).lower())
    images.sort(key=lambda item: item["mtime"], reverse=True)
    return subdirs, images


def scan_images(dirs: List[str], extra: Optional[List[str]] = None,
                limit: int = LIST_CAP) -> List[Dict[str, Any]]:
    """把多个目录里的图片汇总成一份列表（按修改时间新→旧，路径去重）。"""
    out: List[Dict[str, Any]] = []
    seen = set()
    for folder in list(extra or []) + list(dirs or []):
        if not folder or not os.path.isdir(folder):
            continue
        _, images = list_dir(folder)
        for item in images:
            key = os.path.abspath(item["path"]).lower()
            if key in seen:
                continue
            seen.add(key)
            out.append(item)
    out.sort(key=lambda item: item["mtime"], reverse=True)
    return out[:max(1, int(limit))]


# --------------------------------------------------------------------------- #
# 缩略图（Pillow，缓存到应用私有目录；失败返回 ""，界面回落到占位图标）
# --------------------------------------------------------------------------- #
def _thumb_dir() -> str:
    path = os.path.join(store.data_dir(), "thumbs")
    try:
        os.makedirs(path, exist_ok=True)
    except OSError:
        return ""
    return path


def thumb_for(path: str, size: int = THUMB_PX) -> str:
    """生成/复用缩略图；任何异常都只回 ""，绝不因为一张坏图影响整个选择器。"""
    folder = _thumb_dir()
    if not folder or not os.path.isfile(path):
        return ""
    try:
        from PIL import Image as PILImage
    except Exception:
        return ""
    try:
        stat = os.stat(path)
        key = hashlib.md5(f"{os.path.abspath(path)}|{int(stat.st_mtime)}|{size}"
                          f"|{int(stat.st_size)}".encode("utf-8")).hexdigest()[:16]
        out = os.path.join(folder, key + ".png")
        if os.path.isfile(out) and os.path.getsize(out) > 0:
            return out
        with PILImage.open(path) as image:
            image.draft("RGB", (size * 2, size * 2))     # JPEG 直接降采样解码，快很多
            image = image.convert("RGB")
            image.thumbnail((size, size))
            image.save(out, "PNG")
        return out if os.path.getsize(out) > 0 else ""
    except Exception as exc:
        Logger.warning(f"tt_bgpick: 缩略图生成失败 {os.path.basename(path)}（{exc}）")
        return ""


# --------------------------------------------------------------------------- #
# Android 相册（系统图库）与运行时权限
# --------------------------------------------------------------------------- #
def gallery_supported() -> bool:
    if not IS_ANDROID:
        return False
    try:
        import jnius  # noqa: F401
        from android import activity, mActivity  # noqa: F401
        return True
    except Exception:
        return False


def request_media_permissions(callback: Optional[Callable[..., Any]] = None) -> bool:
    """Android 13+/12- 读相册需要运行时授权；桌面端不需要，直接返回 False。"""
    if not IS_ANDROID:
        return False
    try:
        from android.permissions import request_permissions
    except Exception:
        return False
    try:
        request_permissions(["android.permission.READ_MEDIA_IMAGES",
                             "android.permission.READ_EXTERNAL_STORAGE"],
                            callback or (lambda *_: None))
        return True
    except Exception as exc:
        Logger.warning(f"tt_bgpick: 相册权限请求失败（{exc}）")
        return False


def _sniff_ext(path: str) -> str:
    """按文件头判断真实格式（相册回来的文件常常没有正确后缀）。"""
    try:
        with open(path, "rb") as fh:
            head = fh.read(16)
    except OSError:
        return ""
    if head.startswith(b"\x89PNG"):
        return ".png"
    if head.startswith(b"\xff\xd8"):
        return ".jpg"
    if head.startswith(b"GIF8"):
        return ".gif"
    if head[:4] == b"RIFF" and head[8:12] == b"WEBP":
        return ".webp"
    if head.startswith(b"BM"):
        return ".bmp"
    return ""


def copy_uri_to_private(uri: Any) -> str:
    """把相册返回的 content:// 图片复制进应用私有目录，返回落盘路径（失败返回 ""）。"""
    from jnius import autoclass, jarray
    from android import mActivity
    resolver = mActivity.getContentResolver()
    stream = resolver.openInputStream(uri)
    if stream is None:
        return ""
    dest = os.path.join(store.data_dir(),
                        f"bg_{datetime.now().strftime('%Y%m%d_%H%M%S')}.jpg")
    FileOutputStream = autoclass("java.io.FileOutputStream")
    out = FileOutputStream(dest)
    buffer = jarray("b")([0] * 65536)
    total = 0
    try:
        while True:
            read = stream.read(buffer)
            if read <= 0:
                break
            out.write(buffer, 0, read)
            total += int(read)
            if total > 32 * 1024 * 1024:        # 32MB 上限，防超大原图撑爆内存
                break
    finally:
        try:
            out.flush()
            out.close()
            stream.close()
        except Exception:
            pass
    try:
        if os.path.getsize(dest) <= 0:
            return ""
    except OSError:
        return ""
    real_ext = _sniff_ext(dest)
    if real_ext and real_ext != ".jpg":
        renamed = os.path.splitext(dest)[0] + real_ext
        try:
            os.replace(dest, renamed)
            dest = renamed
        except OSError:
            pass
    return dest


def pick_from_gallery(callback: Callable[[str, str], None]) -> bool:
    """调系统相册选图，回调 (复制后的路径, 错误信息)；不支持时返回 False。"""
    if not gallery_supported():
        return False
    try:
        from jnius import autoclass
        from android import activity, mActivity
        Intent = autoclass("android.content.Intent")
        intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.setType("image/*")
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        chooser = Intent.createChooser(intent, "选择背景图片")

        def on_result(request_code: int, result_code: int, data: Any) -> None:
            if request_code != REQ_GALLERY:
                return
            try:
                activity.unbind(on_activity_result=on_result)
            except Exception:
                pass
            if data is None or result_code != -1:      # RESULT_OK = -1
                callback("", "")                        # 主人自己取消了，不算错误
                return
            try:
                path = copy_uri_to_private(data.getData())
            except Exception as exc:
                Logger.warning(f"tt_bgpick: 相册图片导入失败（{exc}）")
                callback("", f"导入相册图片失败：{exc}")
                return
            callback(path, "" if path else "这张图片读不出来，换一张试试")

        activity.bind(on_activity_result=on_result)
        mActivity.startActivityForResult(chooser, REQ_GALLERY)
        return True
    except Exception as exc:
        Logger.warning(f"tt_bgpick: 打不开系统相册（{exc}）")
        return False


# --------------------------------------------------------------------------- #
# 选择器界面
# --------------------------------------------------------------------------- #
def _btn(text: str, ctx: Ctx, cb: Callable[[], None], width: float) -> Any:
    from kivy.uix.button import Button
    btn = Button(text=text, font_name=ctx.font, font_size=ctx.fs(11),
                 size_hint_x=None, size_hint_y=None, width=u(width), height=u(BAR_H),
                 pos_hint={"center_y": 0.5}, background_normal="",
                 background_color=rgba(THEME["panel3"]))
    btn.color = ctx.text_color("text")
    btn.bind(on_release=lambda *_: cb())
    return btn


class _IconBox(Widget):
    """缩略图的占位块：圆角色块 + 居中文字（无缩略图时用）。"""

    def __init__(self, text: str, ctx: Ctx, size: float = ROW_H - 16,
                 color: str = THEME["panel3"], fg: str = "sub", **kwargs):
        super().__init__(size_hint=(None, None), size=(u(size), u(size)), **kwargs)
        with self.canvas:
            Color(*rgba(color))
            self._rect = RoundedRectangle(radius=[u(6)], pos=self.pos, size=self.size)
        self.bind(pos=lambda *_: setattr(self._rect, "pos", self.pos),
                  size=lambda *_: setattr(self._rect, "size", self.size))
        self.add_widget(_label(text, ctx, 10, fg, halign="center"))


class _PickRow(ButtonBehavior, BoxLayout):
    """列表行：可点（ButtonBehavior），选中时高亮。"""

    def __init__(self, height: float = ROW_H, **kwargs):
        super().__init__(orientation="horizontal", size_hint_y=None, height=u(height),
                         spacing=u(8), padding=(u(6), u(4), u(8), u(4)), **kwargs)
        with self.canvas.before:
            self._bg_color = Color(*rgba(THEME["panel"], 0))
            self._bg_rect = RoundedRectangle(radius=[u(8)], pos=self.pos, size=self.size)
        self.bind(pos=lambda *_: setattr(self._bg_rect, "pos", self.pos),
                  size=lambda *_: setattr(self._bg_rect, "size", self.size))

    def set_selected(self, flag: bool) -> None:
        self._bg_color.rgba = rgba(THEME["accent_soft"] if flag else THEME["panel"],
                                   0.9 if flag else 0.0)


class _ImageRow(_PickRow):
    """图片行：缩略图 + 文件名（左）+ 大小（右对齐到自己的列宽）。"""

    def __init__(self, item: Dict[str, Any], ctx: Ctx,
                 on_pick: Callable[[Dict[str, Any]], None], thumb: str, **kwargs):
        super().__init__(**kwargs)
        self.item = item
        if thumb:
            self.add_widget(Image(source=thumb, nocache=True, size_hint=(None, None),
                                  size=(u(ROW_H - 16), u(ROW_H - 16)),
                                  allow_stretch=True, keep_ratio=True))
        else:
            ext = os.path.splitext(str(item.get("name") or ""))[1].lstrip(".").upper()
            self.add_widget(_IconBox((ext or "IMG")[:4], ctx))
        name = _label(str(item.get("name") or ""), ctx, 11.5)
        name.shorten = True
        name.shorten_from = "right"
        name.size_hint_x = 1
        self.add_widget(name)
        # 大小列：给定独立列宽 + text_size 绑定，右对齐的右边距才落得准
        size = _label(human_size(item.get("size")), ctx, 10, "dim", halign="right")
        size.size_hint_x = None
        size.width = u(64)
        self.add_widget(size)
        self.bind(on_release=lambda *_: on_pick(self.item))


class _DirRow(_PickRow):
    """子文件夹行：点一下进入该目录。"""

    def __init__(self, item: Dict[str, Any], ctx: Ctx,
                 on_enter: Callable[[Dict[str, Any]], None], **kwargs):
        super().__init__(**kwargs)
        self.item = item
        self.add_widget(_IconBox("夹", ctx, color=THEME["accent_soft"], fg="accent"))
        name = _label(str(item.get("name") or ""), ctx, 11.5)
        name.shorten = True
        name.shorten_from = "right"
        name.size_hint_x = 1
        self.add_widget(name)
        tail = _label("文件夹", ctx, 10, "dim", halign="right")
        tail.size_hint_x = None
        tail.width = u(64)
        self.add_widget(tail)
        self.bind(on_release=lambda *_: on_enter(self.item))


class BackgroundPickerDialog:
    """背景图选择器（设置页「选择」按钮唤起）。

    ``dir`` 为空字符串表示"相册汇总"（所有候选目录的图片合并，新的在前）；
    否则只列该目录下的图片与子文件夹。
    """

    def __init__(self, ctx: Ctx, current: str = "",
                 on_pick: Optional[Callable[[str], None]] = None,
                 on_close: Optional[Callable[[], None]] = None,
                 extra_dirs: Optional[List[str]] = None):
        self.ctx = ctx
        self.current = str(current or "")
        self.on_pick_cb = on_pick
        self.on_close_cb = on_close
        self.extra_dirs = [str(d) for d in (extra_dirs or []) if str(d or "").strip()]
        self.dir = ""
        self.items: List[Dict[str, Any]] = []
        self.subdirs: List[Dict[str, Any]] = []
        self.rows = 0
        self.selected = ""
        self.picked = ""
        self.thumbs = 0
        self._img_rows: List[Any] = []
        self._thumb_budget = THUMB_BUDGET
        self._build()

    # ------------------------------------------------------------------ #
    def _build(self) -> None:
        ctx = self.ctx
        self.popup = Popup(title="选择背景图片", size_hint=(0.94, 0.9),
                           title_font=ctx.font, title_size=ctx.fs(13), separator_height=0)
        try:
            # 关掉 Kivy 默认的深色底图与分隔线，改用下面的浅色圆角面板（与设置页一致）
            self.popup.background_color = (0, 0, 0, 0)
            self.popup.title_color = ctx.text_color("text")
        except Exception:
            pass
        box = BoxLayout(orientation="vertical", padding=u(10), spacing=u(8))
        with box.canvas.before:
            Color(*rgba(THEME["panel"], 0.99))
            panel = RoundedRectangle(radius=[u(12)], pos=box.pos, size=box.size)
        box.bind(pos=lambda *_: setattr(panel, "pos", box.pos),
                 size=lambda *_: setattr(panel, "size", box.size))

        bar = BoxLayout(orientation="horizontal", size_hint_y=None, height=u(BAR_H),
                        spacing=u(6))
        self.gallery_btn = None
        if gallery_supported():
            self.gallery_btn = _btn("从相册选择", ctx, self._pick_gallery, 96)
            bar.add_widget(self.gallery_btn)
        bar.add_widget(_btn("相册汇总", ctx, self._show_all, 76))
        bar.add_widget(_btn("刷新", ctx, self.refresh, 52))
        bar.add_widget(Widget())
        box.add_widget(bar)

        loc = BoxLayout(orientation="horizontal", size_hint_y=None, height=u(BAR_H),
                        spacing=u(6))
        self.dir_label = _label("", ctx, 10, "sub")
        self.dir_label.shorten = True
        self.dir_label.shorten_from = "left"
        loc.add_widget(self.dir_label)
        loc.add_widget(_btn("上级", ctx, self._go_up, 52))
        box.add_widget(loc)

        scroll = ScrollView(do_scroll_x=False)
        self.list_box = BoxLayout(orientation="vertical", size_hint_y=None, spacing=u(4))
        self.list_box.bind(minimum_height=self.list_box.setter("height"))
        scroll.add_widget(self.list_box)
        box.add_widget(scroll)

        self.hint = _label("", ctx, 10, "dim")
        self.hint.size_hint_y = None
        self.hint.height = u(34)
        box.add_widget(self.hint)

        self.sel_label = _label("", ctx, 10, "accent")
        self.sel_label.size_hint_y = None
        self.sel_label.height = u(24)
        self.sel_label.shorten = True
        box.add_widget(self.sel_label)

        foot = BoxLayout(orientation="horizontal", size_hint_y=None, height=u(BAR_H + 4),
                         spacing=u(8))
        foot.add_widget(Widget())
        foot.add_widget(_btn("确定", ctx, self._confirm, 70))
        foot.add_widget(_btn("取消", ctx, self.dismiss, 70))
        box.add_widget(foot)

        self.popup.content = box
        self.box = box              # 自检要量列表行数
        self.scroll = scroll

    # ------------------------------------------------------------------ #
    def open(self) -> None:
        self.refresh()
        self.popup.open()
        # Android 13+/12- 读相册要运行时授权；授权回来后重新扫一遍列表
        request_media_permissions(lambda *_: Clock.schedule_once(lambda *_: self.refresh(), 0))

    def dismiss(self) -> None:
        try:
            self.popup.dismiss()
        except Exception:
            pass
        if self.on_close_cb is not None:
            self.on_close_cb()

    # ------------------------------------------------------------------ #
    def refresh(self) -> None:
        """重新扫描并渲染列表（可在自检里直接调用）。"""
        self._thumb_budget = THUMB_BUDGET
        if self.dir:
            self.subdirs, self.items = list_dir(self.dir)
        else:
            self.subdirs = []
            self.items = scan_images(candidate_dirs(self.current, self.extra_dirs))
        self._render()
        self._update_labels()

    def _render(self) -> None:
        self.list_box.clear_widgets()
        self._img_rows = []
        self.rows = 0
        for item in self.items[:LIST_CAP]:
            row = _ImageRow(item, self.ctx, self._pick, self._thumb_for(item))
            row.set_selected(bool(self.selected)
                             and os.path.abspath(str(item["path"])) == os.path.abspath(self.selected))
            self.list_box.add_widget(row)
            self._img_rows.append(row)
            self.rows += 1
        for item in self.subdirs[:LIST_CAP]:
            self.list_box.add_widget(_DirRow(item, self.ctx, self._enter))
            self.rows += 1

    def _thumb_for(self, item: Dict[str, Any]) -> str:
        if self._thumb_budget <= 0:
            return ""
        self._thumb_budget -= 1
        path = thumb_for(str(item.get("path") or ""))
        if path:
            self.thumbs += 1
        return path

    def _update_labels(self) -> None:
        if self.dir:
            where = self.dir
        else:
            where = f"相册汇总（{len(candidate_dirs(self.current, self.extra_dirs))} 个目录）"
        self.dir_label.text = f"位置：{where}"
        parts = []
        if self.items:
            parts.append(f"{len(self.items)} 张图片")
        if self.subdirs:
            parts.append(f"{len(self.subdirs)} 个子文件夹")
        if parts:
            self.hint.text = "、".join(parts) + ("，点一行选中" if self.items else "，点一行进入")
        else:
            self.hint.text = ("这个位置没有找到图片。可以点「从相册选择」，或点「上级」换个文件夹"
                              "（手机相册一般在 图库/Pictures/DCIM 目录下）。")
        self.sel_label.text = (f"已选：{self.selected}" if self.selected
                               else "点一张图片选中，再点「确定」")

    # ------------------------------------------------------------------ #
    def _pick(self, item: Dict[str, Any]) -> None:
        self.selected = str(item.get("path") or "")
        for row in self._img_rows:
            row.set_selected(os.path.abspath(str(row.item["path"])) == os.path.abspath(self.selected))
        self._update_labels()

    def _enter(self, item: Dict[str, Any]) -> None:
        self.dir = str(item.get("path") or "")
        self.selected = ""
        self.refresh()

    def _show_all(self) -> None:
        self.dir = ""
        self.refresh()

    def _go_up(self) -> None:
        if not self.dir:
            return
        parent = os.path.dirname(os.path.abspath(self.dir))
        self.dir = parent if os.path.isdir(parent) else ""
        self.selected = ""
        self.refresh()

    def _confirm(self) -> None:
        target = self.selected or str(self.current or "")
        if not target:
            self.hint.text = "请先点一张图片选中，再点「确定」"
            return
        self.picked = target
        if self.on_pick_cb is not None:
            self.on_pick_cb(target)
        self.dismiss()

    # ------------------------------------------------------------------ #
    def _pick_gallery(self) -> None:
        self.hint.text = "正在打开系统相册 ..."
        if not pick_from_gallery(self._gallery_done):
            self.hint.text = "本机打不开系统相册，请在下面的列表里挑一张"

    def _gallery_done(self, path: str, error: str) -> None:
        """相册回调（可能在非 UI 线程），统一切回主线程处理。"""
        def apply(*_):
            if error:
                self.hint.text = error
                return
            if not path:
                self.hint.text = "已取消选择"
                return
            self.selected = path
            self.dir = ""
            self.refresh()
            self.hint.text = f"已选：{path}"

        Clock.schedule_once(apply, 0)

    # ------------------------------------------------------------------ #
    def described(self) -> Dict[str, Any]:
        """自检用：当前选择器状态（列表条数、命中路径、空态文案等）。"""
        return {"dir": self.dir or "(汇总)", "rows": self.rows,
                "images": len(self.items), "dirs": len(self.subdirs),
                "thumbs": self.thumbs, "selected": self.selected,
                "picked": self.picked,
                "paths": [str(item.get("path") or "") for item in self.items],
                "candidates": candidate_dirs(self.current, self.extra_dirs),
                "hint": self.hint.text,
                "gallery_button": bool(self.gallery_btn),
                "popup_open": bool(self.popup._window is not None)}
