---
AIGC:
    Label: "1"
    ContentProducer: 001191440300708461136T1XGW3
    ProduceID: 7992f24a3b0cbde17f03ffdc8c69294b_5faf7b75b62b11f19ef152540024e231
    ReservedCode1: MsJ129HNvRM5vff2p8DDpVQLaqpCDd0zIzt+RfTyc+uw8PMe7mdfWmoADLu5v7WlVs1PEAKxm8TW0BEBnQhE9JB2VDdmANYiOhvBWh8IQ8epQHBuOKz6em2bL1c5w2MmGq50e3HdwDtzBidj0QWYhGfwXVv17Gc5sDdtcYHSGtaI494MeiZqlopqIQA=
    ContentPropagator: 001191440300708461136T1XGW3
    PropagateID: 7992f24a3b0cbde17f03ffdc8c69294b_5faf7b75b62b11f19ef152540024e231
    ReservedCode2: MsJ129HNvRM5vff2p8DDpVQLaqpCDd0zIzt+RfTyc+uw8PMe7mdfWmoADLu5v7WlVs1PEAKxm8TW0BEBnQhE9JB2VDdmANYiOhvBWh8IQ8epQHBuOKz6em2bL1c5w2MmGq50e3HdwDtzBidj0QWYhGfwXVv17Gc5sDdtcYHSGtaI494MeiZqlopqIQA=
---

# nustti-timetable

南泰科课表（NUSTTI Timetable）· Android 原生客户端（v2.1.0）。

课表获取**直接对接南京理工大学泰州科技学院教务系统官网** `jwgl.nustti.edu.cn`：手机端自行登录官网、抓取课表 HTML 并解析，
**不依赖任何中间服务端**。v2.0.0 及更早版本的 JSP 测试台与 Java（Servlet）服务端已从本仓库移除，仓库只保留 Android 工程。

```
nustti-timetable/
└─ android/    Android Studio 原生 Java 工程：登录页 + 整周/今日/紧凑三种课表视图 + 设置页
```

## 构建

```powershell
$env:JAVA_HOME    = 'C:\Program Files\Microsoft\jdk-21.0.6.7-hotspot'
$env:ANDROID_HOME = 'C:\Users\Aths\AppData\Local\Android\Sdk'
cd android
.\gradlew.bat assembleDebug             # 产物：android\app\build\outputs\apk\debug\app-debug.apk
```

安装：`adb install -r app-debug.apk`（adb 位于 `%ANDROID_HOME%\platform-tools`）。

## 使用

1. 打开 App，在登录页填写教务系统**学号与密码**（官网登录页本身不需要验证码，故客户端也没有验证码输入项）。
2. 登录成功后自动抓取当前学期课表并缓存到本机，之后可离线查看；学期列表可直接在设置页切换。
3. 刷新时若官网会话已过期，客户端会用已保存的凭据自动重新登录一次。
4. 也可点「演示数据」直接查看界面（本地生成，不联网）。

## 数据来源（客户端直连官网）

| 环节 | 说明 |
| --- | --- |
| 教务系统地址 | `https://jwgl.nustti.edu.cn/jsxsd/` |
| 登录 | `POST /jsxsd/xk/LoginToXk`（账号=学号、口令按官网规则编码，无验证码） |
| 课表 | `GET /jsxsd/xskb/xskb_list.do?xnxq01id=<学期>` |
| 解析 | 手机端用 Jsoup 解析课表 HTML 表格（含 rowspan 合并单元格），转换为「星期 + 小节区间」结构 |

## 说明

- 已验证：`gradlew assembleDebug` 构建成功，产物为 `app-debug.apk`。
- 需要的运行环境：JDK 17+（本机使用 Microsoft JDK 21）、Android SDK（compileSdk 35 / buildTools 35.0.1）、Gradle Wrapper 自带。
- 教务系统地址常量在 `android/app/src/main/java/edu/nustti/timetable/edu/JwglClient.java`。
- 本次改造要点：删除 `server/` 整个 Maven 工程与 `index.jsp`；课表解析由服务端迁移到 Android 端（新增 `edu/JwglClient.java`、`edu/JwglSession.java`、`parse/TimetableParser.java`，删除 `api/ApiClient.java`）；设置页文案改为官网直连说明。
*（内容由AI生成，仅供参考）*
