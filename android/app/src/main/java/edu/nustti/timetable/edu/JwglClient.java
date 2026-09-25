package edu.nustti.timetable.edu;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import edu.nustti.timetable.model.TimetableResult;
import edu.nustti.timetable.parse.TimetableParser;

/**
 * 南京理工大学泰州科技学院教务系统（正方 jsxsd）直连客户端（Android 端）。
 *
 * <p>课堂课表由本类在手机端直接向教务系统官网请求获取，不再经过任何中间服务端。</p>
 *
 * <p>接口事实（本机实测确认）：</p>
 * <pre>
 * 登录页     GET  https://jwgl.nustti.edu.cn/jsxsd/
 * 登录提交   POST https://jwgl.nustti.edu.cn/jsxsd/xk/LoginToXk
 *            字段：userAccount / userPassword / encoded / pwdstr1 / pwdstr2
 *            encoded = base64(学号) + "%%%" + base64(密码)
 * 学生课表   GET/POST https://jwgl.nustti.edu.cn/jsxsd/xskb/xskb_list.do  学期参数：xnxq01id
 * </pre>
 *
 * <p>登录页只有上述五个表单字段，页面 DOM 中不存在验证码输入框，因此登录为「一次提交」。</p>
 */
public class JwglClient {

    /** 教务系统官网地址（唯一数据来源）。 */
    public static final String DEFAULT_BASE = "https://jwgl.nustti.edu.cn";

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private static final String[] LOGIN_MARKERS = {"LoginToXk", "userAccount"};

    private static final Pattern SHOWMSG_RE =
            Pattern.compile("id\\s*=\\s*[\"']showMsg[\"'][^>]*>(.*?)<", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CHARSET_RE = Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG_RE = Pattern.compile("<[^>]+>");

    private static final SSLContext TRUST_ALL = buildTrustAllContext();

    private final String base;
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private final List<String> logs = new ArrayList<>();
    private String lastPage = "";
    private boolean loggedIn;

    public JwglClient() {
        this(DEFAULT_BASE);
    }

    public JwglClient(String baseUrl) {
        this.base = normalizeBase(baseUrl);
    }

    /** 规范化教务系统地址：空值回退官网、自动补协议、去尾部斜杠。 */
    public static String normalizeBase(String baseUrl) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        if (b.isEmpty()) {
            return DEFAULT_BASE;
        }
        if (!b.startsWith("http://") && !b.startsWith("https://")) {
            b = "https://" + b;
        }
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }

