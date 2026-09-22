<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" language="java" %>
<%--
  NUSTTI 课表服务端 —— 网页冒烟测试台。
  用途：不装 Android 客户端也能验证「登录教务系统 → 抓取解析课表 → 返回 JSON」整条链路。
  安卓端调用的就是本页使用的同一批 /api 接口。
--%>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>NUSTTI 课表服务端 · 测试台</title>
  <style>
    * { box-sizing: border-box; }
    body { margin: 0; font-family: "Microsoft YaHei UI", "PingFang SC", sans-serif; background: #0f172a; color: #e2e8f0; }
    .wrap { max-width: 1180px; margin: 0 auto; padding: 20px; }
    h1 { font-size: 20px; margin: 0 0 4px; }
    .sub { color: #94a3b8; font-size: 13px; margin-bottom: 18px; }
    .card { background: #1e293b; border: 1px solid #334155; border-radius: 12px; padding: 16px; margin-bottom: 16px; }
    .row { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
    label { font-size: 13px; color: #cbd5e1; }
    input[type=text], input[type=password] { background: #0f172a; border: 1px solid #475569; color: #e2e8f0;
      border-radius: 8px; padding: 8px 10px; font-size: 14px; min-width: 150px; }
    button { background: #2563eb; border: none; color: #fff; border-radius: 8px; padding: 9px 16px;
      font-size: 14px; cursor: pointer; }
    button.gray { background: #475569; }
    button:disabled { opacity: .6; cursor: not-allowed; }
    #captcha { height: 40px; border-radius: 6px; background: #fff; cursor: pointer; }
    .status { font-size: 13px; color: #93c5fd; margin-top: 10px; white-space: pre-wrap; }
    .err { color: #fca5a5; }
    table.kb { width: 100%; border-collapse: collapse; font-size: 12px; }
    table.kb th, table.kb td { border: 1px solid #334155; padding: 4px; vertical-align: top; }
    table.kb th { background: #0f172a; color: #cbd5e1; font-weight: 600; }
    .slot { color: #94a3b8; text-align: center; white-space: nowrap; width: 96px; }
    .course { background: #1d4ed8; border-radius: 6px; padding: 3px 5px; margin-bottom: 3px; line-height: 1.35; }
    .course small { color: #c7d2fe; display: block; }
    pre { background: #0f172a; border: 1px solid #334155; border-radius: 8px; padding: 10px; max-height: 300px;
      overflow: auto; font-size: 12px; }
    .hint { font-size: 12px; color: #94a3b8; }
  </style>
</head>
<body>
<div class="wrap">
  <h1>NUSTTI 课表服务端 · 测试台</h1>
  <div class="sub">登录正方 jsxsd 教务系统 → 抓取并解析课表 → 以 JSON 提供给 Android 客户端</div>

  <div class="card">
    <div class="row">
      <label>教务系统</label>
      <input type="text" id="base" value="https://jwgl.nustti.edu.cn" style="min-width:280px">
      <label>学号</label><input type="text" id="sid" placeholder="学号">
      <label>密码</label><input type="password" id="pwd" placeholder="密码">
    </div>
    <div class="row" style="margin-top:10px">
      <label>验证码</label>
      <input type="text" id="captchaText" placeholder="看不清点图片刷新" style="min-width:110px">
      <img id="captcha" alt="验证码" title="点击刷新" src="api/captcha">
      <button onclick="refreshCaptcha()" class="gray">换一张</button>
    </div>
    <div class="row" style="margin-top:12px">
      <button id="btnLogin" onclick="doLogin()">登录并拉取课表</button>
      <button class="gray" onclick="loadDemo()">演示数据（免登录）</button>
      <button class="gray" onclick="loadTimetable('')">刷新课表</button>
      <button class="gray" onclick="doLogout()">退出登录</button>
    </div>
    <div class="status" id="status">就绪。验证码由教务系统实时下发，需人工填写（程序不识别、不绕过验证码）。</div>
  </div>

  <div class="card">
    <div class="row" style="justify-content:space-between">
      <div id="summary" class="hint">尚未加载课表</div>
      <div class="hint">接口：/api/health · /api/captcha · /api/login · /api/timetable · /api/demo/timetable</div>
    </div>
    <div style="overflow:auto; margin-top:10px" id="gridBox"></div>
  </div>

  <div class="card">
    <div class="hint">原始返回 JSON</div>
    <pre id="raw">（空）</pre>
  </div>
</div>

<script>
  var state = { data: null };

  function setStatus(text, isErr) {
    var el = document.getElementById('status');
    el.className = 'status' + (isErr ? ' err' : '');
    el.textContent = text;
  }
  function refreshCaptcha() {
    document.getElementById('captcha').src = 'api/captcha?base=' + encodeURIComponent(document.getElementById('base').value)
      + '&t=' + Math.random();
  }
  function api(path, options) {
    return fetch(path, options).then(function (r) { return r.json(); });
  }

  function doLogin() {
    var payload = {
      base: document.getElementById('base').value,
      studentId: document.getElementById('sid').value,
      password: document.getElementById('pwd').value,
      captcha: document.getElementById('captchaText').value
    };
    setStatus('正在登录教务系统 ...');
    api('api/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    }).then(function (res) {
      if (res.needCaptcha) {
        setStatus((res.message || '教务系统要求验证码') + '，请填写验证码后再点登录。', true);
        refreshCaptcha();
        return;
      }
      if (!res.ok) {
        setStatus(res.message || '登录失败', true);
        refreshCaptcha();
        return;
      }
      setStatus('登录成功，正在拉取课表 ...');
      loadTimetable('');
    }).catch(function (e) { setStatus('请求异常：' + e, true); });
  }

  function doLogout() {
    api('api/logout', { method: 'POST' }).then(function () {
      setStatus('已退出登录');
      refreshCaptcha();
    });
  }

  function loadTimetable(term) {
    api('api/timetable' + (term ? '?term=' + encodeURIComponent(term) : '')).then(function (res) {
      document.getElementById('raw').textContent = JSON.stringify(res, null, 2);
      if (res.needLogin) { setStatus(res.message || '请先登录', true); return; }
      if (!res.ok) { setStatus(res.message || '拉取课表失败', true); return; }
      render(res.data);
      setStatus('课表加载完成。');
    }).catch(function (e) { setStatus('请求异常：' + e, true); });
  }

  function loadDemo() {
    api('api/demo/timetable').then(function (res) {
      document.getElementById('raw').textContent = JSON.stringify(res, null, 2);
      if (!res.ok) { setStatus(res.message || '演示数据加载失败', true); return; }
      render(res.data);
      setStatus('已加载演示数据（含"同一格多门课"样本）。');
    });
  }

  function render(data) {
    state.data = data;
    var courses = data.courses || [];
    var times = (data.meta && data.meta.sessionTimes) || {};
    var maxWeek = (data.meta && data.meta.maxWeek) || 20;
    document.getElementById('summary').textContent =
      '学期：' + (data.term || '-') + ' · 课程 ' + courses.length + ' 条 · 周次 ' +
      ((data.meta && data.meta.minWeek) || 1) + '-' + maxWeek + ' · 选项学期 ' + ((data.terms || []).length) + ' 个';

    var slots = 12;
    var html = ['<table class="kb"><thead><tr><th>节次</th>'];
    for (var d = 1; d <= 7; d++) { html.push('<th>星期' + '一二三四五六日'[d - 1] + '</th>'); }
    html.push('</tr></thead><tbody>');
    for (var s = 1; s <= slots; s++) {
      var t = times[String(s)] || ['', ''];
      html.push('<tr><td class="slot">第' + s + '节<br><small>' + t[0] + '-' + t[1] + '</small></td>');
      for (var wd = 1; wd <= 7; wd++) {
        html.push('<td>');
        courses.forEach(function (c) {
          if (c.weekday !== wd) return;
          if ((c.sessions || []).indexOf(s) < 0) return;
          html.push('<div class="course">' + esc(c.name)
            + '<small>' + esc(c.room || '') + (c.teacher ? ' · ' + esc(c.teacher) : '') + '</small>'
            + '<small>' + esc(c.weekRaw || ((c.weeks || []).length + '周')) + '</small></div>');
        });
        html.push('</td>');
      }
      html.push('</tr>');
    }
    html.push('</tbody></table>');
    document.getElementById('gridBox').innerHTML = html.join('');
  }

  function esc(text) {
    return String(text == null ? '' : text).replace(/[&<>"]/g, function (ch) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[ch];
    });
  }

  api('api/health').then(function (res) {
    setStatus('服务端就绪（版本 ' + (res.version || '-') + '，已登录：' + (res.loggedIn ? '是' : '否') + '）。');
  });
</script>
</body>
</html>
