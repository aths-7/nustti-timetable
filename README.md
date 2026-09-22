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

# nustti-native

南泰科课表（NUSTTI Timetable）原生重写版：Java Web（JSP/Servlet）服务端 + Android 原生客户端。

```
nustti-native/
├─ server/     Maven war 工程：登录正方 jsxsd 教务系统、抓取并解析课表、对外提供 JSON 接口
└─ android/    Android Studio 原生 Java 工程：登录页 + 整周/今日/紧凑三种课表视图 + 设置页
```

## 快速开始

### 服务端

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.6.7-hotspot'
cd server
mvn clean package                       # 产物：server\target\nustti-server.war
Copy-Item target\nustti-server.war <TOMCAT>\webapps\ -Force
<TOMCAT>\bin\startup.bat                # 打开 http://localhost:8080/nustti-server/
```

要求 Tomcat 10.1+（Jakarta EE 10，`jakarta.*` 命名空间，Tomcat 9 不可用）、JDK 17+、Maven 3.9+。

### 客户端

```powershell
$env:JAVA_HOME    = 'C:\Program Files\Microsoft\jdk-21.0.6.7-hotspot'
$env:ANDROID_HOME = 'C:\Users\Aths\AppData\Local\Android\Sdk'
cd android
.\gradlew.bat assembleDebug             # 产物：android\app\build\outputs\apk\debug\app-debug.apk
```

安装：`adb install -r app-debug.apk`（adb 位于 `%ANDROID_HOME%\platform-tools`）。

首次使用：登录页填服务端地址（模拟器 `http://10.0.2.2:8080/nustti-server`，真机填电脑局域网 IP），或点"演示数据"直接看界面。

## 接口

| 方法与路径 | 说明 |
| --- | --- |
| `GET /api/health` | 健康检查（版本、教务系统地址、登录态） |
| `GET /api/captcha` | 教务系统图形验证码（image/jpeg，人工识别） |
| `POST /api/login` | 登录教务系统，会话保存在服务端 HttpSession |
| `GET /api/timetable?term=&week=` | 抓取并解析课表，返回 JSON |
| `POST /api/logout` | 退出登录 |
| `GET /api/demo/timetable` | 演示课表（无需登录，供联调） |
| `GET /` | 内置 JSP 测试台 |

## 说明

- 已验证：`mvn clean package`（4 个解析单元测试通过）、`gradlew assembleDebug` 均可构建成功；服务端已实际部署 Tomcat 10.1.60 跑通上述接口。
- 教务系统地址写在 `server/src/main/java/edu/nustti/timetable/edu/JwglClient.java`。
- 本机环境相关注意（无效 Gradle 代理覆盖、buildToolsVersion、临时 Maven/Tomcat 等）见《启动与安装说明.md》。
*（内容由AI生成，仅供参考）*
