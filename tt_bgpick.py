# -*- coding: utf-8 -*-
"""背景图选择器（自研，替代手机端不可用的 ``FileChooserListView``）。

为什么要换掉 Kivy 自带的文件选择器（最早截图里的 bug 根因）：
    1. 起始目录取 ``os.path.expanduser("~")``。python-for-android 环境里 HOME 未设置，
       expanduser 会"原样返回 ~"；接着 ``os.path.isdir("~")`` 为假，退回到 ``~``，
       FileChooser 内部 ``listdir("~")`` 抛 OSError 被吞掉后 ``files[:] = []`` ——
       于是列表只剩 Kivy 自己插的一条 "../" 返回项：界面大面积空白、只留一个 "." 行。
    2. 列头 Name / Size 是 Kivy 内置模板，Size 用 halign='right' 但没绑列宽，
       右对齐的边距永远对不上。
    3. 手机用户的心智是"从相册挑一张"，不是"浏览文件系统"。

本模块的做法（v1.0.3 起）：
    * 「全部图片」（默认视图）：像系统「所有照片」那样**枚举手机里所有图片**：
        - Android：先查 MediaStore（系统相册索引，覆盖所有已收录图片与所有格式），
          再对存储卡根目录做**递归扫描**（覆盖 Downloads / 微信 / QQ / 各 App 自建目录
          这类 MediaStore 之外或未及时入库的文件），两条来源按绝对路径去重合并。
        - 桌面：递归扫描 图片 / 桌面 / 下载 / OneDrive 等目录 + 传入的额外目录。
      MediaStore 里拿不到可读文件路径的条目（Android 10+ 少见但存在）保留 content://
      URI，缩略图走 ContentResolver，选中时再复制进应用私有目录。
    * 格式白名单（IMAGE_EXTS）扩到 jpg/jpeg/jpe/jfif/png/webp/bmp/gif/tif/tiff/heic/heif/ico/avif。
    * 「文件夹」视图：仍可逐级进目录挑（缩略图 + 文件名 + 大小右对齐）。
    * 列表分页（每页 PAGE 张）+ 缩略图分批生成（clock 驱动）：几千张图也不会把界面卡死。
    * 空态：写清"哪个位置没有图片、接下来点哪里"，绝不再是一片空白。
"""

from __future__ import annotations

import hashlib
import os
import time
from datetime import datetime
from typing import Any, Callable, Dict, Iterator, List, Optional, Tuple

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

# 常见图片格式（含手机相册里常见的 HEIC / JFIF；MediaStore 侧按 mime image/* 判定）
IMAGE_EXTS = (".jpg", ".jpeg", ".jpe", ".jfif", ".png", ".webp", ".bmp", ".gif",
              ".tif", ".tiff", ".heic", ".heif", ".ico", ".avif")

ROW_H = 56.0            # 列表行高（设计单位）
BAR_H = 38.0            # 工具条/按钮高度
PAGE = 80               # 每页渲染多少张图片（点「显示更多」再翻一页）
DIR_CAP = 200           # 文件夹视图里子文件夹最多显示多少行
THUMB_PX = 96           # 缩略图边长（像素）
THUMB_FIRST = 24        # 一次渲染最多同步生成多少张缩略图（首屏要快）
THUMB_BUDGET = 160      # 单次刷新的缩略图总预算
THUMB_PER_TICK = 6      # 每个时钟周期补多少张缩略图
SCAN_CAP = 6000         # 单目录列举上限（防超大目录卡死）
SCAN_TOTAL = 4000       # 全部图片模式下最多收集多少张
SCAN_BUDGET = 4.0       # 递归扫描时间预算（秒），超时先给出已有结果，界面不卡死
MAX_DEPTH = 6           # 递归深度上限（防止 Android 深层目录无限下钻）
REQ_GALLERY = 0x9A11    # startActivityForResult 请求码

# 递归扫描时跳过的目录名（缓存 / 应用私有数据 / 代码目录，进去只会浪费预算）
SKIP_DIR_NAMES = {
    ".thumbnails", "thumbs", "cache", ".cache", "android", "obb", "node_modules",
    ".git", ".svn", "logs", ".trash", "temp", "tmp", "$recycle.bin", "system volume information",
}


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


