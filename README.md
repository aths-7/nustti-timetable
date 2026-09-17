---
AIGC:
    Label: "1"
    ContentProducer: 001191440300708461136T1XGW3
    ProduceID: 7992f24a3b0cbde17f03ffdc8c69294b_7afe19ebb22e11f1b2fa525400638852
    ReservedCode1: pRJZDGydEjVlMYMx34LLhgf50VeCZ1js1UifCLIkd5l+7OyGCXdEusC7JERhX6cHV3uadve7Zzz677ZVqYIfXE2LA/+t6AXxaXpA9sQwPsui8asZ3i4w8jP3IUI4HIGFRmXy27VQLrVbyTs8ZC5uGXIY38NnVby82FMpLJ2zerx1tN3ALBJjG6qDm2c=
    ContentPropagator: 001191440300708461136T1XGW3
    PropagateID: 7992f24a3b0cbde17f03ffdc8c69294b_7afe19ebb22e11f1b2fa525400638852
    ReservedCode2: pRJZDGydEjVlMYMx34LLhgf50VeCZ1js1UifCLIkd5l+7OyGCXdEusC7JERhX6cHV3uadve7Zzz677ZVqYIfXE2LA/+t6AXxaXpA9sQwPsui8asZ3i4w8jP3IUI4HIGFRmXy27VQLrVbyTs8ZC5uGXIY38NnVby82FMpLJ2zerx1tN3ALBJjG6qDm2c=
---

# 南泰课表 · Kivy 版

南京理工大学泰州科技学院课表程序（Kivy 重写版）。同一份代码在 **Windows 桌面** 和 **Android 手机** 上运行，
用于替代原 tkinter 桌面版无法上手机的问题。

数据层（`jwgl_client.py` / `kb_parser.py` / `store.py`）沿用桌面版逻辑，配置文件与桌面版共用同一目录，
两端可以互相读同一份 `config.json`、`timetable.json`。

---

## 1. 功能

| 功能 | 说明 |
| --- | --- |
| 整周课表 | 周一至周日 × 12 节，课程块按学科配色 |
| 今日视图 | 只列当天课程，含时间、地点、教师 |
| 本周紧凑 | 缩小字号、压缩行高，一屏看全周 |
| 设置面板 | 字体族 / 字号缩放 / 字体颜色 / 背景图（含蒙版浓度、模糊）|
| 背景层 | 自选照片做背景，蒙版 + 高斯模糊，切换视图跟随 |
| 课表同步 | `--sync` 登录教务系统重新抓取课表 |
| 本地配置 | `%APPDATA%\NUSTTI-Timetable\config.json`（Windows）/ 应用私有目录（Android）|
| 无人值守自检 | `--selfcheck <目录>` 四个视图渲染并截图，输出 `selfcheck_report.json` |

## 2. 目录结构

```
NUSTTI_Timetable_Kivy/
├── main.py                 # 入口：App 构建、视图切换、命令行参数、--selfcheck
├── tt_views.py             # 整周 / 今日 / 紧凑 三种课表视图
├── tt_settings.py          # 设置面板（SettingsOverlay，避开了 Kivy 内置 SettingsPanel 规则冲突）
├── tt_theme.py             # 配色、字体族 → 字体文件解析、字号缩放
├── tt_bg.py                # 背景层（PIL 高斯模糊 + 蒙版）
├── tt_model.py             # 课表数据模型 / 时间轴换算
├── jwgl_client.py          # 教务系统登录与抓取（与桌面版共用）
├── kb_parser.py            # 课表文本/表格解析（与桌面版共用）
├── store.py                # 配置与课表读写
├── buildozer.spec          # Android 打包配置
├── VERSION                 # 版本号（与 buildozer.spec 的 version 同步）
├── assets/                 # 应用图标（多尺寸）+ 启动图
│   ├── icon.png            # 512×512 主图标
│   ├── icon-192/144/96/72/48.png
│   └── presplash.png       # Android 启动图 1080×1920
└── screenshots/            # 自检截图产物（不打包进 apk）
```

## 3. Windows 桌面运行

```powershell
cd <本工程目录>
pip install kivy pillow requests openpyxl    # 一次性
python main.py                                # 正常启动（带窗口）
python main.py --version                      # 打印版本与数据目录
python main.py --sync                         # 登录教务系统同步课表
python main.py --selfcheck .\screenshots      # 四视图渲染 + 截图 + 自检报告
```

已实测（Windows 11 + Kivy 2.3）：`--selfcheck` 返回码 0，四个视图全部渲染成功，截图非零字节，
自检报告 `assertions` 七项全为 true，中文正常显示（字体落到 `C:\Windows\Fonts\msyh.ttc`）。