    private static SSLContext buildTrustAllContext() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, new SecureRandom());
            return ctx;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ //
    // 基础请求（HttpURLConnection + 手动 Cookie / 重定向）
    // ------------------------------------------------------------------ //

    private String get(String path, Map<String, String> query) {
        return request(path, "GET", query, null);
    }

    private String post(String path, Map<String, String> form) {
        return request(path, "POST", null, form);
    }

    private String request(String pathOrUrl, String method, Map<String, String> query, Map<String, String> form) {
        String target = pathOrUrl.startsWith("http") ? pathOrUrl : base + pathOrUrl;
        if (query != null && !query.isEmpty()) {
            target = appendQuery(target, query);
        }
        String current = target;
        String currentMethod = method;
        String currentBody = (form == null || form.isEmpty()) ? null : encodeForm(form);

        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(current);
                conn = (HttpURLConnection) url.openConnection();
                if (conn instanceof HttpsURLConnection) {
                    HttpsURLConnection https = (HttpsURLConnection) conn;
                    if (TRUST_ALL != null) {
                        https.setSSLSocketFactory(TRUST_ALL.getSocketFactory());
                    }
                    https.setHostnameVerifier(new HostnameVerifier() {
                        @Override
                        public boolean verify(String hostname, SSLSession session) {
                            return true;
                        }
                    });
                }
                conn.setRequestMethod(currentMethod);
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", UA);
                conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
                conn.setRequestProperty("Referer", base + "/jsxsd/");
                String cookieHeader = cookieHeader();
                if (!cookieHeader.isEmpty()) {
                    conn.setRequestProperty("Cookie", cookieHeader);
                }
                if (currentBody != null) {
                    byte[] bytes = currentBody.getBytes(StandardCharsets.UTF_8);
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
                    conn.setFixedLengthStreamingMode(bytes.length);
                    OutputStream out = conn.getOutputStream();
                    out.write(bytes);
                    out.flush();
                    out.close();
                }
                int code = conn.getResponseCode();
                captureCookies(conn);
                if (code >= 300 && code < 400) {
                    String location = conn.getHeaderField("Location");
                    if (location != null && !location.isEmpty()) {
                        current = new URL(url, location).toString();
                        if (code != 307 && code != 308) {
                            currentMethod = "GET";
                            currentBody = null;
                        }
                        continue;
                    }
                }
                InputStream in = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
                byte[] raw = readAll(in);
                this.lastPage = decode(raw, conn.getContentType());
                return this.lastPage;
            } catch (JwglException e) {
                throw e;
            } catch (Exception e) {
                throw new JwglException("无法访问教务系统（" + base + "）：" + e.getMessage(), e);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        throw new JwglException("访问教务系统重定向次数过多：" + target);
    }

    private static String appendQuery(String url, Map<String, String> query) {
        StringBuilder sb = new StringBuilder(url);
        sb.append(url.contains("?") ? "&" : "?");
        boolean first = true;
        for (Map.Entry<String, String> e : query.entrySet()) {
            if (!first) {
                sb.append("&");
            }
            sb.append(enc(e.getKey())).append("=").append(enc(e.getValue()));
            first = false;
        }
        return sb.toString();
    }

    private static String encodeForm(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (!first) {
                sb.append("&");
            }
            sb.append(enc(e.getKey())).append("=").append(enc(e.getValue()));
            first = false;
        }
        return sb.toString();
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception e) {
            return value == null ? "" : value;
        }
    }

    private String cookieHeader() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        return sb.toString();
    }

    private void captureCookies(HttpURLConnection conn) {
        Map<String, List<String>> headers = conn.getHeaderFields();
        if (headers == null) {
            return;
        }
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            String key = e.getKey();
            if (key == null || !key.equalsIgnoreCase("Set-Cookie") || e.getValue() == null) {
                continue;
            }
            for (String raw : e.getValue()) {
                if (raw == null) {
                    continue;
                }
                int semi = raw.indexOf(';');
                String pair = (semi >= 0 ? raw.substring(0, semi) : raw).trim();
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    cookies.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        if (in == null) {
            return new byte[0];
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, n);
        }
        in.close();
        return buffer.toByteArray();
    }

    private static String decode(byte[] raw, String contentType) {
        Charset charset = StandardCharsets.UTF_8;
        Matcher m = CHARSET_RE.matcher(contentType == null ? "" : contentType);
        if (m.find()) {
            try {
                charset = Charset.forName(m.group(1));
            } catch (Exception ignored) {
                charset = StandardCharsets.UTF_8;
            }
        }
        return new String(raw, charset);
    }

    // ------------------------------------------------------------------ //
    // 工具方法
    // ------------------------------------------------------------------ //

    /** 复刻站点 conwork.js 的 encodeInp：base64(账号) %%% base64(密码)。 */
    public static String encodeAccount(String user, String password) {
        String account = Base64.encodeToString(
                (user == null ? "" : user).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String pass = Base64.encodeToString(
                (password == null ? "" : password).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        return account + "%%%" + pass;
    }

    public static boolean isLoginPage(String html) {
        if (html == null || html.isEmpty()) {
            return false;
        }
        for (String marker : LOGIN_MARKERS) {
            if (!html.contains(marker)) {
                return false;
            }
        }
        return html.contains("xk/LoginToXk");
    }

    public static String extractMessage(String html) {
        if (html == null) {
            return "";
        }
        Matcher m = SHOWMSG_RE.matcher(html);
        if (m.find()) {
            String text = TAG_RE.matcher(m.group(1)).replaceAll("").replace("&nbsp;", " ").trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return "";
    }

    public String base() {
        return base;
    }

    public List<String> logs() {
        return logs;
    }

    public String lastPage() {
        return lastPage;
    }

    public boolean isLoggedIn() {
        return loggedIn;
    }

    // ------------------------------------------------------------------ //
    // 登录（一次性提交，登录页无验证码）
    // ------------------------------------------------------------------ //

    /**
     * 登录教务系统官网。
     *
     * @throws LoginException 账号 / 密码错误等登录失败（message 为教务系统原文提示）
     * @throws JwglException  网络不可达、页面结构异常等
     */
    public void login(String studentId, String password) {
        logs.clear();
        loggedIn = false;
        logs.add("正在连接教务系统官网 ...");
        String html = get("/jsxsd/", null);
        if (html.contains("cas.nustti.edu.cn") || html.contains("login_slogin")) {
            throw new JwglException("教务系统跳转到统一身份认证(CAS)页面，当前版本暂不支持");
        }
        if (!isLoginPage(html)) {
            logs.add("当前会话已登录，跳过登录步骤");
            loggedIn = true;
            return;
        }
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("userAccount", studentId);
        payload.put("userPassword", "");
        payload.put("encoded", encodeAccount(studentId, password));
        payload.put("pwdstr1", "");
        payload.put("pwdstr2", "");
        logs.add("正在提交登录信息 ...");
        html = post("/jsxsd/xk/LoginToXk", payload);
        if (isLoginPage(html)) {
            String message = extractMessage(html);
            throw new LoginException(message.isEmpty() ? "登录失败：学号或密码错误" : "登录失败：" + message);
        }
        loggedIn = true;
        logs.add("登录成功");
    }

    /** 退出登录（仅清理本地会话状态）。 */
    public void logout() {
        cookies.clear();
        loggedIn = false;
        lastPage = "";
        logs.clear();
    }

    // ------------------------------------------------------------------ //
    // 课表
    // ------------------------------------------------------------------ //

    /** 打开课表页面；指定学期时按 POST → POST → GET 顺序尝试，返回含课表数据的页面。 */
    private String openKbPage(String term) {
        String url = "/jsxsd/xskb/xskb_list.do";
        if (term == null || term.isEmpty()) {
            return get(url, null);
        }
        Map<String, String> withZs = new LinkedHashMap<>();
        withZs.put("xnxq01id", term);
        withZs.put("zs", "1");
        String html = post(url, withZs);
        if (hasTimetableData(html)) {
            return html;
        }
        Map<String, String> onlyTerm = new LinkedHashMap<>();
        onlyTerm.put("xnxq01id", term);
        html = post(url, onlyTerm);
        if (hasTimetableData(html)) {
            return html;
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("xnxq01id", term);
        return get(url, query);
    }

    /** 判定页面是否真的包含课表数据：仅含表头骨架的空课表页（如学期无课或数据被清理）不算。 */
    private boolean hasTimetableData(String html) {
        return html.contains("kbtable") && html.contains("kbcontent1");
    }

    /** 拉取并解析课表。term 为空表示教务系统当前学期。 */
    public TimetableResult fetchTimetable(String term) {
        logs.add("正在读取课表数据 ...");
        String html;
        try {
            html = openKbPage(term);
        } catch (JwglException e) {
            throw e;
        } catch (Exception e) {
            throw new JwglException("获取课表失败：" + e.getMessage(), e);
        }
        this.lastPage = html;
        if (isLoginPage(html)) {
            loggedIn = false;
            throw new LoginException("登录状态已失效，请重新登录");
        }
        TimetableResult result = TimetableParser.parseHtml(html);
        if ((result.courses == null || result.courses.isEmpty())
                && term != null && !term.isEmpty()) {
            // 指定学期无课表（如学期已切换、旧学期数据被清理）：回退到教务系统当前学期重试
            logs.add("学期 " + term + " 未解析出课程，回退到当前学期重试 ...");
            term = null;
            html = openKbPage(null);
            this.lastPage = html;
            if (isLoginPage(html)) {
                loggedIn = false;
                throw new LoginException("登录状态已失效，请重新登录");
            }
            result = TimetableParser.parseHtml(html);
        }
        if (result.courses == null || result.courses.isEmpty()) {
            String detail = (result.meta == null || result.meta.error == null) ? "" : result.meta.error;
            throw new JwglException("未能在课表页面中识别出课程数据"
                    + (detail.isEmpty() ? "（可能该学期无课表，或教务系统页面结构有变）" : "（" + detail + "）"));
        }
        result.terms = TimetableParser.parseTerms(html);
        if (term == null || term.isEmpty()) {
            String active = TimetableParser.parseActiveTerm(html);
            if (!active.isEmpty()) {
                result.term = active;
            } else if (!result.terms.isEmpty()) {
                result.term = result.terms.get(0).value;
            }
        } else {
            result.term = term;
        }
        logs.add("课表读取完成，共 " + result.courses.size() + " 条课程记录");
        return result;
    }
}