def item_key(item: Dict[str, Any]) -> str:
    """同一条目的去重键：有真实文件路径用路径，否则用 content:// URI。"""
    path = str(item.get("path") or "")
    if path:
        return "f:" + os.path.abspath(path).lower()
    uri = str(item.get("uri") or "")
    if uri:
        return "u:" + uri
    return ""


def is_uri_item(item: Dict[str, Any]) -> bool:
    """没有可读文件路径、只剩 content:// URI 的条目（Android 10+ 少量情况）。"""
    return bool(item) and not str(item.get("path") or "") and bool(item.get("uri"))


def make_item(path: str = "", uri: str = "", name: str = "", size: Any = 0,
              mtime: Any = 0.0, source: str = "fs") -> Dict[str, Any]:
    return {"path": str(path or ""), "uri": str(uri or ""),
            "name": str(name or (os.path.basename(path) if path else "")),
            "size": int(size or 0), "mtime": float(mtime or 0.0), "source": source}


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
                     "/storage/emulated/0/Pictures/WeiXin",
                     "/storage/emulated/0/DCIM",
                     "/storage/emulated/0/DCIM/Camera",
                     "/storage/emulated/0/DCIM/Screenshots",
                     "/storage/emulated/0/Download",
                     "/storage/emulated/0/Documents",
                     "/storage/emulated/0/Movies",
                     "/storage/emulated/0/Pictures/Screenshots",
                     "/storage/emulated/0/Android/media"):
            add(path)
    else:
        home = os.path.expanduser("~")
        for name in ("Pictures", "Pictures/Screenshots", "Desktop", "Downloads",
                     "OneDrive/Pictures", "OneDrive/Desktop", "OneDrive/图片", "图片",
                     "Documents"):
            add(os.path.join(home, name))
    add(store.data_dir())
    return out