> 注意：`config.json` 里与桌面版共用的 `font_family` 可能是 `Modern`（tkinter 的族名）。
> Kivy 没有 Modern，若直接用内置 Roboto 渲染中文会变成空心方块，`tt_theme.apply_font()` 已做回落：
> 解析不到真实字体文件时改用本机默认可显示中文字体（Windows=微软雅黑，Android=NotoSansCJK）。

## 4. Android 构建

### 4.1 结论先说

* **Windows 原生无法打 apk**。buildozer / python-for-android 依赖 Linux 的 shell、autotools 与 NDK 工具链，
  官方只支持 Linux（含 WSL2）与 macOS。因此必须借一个 Linux 环境。
* 本机当前 **不具备** 现成构建环境（探测结果见 4.2），要走"路线 A（本机 WSL2）"需先做系统级变更，
  **请先授权**：启用 WSL2 会改动 Windows 可选功能并要求重启。
* 构建产物：`bin/nustti_timetable-1.0.0-arm64-v8a-debug.apk`（debug 包可直接安装；发布用 `buildozer android release`）。

### 4.2 本机环境探测结果（2026-09-17）

| 项目 | 现状 | 是否满足构建 |
| --- | --- | --- |
| WSL | `wsl.exe` 存在，但提示"未安装适用于 Linux 的 Windows 子系统"；`Microsoft-Windows-Subsystem-Linux`、`VirtualMachinePlatform` 均为 **Disabled** | 不满足，需启用 |
| CPU / 内存 | AMD Ryzen 7 8845HS（8 核）、19.8 GB、虚拟化固件与 SLAT 均开启 | 满足 |
| 磁盘 | C 盘剩余 **11.8 GB**、D 盘剩余 134 GB | C 盘不足，必须放 D 盘 |
| JDK | 系统 java 为 **9.0.1** | 不满足（p4a 需要 JDK 17） |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk` 存在：platform `android-35`、build-tools `35/36/37`、platform-tools、licenses，共 1.65 GB；**无 NDK、无 cmdline-tools** | 部分满足（WSL 内仍会自建一套） |
| Git | 已安装（C:\Program Files\Git\cmd\git.exe） | 满足 |
| Python 包 | kivy / pillow / requests / openpyxl 已装；buildozer、Cython 未装（这两个只在 Linux 侧需要） | 桌面侧满足 |

### 4.3 路线 A：本机 WSL2 + Ubuntu（需授权）

```powershell
# ① 管理员 PowerShell —— 启用 WSL2，并显式指定装到 D 盘（C 盘仅剩 11.8 GB，不够）
wsl --install -d Ubuntu-22.04 --location D:\WSL\Ubuntu
# 执行后需要重启一次；首次进入 Ubuntu 会让你设用户名/密码
```

```bash
# ② Ubuntu 内：系统依赖（约 2~3 GB）
sudo apt update && sudo apt install -y git zip unzip openjdk-17-jdk \
     python3-pip autoconf libtool pkg-config zlib1g-dev libncurses5-dev \
     libncursesw5-dev libtinfo5 cmake libffi-dev libssl-dev

# ③ Ubuntu 内：buildozer（编译期需要 Cython<3 之外的兼容版本，p4a 会自动带）
pip3 install --user buildozer cython

# ④ 把工程拷进 Linux 文件系统（切勿在 /mnt/c 下构建，权限与性能都会炸）
mkdir -p ~/proj && cp -r /mnt/d/<你的路径>/NUSTTI_Timetable_Kivy ~/proj/
cd ~/proj/NUSTTI_Timetable_Kivy

# ⑤ 出包（首次会下载 Android SDK + NDK，约 4~6 GB，40~90 分钟）
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
buildozer -v android debug

