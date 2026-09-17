[app]

# 应用名（手机桌面显示）
title = 南泰课表

# 包名：cn.nustti.nustti_timetable
package.name = nustti_timetable
package.domain = cn.nustti

# 源码目录（本 spec 所在目录），自检截图与构建缓存不打包进 apk
source.dir = .
source.include_exts = py,png,jpg,jpeg,webp,bmp,ttf,otf,ttc,json,md
source.exclude_dirs = screenshots,screenshots_temp,__pycache__,build,dist,.buildozer,bin,temp

# apk 版本号：与 VERSION 文件保持一致（改版本号时两处一起改）
version = 1.0.0

# 依赖：程序在手机上真正用到的四个库
#   kivy     -> 界面
#   pillow   -> 背景图缩放/模糊（tt_bg.py 用 PIL 做高斯模糊）
#   requests -> 教务系统登录/抓课表
#   openpyxl -> 仅用于读取 .xlsx 课表（如需缩短首次构建时间，可从本行删掉）
requirements = python3,kivy,pillow,requests,openpyxl

# 竖屏、非全屏（Kivy 会自己按窗口尺寸排版）
orientation = portrait
fullscreen = 0

# 图标与启动图（assets/ 下已附多尺寸：48/72/96/144/192/512）
icon.filename = %(source.dir)s/assets/icon.png
presplash.filename = %(source.dir)s/assets/presplash.png

# Android 权限：
#   INTERNET / ACCESS_NETWORK_STATE -> 登录教务系统同步课表
#   READ_MEDIA_IMAGES (Android 13+) / READ_EXTERNAL_STORAGE (Android 12-) -> 选择本机图片当背景
android.permissions = INTERNET,ACCESS_NETWORK_STATE,READ_MEDIA_IMAGES,READ_EXTERNAL_STORAGE

# 编译目标：compileSdk 34 / minSdk 24（Android 7.0 起）/ NDK 25b
android.api = 34
android.minapi = 24
android.ndk = 25b
android.ndk_api = 24
android.accept_sdk_license = True

# 只出 arm64 包（体积最小，覆盖所有现代手机）；老机型可加 armeabi-v7a
android.archs = arm64-v8a

# 配置/课表存在应用私有目录；允许 adb backup
android.private_storage = True
android.allow_backup = True

# 日志过滤：只看 python 输出，方便用 adb logcat 排错
android.logcat_filters = *:S python:D

# python-for-android 引导方式：SDL2（Kivy 桌面/手机统一）
p4a.bootstrap = sdl2

[buildozer]
log_level = 2
warn_on_root = 1