def storage_roots(extra: Optional[List[str]] = None) -> List[str]:
    """递归扫描的起点（Android 上是整张内置存储卡；桌面是常用几个目录）。"""
    out: List[str] = []

    def add(path: Any) -> None:
        text = str(path or "").strip()
        if not text or "~" in text:
            return
        try:
            text = os.path.abspath(os.path.expanduser(text))
        except Exception:
            return
        if os.path.isdir(text) and text not in out:
            out.append(text)

    for path in (extra or []):
        add(path)
    if IS_ANDROID:
        for path in ("/storage/emulated/0", "/storage/emulated/0/DCIM",
                     "/storage/emulated/0/Pictures", "/storage/emulated/0/Download",
                     "/storage/emulated/0/Documents", "/storage/emulated/0/Movies"):
            add(path)
    else:
        home = os.path.expanduser("~")
        for name in ("Pictures", "Desktop", "Downloads", "Documents",
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
                        subdirs.append(make_item(path=entry.path, name=entry.name,
                                                 source="dir") | {"isdir": True})
                    elif entry.is_file(follow_symlinks=False) and is_image(entry.name):
                        stat = entry.stat()
                        images.append(make_item(path=entry.path, name=entry.name,
                                                size=stat.st_size, mtime=stat.st_mtime))
                except OSError:
                    continue
    except OSError as exc:
        Logger.warning(f"tt_bgpick: 读不到目录 {path}（{exc}）")
    subdirs.sort(key=lambda item: str(item["name"]).lower())
    images.sort(key=lambda item: item["mtime"], reverse=True)
    return subdirs, images


def walk_images(roots: List[str], limit: int = SCAN_TOTAL,
                deadline: Optional[float] = None, max_depth: int = MAX_DEPTH
                ) -> Iterator[Dict[str, Any]]:
    """递归扫描目录树里的图片（生成器；跳过缓存/私有目录，带时间与深度上限）。"""
    stack: List[Tuple[str, int]] = []
    for root in roots or []:
        if root and os.path.isdir(root):
            stack.append((os.path.abspath(root), 0))
    seen_dirs = set()
    found = 0
    while stack and found < limit:
        if deadline is not None and time.time() > deadline:
            return
        folder, depth = stack.pop()
        key = folder.lower()
        if key in seen_dirs:
            continue
        seen_dirs.add(key)
        try:
            with os.scandir(folder) as it:
                for index, entry in enumerate(it):
                    if index >= SCAN_CAP or found >= limit:
                        break
                    try:
                        if entry.is_dir(follow_symlinks=False):
                            name = entry.name.lower()
                            if name.startswith(".") or name in SKIP_DIR_NAMES:
                                continue
                            if depth + 1 <= max_depth:
                                stack.append((entry.path, depth + 1))
                        elif entry.is_file(follow_symlinks=False) and is_image(entry.name):
                            stat = entry.stat()
                            found += 1
                            yield make_item(path=entry.path, name=entry.name,
                                            size=stat.st_size, mtime=stat.st_mtime)
                    except OSError:
                        continue
        except OSError:
            continue


def scan_images(dirs: List[str], extra: Optional[List[str]] = None,
                limit: int = PAGE) -> List[Dict[str, Any]]:
    """把多个目录里的图片汇总成一份列表（按修改时间新→旧，路径去重）。"""
    out: List[Dict[str, Any]] = []
    seen = set()
    for folder in list(extra or []) + list(dirs or []):
        if not folder or not os.path.isdir(folder):
            continue
        _, images = list_dir(folder)
        for item in images:
            key = item_key(item)
            if not key or key in seen:
                continue
            seen.add(key)
            out.append(item)
    out.sort(key=lambda item: item["mtime"], reverse=True)
    return out[:max(1, int(limit))]


# --------------------------------------------------------------------------- #
# Android MediaStore（系统相册索引：手机里"所有照片"的正源）
# --------------------------------------------------------------------------- #
def media_store_query_supported() -> bool:
    if not IS_ANDROID:
        return False
    try:
        import jnius  # noqa: F401
        from android import mActivity  # noqa: F401
        return True
    except Exception:
        return False


def media_store_items(limit: int = SCAN_TOTAL) -> List[Dict[str, Any]]:
    """查 MediaStore 图片表，返回手机里所有已收录图片（含非相册目录里的）。

    有可读文件路径的走 ``path``（缩略图直接交给 Pillow，最快）；拿不到路径的保留
    ``uri``（缩略图走 ContentResolver，选中时复制到应用私有目录）。任何异常都只
    记日志并返回已拿到的部分，绝不让选择器崩掉。
    """
    if not media_store_query_supported():
        return []
    out: List[Dict[str, Any]] = []
    try:
        from jnius import autoclass, jarray
        from android import mActivity
        resolver = mActivity.getContentResolver()
        MediaStore = autoclass("android.provider.MediaStore$Images$Media")
        uri = MediaStore.EXTERNAL_CONTENT_URI
        projection = jarray("java.lang.String")(
            ["_id", "_data", "_display_name", "_size", "date_modified", "mime_type"])
        cursor = resolver.query(uri, projection, None, None, "date_modified DESC")
        if cursor is None:
            return []
        try:
            idx_id = cursor.getColumnIndex("_id")
            idx_data = cursor.getColumnIndex("_data")
            idx_name = cursor.getColumnIndex("_display_name")
            idx_size = cursor.getColumnIndex("_size")
            idx_mtime = cursor.getColumnIndex("date_modified")
            idx_mime = cursor.getColumnIndex("mime_type")
            ContentUris = autoclass("android.content.ContentUris")
            while cursor.moveToNext() and len(out) < limit:
                mime = str(cursor.getString(idx_mime) or "") if idx_mime >= 0 else ""
                if mime and not mime.startswith("image/"):
                    continue
                data = str(cursor.getString(idx_data) or "") if idx_data >= 0 else ""
                name = str(cursor.getString(idx_name) or "") if idx_name >= 0 else ""
                size = int(cursor.getLong(idx_size) or 0) if idx_size >= 0 else 0
                mtime = float(cursor.getLong(idx_mtime) or 0) / 1000.0 if idx_mtime >= 0 else 0.0
                readable = bool(data) and os.path.isfile(data)
                image_uri = ""
                if not readable and idx_id >= 0:
                    row_id = int(cursor.getLong(idx_id) or 0)
                    image_uri = str(ContentUris.withAppendedId(uri, row_id))
                if not readable and not image_uri:
                    continue
                out.append(make_item(path=data if readable else "", uri=image_uri,
                                     name=name or (os.path.basename(data) if data else ""),
                                     size=size, mtime=mtime, source="media"))
        finally:
            try:
                cursor.close()
            except Exception:
                pass
    except Exception as exc:
        Logger.warning(f"tt_bgpick: 读取系统相册索引失败（{exc}）")
    return out


def scan_all_images(roots: Optional[List[str]] = None, current: str = "",
                    extra: Optional[List[str]] = None, limit: int = SCAN_TOTAL,
                    budget: float = SCAN_BUDGET) -> List[Dict[str, Any]]:
    """枚举"手机里所有图片"：MediaStore + 候选目录直属列举 + 递归扫描，去重合并。

    返回按修改时间新→旧排序的条目；每条含 path / uri / name / size / mtime / source。
    """
    deadline = time.time() + max(0.5, float(budget or SCAN_BUDGET))
    scan_roots = list(roots) if roots is not None else storage_roots(extra)
    for folder in candidate_dirs(current, extra):        # 已知相册目录优先，保证首屏有图
        if folder not in scan_roots:
            scan_roots.insert(0, folder)

    out: List[Dict[str, Any]] = []
    seen = set()

    def push(item: Dict[str, Any]) -> None:
        key = item_key(item)
        if not key or key in seen or len(out) >= limit:
            return
        seen.add(key)
        out.append(item)

    for item in media_store_items(limit):                # ① 系统相册索引（最全）
        push(item)
    for folder in scan_roots:                            # ② 目录直属列举（含未入库新图）
        if not os.path.isdir(folder):
            continue
        _, images = list_dir(folder)
        for item in images:
            push(item)
    for item in walk_images(scan_roots, limit=limit, deadline=deadline):   # ③ 递归
        push(item)
    out.sort(key=lambda item: item["mtime"], reverse=True)
    return out[:limit]


def ext_summary(items: List[Dict[str, Any]], cap: int = 6) -> List[str]:
    """列表里出现过的图片后缀（新→旧前 N 个），用来向主人证明"格式都认"。"""
    exts: List[str] = []
    for item in items:
        ext = os.path.splitext(str(item.get("name") or item.get("path") or ""))[1].lower()
        if ext and ext not in exts:
            exts.append(ext)
        if len(exts) >= cap:
            break
    return exts


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
    if not folder or not path or not os.path.isfile(path):
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


def thumb_for_uri(uri: str, size: int = THUMB_PX) -> str:
    """content:// 条目的缩略图：BitmapFactory 按 inSampleSize 解码后压成 PNG 缓存。"""
    folder = _thumb_dir()
    if not folder or not uri or not IS_ANDROID:
        return ""
    try:
        from jnius import autoclass
        from android import mActivity
        key = hashlib.md5(f"{uri}|{size}".encode("utf-8")).hexdigest()[:16]
        out = os.path.join(folder, key + ".png")
        if os.path.isfile(out) and os.path.getsize(out) > 0:
            return out
        BitmapFactory = autoclass("android.graphics.BitmapFactory")
        Options = autoclass("android.graphics.BitmapFactory$Options")
        opts = Options()
        opts.inSampleSize = 8
        resolver = mActivity.getContentResolver()
        stream = resolver.openInputStream(autoclass("android.net.Uri").parse(uri))
        if stream is None:
            return ""
        try:
            bitmap = BitmapFactory.decodeStream(stream, None, opts)
        finally:
            try:
                stream.close()
            except Exception:
                pass
        if bitmap is None:
            return ""
        FileOutputStream = autoclass("java.io.FileOutputStream")
        fmt = autoclass("android.graphics.Bitmap$CompressFormat").PNG
        handle = FileOutputStream(out)
        ok = False
        try:
            ok = bool(bitmap.compress(fmt, 100, handle))
        finally:
            try:
                handle.flush()
                handle.close()
                bitmap.recycle()
            except Exception:
                pass
        return out if ok and os.path.isfile(out) and os.path.getsize(out) > 0 else ""
    except Exception as exc:
        Logger.warning(f"tt_bgpick: 相册缩略图失败（{exc}）")
        return ""


def thumb_for_item(item: Dict[str, Any], size: int = THUMB_PX) -> str:
    """按条目类型取缩略图：有文件走 Pillow，只有 URI 走 ContentResolver。"""
    path = str(item.get("path") or "")
    if path and os.path.isfile(path):
        return thumb_for(path, size)
    uri = str(item.get("uri") or "")
    if uri:
        return thumb_for_uri(uri, size)
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
    """把相册/索引返回的 content:// 图片复制进应用私有目录，返回落盘路径（失败回 ""）。"""
    from jnius import autoclass, jarray
    from android import mActivity
    if isinstance(uri, str):
        uri = autoclass("android.net.Uri").parse(uri)
    resolver = mActivity.getContentResolver()
    stream = resolver.openInputStream(uri)
    if stream is None:
        return ""
    ext = ".jpg"
    try:
        mime = str(resolver.getType(uri) or "")
        if "png" in mime:
            ext = ".png"
        elif "webp" in mime:
            ext = ".webp"
        elif "gif" in mime:
            ext = ".gif"
        elif "bmp" in mime:
            ext = ".bmp"
        elif "hei" in mime or "avif" in mime:
            ext = ".heic"
    except Exception:
        pass
    dest = os.path.join(store.data_dir(),
                        f"bg_{datetime.now().strftime('%Y%m%d_%H%M%S')}{ext}")
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
    if real_ext and real_ext != ext:
        renamed = os.path.splitext(dest)[0] + real_ext
        try:
            os.replace(dest, renamed)
            dest = renamed
        except OSError:
            pass
    return dest


def export_item(item: Dict[str, Any]) -> str:
    """把选中的条目落成一个可读文件路径（URI 条目先复制进私有目录）。"""
    path = str(item.get("path") or "")
    if path and os.path.isfile(path):
        return path
    uri = str(item.get("uri") or "")
    if uri and IS_ANDROID:
        try:
            return copy_uri_to_private(uri)
        except Exception as exc:
            Logger.warning(f"tt_bgpick: 复制相册图片失败（{exc}）")
            return ""
    return ""


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
        self.kind = "icon"
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
    """图片行：缩略图 + 文件名（左）+ 大小（右对齐到自己的列宽）。

    缩略图分批生成：先摆占位块，随后由 ``set_thumb`` 换成真图（几千张图也不卡首屏）。
    """

    def __init__(self, item: Dict[str, Any], ctx: Ctx,
                 on_pick: Callable[[Dict[str, Any]], None], thumb: str, **kwargs):
        super().__init__(**kwargs)
        self.item = item
        self.ctx = ctx
        self.has_thumb = bool(thumb)
        self.head = self._make_head(thumb)
        self.add_widget(self.head)
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

    def _make_head(self, thumb: str) -> Widget:
        if thumb:
            return Image(source=thumb, nocache=True, size_hint=(None, None),
                         size=(u(ROW_H - 16), u(ROW_H - 16)),
                         allow_stretch=True, keep_ratio=True)
        ext = os.path.splitext(str(self.item.get("name") or ""))[1].lstrip(".").upper()
        return _IconBox((ext or "IMG")[:4], self.ctx)

    def set_thumb(self, thumb: str) -> bool:
        """把占位块换成真缩略图；已有图或没有缩略图时返回 False。"""
        if self.has_thumb or not thumb:
            return False
        self.remove_widget(self.head)
        self.head = self._make_head(thumb)
        self.add_widget(self.head, index=0)
        self.has_thumb = True
        return True


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


class _MoreRow(_PickRow):
    """「显示更多」行：列表分页的入口（还有多少张没显示写在上面）。"""

    def __init__(self, more: int, ctx: Ctx, cb: Callable[[], None], **kwargs):
        super().__init__(**kwargs)
        self.add_widget(_IconBox("+", ctx, color=THEME["accent_soft"], fg="accent"))
        name = _label(f"显示更多（还有 {max(0, int(more))} 张未显示）", ctx, 11.5, "accent")
        name.size_hint_x = 1
        self.add_widget(name)
        self.bind(on_release=lambda *_: cb())


class BackgroundPickerDialog:
    """背景图选择器（设置页「选择」按钮唤起）。

    ``dir`` 为空字符串表示「全部图片」视图（手机里所有图片，新的在前，分页显示）；
    否则只列该目录下的图片与子文件夹（点文件夹可逐级进入）。
    """

    def __init__(self, ctx: Ctx, current: str = "",
                 on_pick: Optional[Callable[[str], None]] = None,
                 on_close: Optional[Callable[[], None]] = None,
                 extra_dirs: Optional[List[str]] = None,
                 scan_roots: Optional[List[str]] = None):
        self.ctx = ctx
        self.current = str(current or "")
        self.on_pick_cb = on_pick
        self.on_close_cb = on_close
        self.extra_dirs = [str(d) for d in (extra_dirs or []) if str(d or "").strip()]
        self.extra_roots = [str(d) for d in (scan_roots or []) if str(d or "").strip()]
        self.dir = ""
        self.items: List[Dict[str, Any]] = []
        self.subdirs: List[Dict[str, Any]] = []
        self.rows = 0
        self.shown = 0                  # 当前渲染了多少张图片（分页游标）
        self.selected = ""
        self.picked = ""
        self.thumbs = 0
        self._img_rows: List[_ImageRow] = []
        self._pending: List[_ImageRow] = []
        self._pump_event: Any = None
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
        self.all_btn = _btn("全部图片", ctx, self._show_all, 76)
        bar.add_widget(self.all_btn)
        bar.add_widget(_btn("文件夹", ctx, self._browse_dirs, 62))
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
        self._stop_pump()
        try:
            self.popup.dismiss()
        except Exception:
            pass
        if self.on_close_cb is not None:
            self.on_close_cb()

    # ------------------------------------------------------------------ #
    def scan_roots(self) -> List[str]:
        """「全部图片」视图的递归扫描起点（额外目录排最前，便于自检/指定目录）。"""
        return storage_roots(self.extra_dirs + self.extra_roots)

    def refresh(self) -> None:
        """重新扫描并渲染列表（可在自检里直接调用）。"""
        self._thumb_budget = THUMB_BUDGET
        if self.dir:
            self.subdirs, self.items = list_dir(self.dir)
        else:
            self.subdirs = []
            self.items = scan_all_images(self.scan_roots(), current=self.current,
                                         extra=self.extra_dirs)
        self.shown = min(len(self.items), PAGE)
        self._render()
        self._update_labels()

    def _render(self) -> None:
        self._stop_pump()
        self.list_box.clear_widgets()
        self._img_rows = []
        self._pending = []
        self.rows = 0
        for item in self.items[:self.shown]:
            row = _ImageRow(item, self.ctx, self._pick, self._thumb_for(item))
            row.set_selected(bool(self.selected)
                             and self._is_selected(item))
            self.list_box.add_widget(row)
            self._img_rows.append(row)
            if not row.has_thumb:
                self._pending.append(row)
            self.rows += 1
        for item in self.subdirs[:DIR_CAP]:
            self.list_box.add_widget(_DirRow(item, self.ctx, self._enter))
            self.rows += 1
        if len(self.items) > self.shown:
            self.list_box.add_widget(_MoreRow(len(self.items) - self.shown, self.ctx,
                                              self.show_more))
            self.rows += 1
        if self._pending:
            self._pump_event = Clock.schedule_interval(self._pump_thumbs, 0.05)

    def show_more(self) -> int:
        """翻下一页（+PAGE 张）；返回当前渲染的张数。"""
        self.shown = min(len(self.items), self.shown + PAGE)
        self._render()
        self._update_labels()
        return self.shown

    def _stop_pump(self) -> None:
        if self._pump_event is not None:
            try:
                self._pump_event.cancel()
            except Exception:
                pass
            self._pump_event = None

    def _pump_thumbs(self, _dt: float = 0.0) -> bool:
        """每个时钟周期补几张缩略图；补完自动停表。"""
        done = 0
        while self._pending and done < THUMB_PER_TICK and self._thumb_budget > 0:
            row = self._pending.pop(0)
            self._thumb_budget -= 1
            if row.set_thumb(thumb_for_item(row.item)):
                self.thumbs += 1
            done += 1
        if not self._pending or self._thumb_budget <= 0:
            self._pending = []
            self._pump_event = None
            return False
        return True

    def fill_thumbs_sync(self, max_rows: int = THUMB_FIRST) -> int:
        """同步补若干张缩略图（自检用，省去等时钟）。返回补成功的张数。"""
        filled = 0
        guard = 0
        while self._pending and filled < max_rows and guard < max_rows * 4:
            guard += 1
            row = self._pending.pop(0)
            if row.set_thumb(thumb_for_item(row.item)):
                self.thumbs += 1
                filled += 1
        return filled

    def _thumb_for(self, item: Dict[str, Any]) -> str:
        if self._thumb_budget <= 0:
            return ""
        self._thumb_budget -= 1
        path = thumb_for_item(item)
        if path:
            self.thumbs += 1
        return path

    def _is_selected(self, item: Dict[str, Any]) -> bool:
        if not self.selected:
            return False
        target = str(item.get("path") or item.get("uri") or "")
        if not target:
            return False
        if str(item.get("path") or ""):
            return os.path.abspath(str(item["path"])) == os.path.abspath(self.selected)
        return str(item.get("uri") or "") == self.selected

    def _update_labels(self) -> None:
        if self.dir:
            where = self.dir
        else:
            where = f"全部图片（扫描 {len(self.scan_roots())} 个位置）"
        self.dir_label.text = f"位置：{where}"
        parts = []
        if self.items:
            parts.append(f"共 {len(self.items)} 张图片")
            if len(self.items) > self.shown:
                parts.append(f"已显示 {self.shown} 张")
            exts = ext_summary(self.items)
            if exts:
                parts.append("格式 " + "/".join(e.lstrip(".") for e in exts))
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
        self.selected = str(item.get("path") or item.get("uri") or "")
        for row in self._img_rows:
            row.set_selected(self._is_selected(row.item))
        self._update_labels()

    def _enter(self, item: Dict[str, Any]) -> None:
        self.dir = str(item.get("path") or "")
        self.selected = ""
        self.refresh()

    def _show_all(self) -> None:
        self.dir = ""
        self.refresh()

    def _browse_dirs(self) -> None:
        """切到文件夹视图：从当前背景图所在目录（或第一个候选目录）开始逐级挑。"""
        folders = candidate_dirs(self.current, self.extra_dirs + self.extra_roots)
        self.dir = folders[0] if folders else ""
        self.selected = ""
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
        if target.startswith("content://"):        # URI 条目：先复制进私有目录
            for item in self.items:
                if str(item.get("uri") or "") == target:
                    target = export_item(item) or ""
                    break
            if not target:
                self.hint.text = "这张图片读不出来，换一张试试"
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
        """自检用：当前选择器状态（列表条数、命中路径、格式覆盖、分页等）。"""
        return {"dir": self.dir or "(全部图片)", "rows": self.rows,
                "images": len(self.items), "shown": self.shown, "page": PAGE,
                "more": max(0, len(self.items) - self.shown),
                "dirs": len(self.subdirs),
                "thumbs": self.thumbs, "pending_thumbs": len(self._pending),
                "selected": self.selected, "picked": self.picked,
                "paths": [str(item.get("path") or item.get("uri") or "") for item in self.items],
                "formats": ext_summary(self.items),
                "candidates": candidate_dirs(self.current, self.extra_dirs),
                "roots": self.scan_roots(),
                "hint": self.hint.text,
                "gallery_button": bool(self.gallery_btn),
                "popup_open": bool(self.popup._window is not None)}