# 产物
ls -lh bin/*.apk
```

装到手机：`adb install -r bin/nustti_timetable-1.0.0-arm64-v8a-debug.apk`，
或把 apk 传到手机后点击安装（需允许"安装未知来源应用"）。

### 4.4 路线 B：远程 Linux / GitHub Actions（不需改动本机）

适合不想动 Windows 系统的情况：

* **GitHub Actions**：把工程推到仓库，用 `ArtemSBulgakov/buildozer-action` 或手写 workflow 跑
  `buildozer android debug`，产物作为 artifact 下载。单次 30~60 分钟，公开仓库免费。
* **任意 Linux 机器 / 云主机**：执行 4.3 的 ②~⑤ 步即可。

两条路线都不改本机系统，代价是需要账号 / 一台机器，且首次构建同样要下 4~6 GB 组件。

### 4.5 常见报错速查

| 报错 | 原因 | 处理 |
| --- | --- | --- |
| `command not found: buildozer` | 未装或未加 PATH | `pip3 install --user buildozer cython`，并把 `~/.local/bin` 加进 PATH |
| `Unsupported class file major version 61` / `invalid source release` | JDK 版本不对（8/9/21 都可能翻车） | 装 openjdk-17 并 `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` |
| `Failed to install the following Android SDK packages` / license 报错 | sdkmanager 许可未接受 | spec 里已 `android.accept_sdk_license = True`；仍报错就手动 `yes \| sdkmanager --licenses --sdk_root=$HOME/.buildozer/android/platform/android-sdk` |
| `No such file or directory: .../ndk/25b` | NDK 未下载完 / 版本与 p4a 要求不符 | 保持 `android.ndk = 25b`、`android.ndk_api = 24`，别手动改；删 `~/.buildozer` 相关目录重跑 |
| `ModuleNotFoundError: No module named 'PIL'` | requirements 漏依赖 | `requirements = python3,kivy,pillow,requests,openpyxl`（本 spec 已写全） |
| 真机上中文显示成方块 | 没有中文字库 | 本程序 `tt_theme` 会在 Android 上从 `/system/fonts` 找 NotoSansCJK/DroidSansFallback；若设备确实没有，需把 ttf 放进 assets 并注册 |
| 打开就闪退 / 白屏 | 缺权限或原生库不匹配 | `adb logcat -s python:D` 看 Python traceback；确认 `android.archs` 含手机架构（现代机 arm64-v8a） |
| 构建极慢 / 磁盘写满 | 首次全量编译 + 下载数 GB | 构建目录放 Linux 文件系统（不要 `/mnt/c`），预留 ≥40 GB；`buildozer android clean` 后重来代价更大，尽量别依赖重跑 |
| `Buildozer is running as root` | 以 root 运行 | 仅警告；建议用普通用户跑 |

### 4.6 版本号 / 图标

* 改版本：同时改 `VERSION` 与 `buildozer.spec` 的 `version`。
* 改图标：替换 `assets/icon.png`（512×512，方形、圆角，buildozer 会自动生成各密度 mipmap）。
  已附带 `icon-192/144/96/72/48.png` 供手动替换到 `res/mipmap-*`。

### 4.7 GitHub Actions 云构建（推荐，不动本机）

工程已内置工作流 `.github/workflows/build-apk.yml`，推上 GitHub 后由云端 Ubuntu 出包，本机不用装 WSL/JDK/NDK。

| 项目 | 建议 |
| --- | --- |
| 仓库名 | `nustti-timetable`（也可用 `NUSTTI_Timetable_Kivy`，无硬性要求）|
| 可见性 | **public 最省事**（Actions 免费无限时长）；**private 也可以**，免费额度 2000 分钟/月，单次构建约 60~90 分钟，够用 |
| 工作流文件 | `.github/workflows/build-apk.yml` |
| 触发方式 | ① 手动：仓库 **Actions → Build Android APK → Run workflow**（可选打包架构，默认 arm64-v8a）② 自动：push 到 `main` / `master` 分支 |
| 构建环境 | ubuntu-latest + JDK 17 + Python 3.11 + buildozer，缓存 `~/.buildozer` 的 SDK/NDK 与 `.buildozer` 中间产物 |
| 产物下载 | 构建页底部 **Artifacts → `nustti-timetable-apk`**，得到 `nustti_timetable-1.0.0-arm64-v8a-debug.apk`（保留 30 天）；失败时另有 `buildozer-logs` |
| 耗时预期 | **首次 45~90 分钟**（下载 SDK/NDK 约 4~6 GB + 全量编译 python-for-android 与依赖）；**缓存命中后 15~30 分钟** |

推送前本地已 `git init` 并完成首次提交（分支 `main`），只需补一条远程并推送：

```bash
git remote add origin https://github.com/<你的账号>/nustti-timetable.git
git push -u origin main
```

> 注意：`git push` 不能用 GitHub 登录密码，需在仓库用 **Personal Access Token（classic，勾选 repo）** 或浏览器里的凭据管理器/gh CLI 授权。
> 推送成功后无需做别的，去 Actions 页面点一次 Run workflow 即可。

## 5. 当前状态

* Windows 桌面：**可直接跑通**（四视图渲染 + 截图自检通过）。
* Android apk：**尚未产出**。本地 git 仓库与 Actions 工作流已就绪（分支 `main`，待推送 GitHub）；
  若走本机路线，则缺一个 Linux 构建环境（WSL2 未装、NDK 缺失、JDK 版本过低、C 盘空间不足）。
* 待授权事项：① 把本地仓库推送到 GitHub（推荐 `nustti-timetable`）并触发 Actions 出包；
  ② 或启用 WSL2 并把发行版装到 D 盘（需重启）。
*（内容由AI生成，仅供参考）*
