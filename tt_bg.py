# -*- coding: utf-8 -*-
"""背景层：铺满窗口的自定义背景图 + 蒙版（变暗）+ 模糊（照片退后）。

绘制顺序（从下到上）：底色 → 背景图（可模糊）→ 蒙版。
与桌面版（Pillow 预模糊 + 蒙版）略有不同：Kivy 端用 EffectWidget 做实时模糊，
避免在手机上做一次大图重采样；模糊强度上限降到 12，兼顾中低端机性能。

注意：背景图刻意不走 ``kivy.uix.image.Image``（异步加载器在部分机器上会报
"Unable to load image" 并连带 EffectWidget 原生崩溃），而是用
``kivy.core.image`` 同步取纹理后自绘 Rectangle，加载成败可精确判定。
"""

from __future__ import annotations

import os
from typing import Optional

from kivy.core.image import Image as CoreImage
from kivy.graphics import Color, Rectangle
from kivy.logger import Logger
from kivy.uix.floatlayout import FloatLayout
from kivy.uix.widget import Widget

from tt_theme import rgba

BASE_BG = "#EEF2F7"         # 浅色外观：底色改为浅灰（原深色 #0f1320）
MAX_BLUR = 12           # 模糊半径上限（像素）：与桌面版 0~30 的可视效果对齐但更省性能


def _effect_widget(blur: float):
    """带模糊效果的容器；Kivy 缺少 effectwidget 时返回 None（退化为不模糊）。"""
    if blur <= 0:
        return None
    try:
        from kivy.uix.effectwidget import EffectWidget, HorizontalBlurEffect, VerticalBlurEffect
    except Exception as exc:                                    # pragma: no cover
        Logger.warning(f"tt_bg: 模糊效果不可用（{exc}）")
        return None
    try:
        effect = HorizontalBlurEffect(size=blur)
        effect2 = VerticalBlurEffect(size=blur)
        widget = EffectWidget(effects=[effect, effect2])
        widget._h_effect, widget._v_effect = effect, effect2      # 便于运行时改强度
        return widget
    except Exception as exc:                                    # pragma: no cover
        Logger.warning(f"tt_bg: 模糊效果初始化失败（{exc}）")
        return None


class _BgPhoto(Widget):
    """自绘背景图：把纹理拉伸铺满自身（对应桌面版 allow_stretch + 不保持比例）。"""

    def __init__(self, texture, **kwargs):
        super().__init__(**kwargs)
        with self.canvas:
            self._color = Color(1, 1, 1, 1)
            self._rect = Rectangle(texture=texture, pos=self.pos, size=self.size)
        self.bind(pos=self._sync, size=self._sync)

    def _sync(self, *_):
        self._rect.pos = self.pos
        self._rect.size = self.size


class BackgroundLayer(FloatLayout):
    """底层背景：纯色底 → 可选背景图（可模糊）→ 蒙版。"""

    def __init__(self, bg_image: str = "", veil: int = 60, blur: int = 8, **kwargs):
        super().__init__(**kwargs)
        self.blur = max(0, min(MAX_BLUR, int(blur or 0)))
        self.bg_image = bg_image or ""
        self.image_ok = False
        self.load_error = ""
        with self.canvas.before:
            self._base_color = Color(*rgba(BASE_BG))
            self._base_rect = Rectangle(pos=self.pos, size=self.size)
        self.bind(pos=self._sync_base, size=self._sync_base)

        self._host: Optional[Widget] = None
        self._photo: Optional[Widget] = None
        self._texture = None
        self._veil_color = None
        self._veil_rect = None
        self._build_image()
        self.set_veil(veil)

    # ------------------------------------------------------------------ #
    def _sync_base(self, *_):
        self._base_rect.pos = self.pos
        self._base_rect.size = self.size
        if self._veil_rect is not None:
            self._veil_rect.pos = self.pos
            self._veil_rect.size = self.size

    def _build_image(self) -> None:
        """加载背景图并挂载（失败仅记录，不影响课表本身显示）。"""
        if not self.bg_image or not os.path.isfile(self.bg_image):
            self.load_error = "file_missing"
            return
        try:
            core = CoreImage(self.bg_image, nocache=True)
            texture = core.texture
            if texture is None:
                raise ValueError("texture is None")
        except Exception as exc:
            self.load_error = f"{type(exc).__name__}: {exc}"
            Logger.warning(f"tt_bg: 背景图加载失败（{exc}）")
            return
        try:
            photo = _BgPhoto(texture, size_hint=(1, 1), pos_hint={"x": 0, "y": 0})
            host = _effect_widget(self.blur)
            if host is not None:
                host.add_widget(photo)
                target = host
            else:
                target = photo
            self.add_widget(target)
        except Exception as exc:                                # pragma: no cover
            self.load_error = f"{type(exc).__name__}: {exc}"
            Logger.warning(f"tt_bg: 背景图挂载失败（{exc}）")
            return
        self._texture, self._photo, self._host = texture, photo, host
        self.image_ok = True
        self.load_error = ""

    def _detach(self) -> None:
        target = self._host or self._photo
        if target is not None:
            try:
                self.remove_widget(target)
            except Exception:                                   # pragma: no cover
                pass
        self._photo = self._host = None
        self._texture = None
        self.image_ok = False

    # ------------------------------------------------------------------ #
    def set_veil(self, veil: int) -> None:
        """蒙版强度 0~100：浅色外观下是"白纱"，越大背景越淡、越贴近白底。"""
        self.veil = max(0, min(100, int(veil or 0)))
        alpha = self.veil / 100.0 * 0.85
        if self._veil_rect is None:
            with self.canvas.after:
                self._veil_color = Color(1, 1, 1, alpha)
                self._veil_rect = Rectangle(pos=self.pos, size=self.size)
        else:
            self._veil_color.a = alpha
        self._sync_base()

    def set_blur(self, blur: int) -> None:
        """模糊半径 0~MAX_BLUR；改强度需要重建背景层（EffectWidget 不支持热改）。"""
        blur = max(0, min(MAX_BLUR, int(blur or 0)))
        if blur == self.blur:
            return
        self.blur = blur
        self._detach()
        self._build_image()
        self._sync_base()

    def set_image(self, path: str) -> None:
        self.bg_image = path or ""
        self._detach()
        self._build_image()
        self._sync_base()

    def described_state(self) -> dict:
        """自检用：当前背景层状态。"""
        return {"bg_image": self.bg_image, "image_ok": self.image_ok,
                "load_error": self.load_error,
                "veil": self.veil, "blur": self.blur,
                "texture": list(self._texture.size) if self._texture else None,
                "host": type(self._host).__name__ if self._host else "",
                "veil_alpha": round(self._veil_color.a, 3) if self._veil_color else None}
