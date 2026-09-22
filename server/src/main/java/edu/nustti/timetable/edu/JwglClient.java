package edu.nustti.timetable.edu;

import edu.nustti.timetable.model.TimetableResult;
import edu.nustti.timetable.parse.TimetableParser;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 南京理工大学泰州科技学院教务系统（正方 jsxsd）在线课表同步客户端。
 *
 * <p>接口事实（与 Kivy 版 jwgl_client.py 保持一致，均已在本机实测确认）：</p>
 * <pre>
 * 登录页     GET  https://jwgl.nustti.edu.cn/jsxsd/
 * 登录提交   POST https://jwgl.nustti.edu.cn/jsxsd/xk/LoginToXk
 *            字段：userAccount / userPassword / encoded / pwdstr1 / pwdstr2 / [验证码字段]
 *            encoded = base64(学号) + "%%%" + base64(密码)   （站点 conwork.js 的 encodeInp）
 * 验证码     GET  https://jwgl.nustti.edu.cn/jsxsd/verifycode.servlet?t=随机数   （image/jpeg）
 * 学生课表   GET/POST https://jwgl.nustti.edu.cn/jsxsd/xskb/xskb_list.do  学期参数：xnxq01id
 * </pre>
 *
 * <p>本类不包含任何账号凭据；账号密码由客户端从界面输入后经接口传入，仅在服务端会话内短暂保留。</p>
 */
public class JwglClient {

    public static final String DEFAULT_BASE = "https://jwgl.nustti.edu.cn";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final String[] LOGIN_MARKERS = {"LoginToXk", "userAccount"};

    private static final Pattern SHOWMSG_RE =
            Pattern.compile("id\\s*=\\s*[\"']showMsg[\"'][^>]*>(.*?)<", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CAPTCHA_FIELD_RE = Pattern.compile(
            "<input[^>]+name\\s*=\\s*[\"']?(SafeCode|verifycode|captcha|yzm|checkcode|validatecode|randomcode)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SAFECODE_IMG_RE =
            Pattern.compile("<img[^>]+id\\s*=\\s*[\"']?SafeCodeImg", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHARSET_RE = Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG_RE = Pattern.compile("<[^>]+>");

    private final String base;
    private final HttpClient http;
    private final List<String> logs = new ArrayList<>();
    private String lastPage = "";

    public JwglClient() {
        this(DEFAULT_BASE);
    }

    public JwglClient(String baseUrl) {
        String b = (baseUrl == null || baseUrl.trim().isEmpty()) ? DEFAULT_BASE : baseUrl.trim();
        this.base = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        this.http = buildHttpClient();
    }

    // ------------------------------------------------------------------ //
    // 基础请求
    // ------------------------------------------------------------------ //

    private static HttpClient buildHttpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15));
        try {
            // 校园网教务系统常使用自签名 / 内网证书链，这里放行证书校验（仅访问用户指定的教务系统）
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
            SSLParameters params = ctx.getDefaultSSLParameters();
            params.setEndpointIdentificationAlgorithm("");
            builder.sslContext(ctx).sslParameters(params);
        } catch (Exception ignored) {
            // 放行失败则退回默认校验
        }
        return builder.build();
    }

    private String url(String path, Map<String, String> query) {
        String full = path.startsWith("http") ? path : base + path;
        if (query == null || query.isEmpty()) {
            return full;
        }
        String qs = query.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        return full + (full.contains("?") ? "&" : "?") + qs;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String get(String path, Map<String, String> query) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url(path, query)))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Referer", base + "/jsxsd/")
                .GET()
                .build();
        return send(request);
    }

    private String post(String path, Map<String, String> form) {
        String body = form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url(path, null)))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Referer", base + "/jsxsd/")
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return send(request);
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<byte[]> resp = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            this.lastPage = decode(resp);
            return this.lastPage;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JwglException("访问教务系统被中断：" + e.getMessage(), e);
        } catch (Exception e) {
            throw new JwglException("无法访问教务系统（" + base + "）：" + e.getMessage(), e);
        }
    }

    private static String decode(HttpResponse<byte[]> resp) {
        Charset charset = StandardCharsets.UTF_8;
        String contentType = resp.headers().firstValue("content-type").orElse("");
        Matcher m = CHARSET_RE.matcher(contentType);
        if (m.find()) {
            try {
                charset = Charset.forName(m.group(1));
            } catch (Exception ignored) {
                charset = StandardCharsets.UTF_8;
            }
        }
        return new String(resp.body(), charset);
    }

    private byte[] fetchBytes(String path, Map<String, String> query) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url(path, query)))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", UA)
                .header("Referer", base + "/jsxsd/")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> resp = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JwglException("获取验证码被中断：" + e.getMessage(), e);
        } catch (Exception e) {
            throw new JwglException("获取验证码失败：" + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ //
    // 工具方法
    // ------------------------------------------------------------------ //

    /** 复刻站点 conwork.js 的 encodeInp：base64(账号) %%% base64(密码)。 */
    public static String encodeAccount(String user, String password) {
        Base64.Encoder encoder = Base64.getEncoder();
        String account = encoder.encodeToString((user == null ? "" : user).getBytes(StandardCharsets.UTF_8));
        String pass = encoder.encodeToString((password == null ? "" : password).getBytes(StandardCharsets.UTF_8));
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

    /**
     * 仅在页面中真实存在验证码输入框时才返回字段名。
     * 登录页 JS 里总会提到 ReShowCode / verifycode.servlet，不能据此判定"需要验证码"。
     */
    public static String detectCaptchaField(String html) {
        if (html == null) {
            return "";
        }
        Matcher m = CAPTCHA_FIELD_RE.matcher(html);
        if (m.find()) {
            return m.group(1);
        }
        if (SAFECODE_IMG_RE.matcher(html).find()) {
            return "SafeCode";
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

    // ------------------------------------------------------------------ //
    // 登录（两步式：先无验证码提交，教务返回"需要验证码"后再带验证码提交）
    // ------------------------------------------------------------------ //

    /** 需要验证码时返回该结果，客户端应展示验证码图片并让用户填写后再次提交。 */
    public static class LoginStep {
        public boolean loggedIn;
        public boolean needCaptcha;
        public String captchaField = "";
        public String message = "";

        static LoginStep ok() {
            LoginStep step = new LoginStep();
            step.loggedIn = true;
            return step;
        }

        static LoginStep captcha(String field, String message) {
            LoginStep step = new LoginStep();
            step.needCaptcha = true;
            step.captchaField = field == null ? "" : field;
            step.message = message == null ? "" : message;
            return step;
        }

        static LoginStep fail(String message) {
            LoginStep step = new LoginStep();
            step.message = message == null ? "" : message;
            return step;
        }
    }

    /** 打开登录页（建立会话 Cookie）。返回 true 表示当前会话已登录，无需再登录。 */
    public boolean openLoginPage() {
        logs.add("正在连接教务系统 ...");
        String html = get("/jsxsd/", null);
        if (html.contains("cas.nustti.edu.cn") || html.contains("login_slogin")) {
            throw new JwglException("教务系统跳转到统一身份认证(CAS)页面，当前版本暂不支持，请使用演示数据或联系维护者");
        }
        if (!isLoginPage(html)) {
            logs.add("当前会话已登录，跳过登录步骤");
            return true;
        }
        return false;
    }

    /** 第一步：不带验证码提交登录。 */
    public LoginStep loginSubmit(String studentId, String password) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("userAccount", studentId);
        payload.put("userPassword", "");
        payload.put("encoded", encodeAccount(studentId, password));
        payload.put("pwdstr1", "");
        payload.put("pwdstr2", "");
        logs.add("正在提交登录信息 ...");
        String html = post("/jsxsd/xk/LoginToXk", payload);
        if (!isLoginPage(html)) {
            logs.add("登录成功");
            return LoginStep.ok();
        }
        String captchaField = detectCaptchaField(html);
        String message = extractMessage(html);
        if (!captchaField.isEmpty()) {
            logs.add("教务系统要求输入验证码");
            return LoginStep.captcha(captchaField, message);
        }
        return LoginStep.fail(message.isEmpty() ? "登录失败：账号或密码错误" : "登录失败：" + message);
    }

    /** 第二步：携带验证码提交登录。 */
    public LoginStep loginSubmitWithCaptcha(String studentId, String password, String captchaField, String captcha) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("userAccount", studentId);
        payload.put("userPassword", "");
        payload.put("encoded", encodeAccount(studentId, password));
        payload.put("pwdstr1", "");
        payload.put("pwdstr2", "");
        if (captchaField != null && !captchaField.isEmpty()) {
            payload.put(captchaField, captcha == null ? "" : captcha);
        }
        logs.add("正在提交登录信息（含验证码）...");
        String html = post("/jsxsd/xk/LoginToXk", payload);
        if (!isLoginPage(html)) {
            logs.add("登录成功");
            return LoginStep.ok();
        }
        String message = extractMessage(html);
        if (message.contains("验证码")) {
            return LoginStep.captcha(detectCaptchaField(html), "验证码不正确，请重新输入（注意区分大小写）");
        }
        if (!message.isEmpty()) {
            return LoginStep.fail("登录失败：" + message);
        }
        return LoginStep.captcha(detectCaptchaField(html), "登录未通过，请重新输入验证码");
    }

    /** 获取验证码图片（JPEG 字节）。 */
    public byte[] fetchCaptcha() {
        byte[] data = fetchBytes("/jsxsd/verifycode.servlet",
                java.util.Collections.singletonMap("t", String.format("%.6f", Math.random())));
        if (data == null || data.length == 0) {
            throw new JwglException("教务系统未返回验证码图片");
        }
        return data;
    }

    // ------------------------------------------------------------------ //
    // 课表
    // ------------------------------------------------------------------ //

    /** 打开课表页面；指定学期时按 POST → GET 顺序尝试，返回含课表表格的页面。 */
    private String openKbPage(String term) {
        String url = "/jsxsd/xskb/xskb_list.do";
        if (term == null || term.isEmpty()) {
            return get(url, null);
        }
        Map<String, String> withZs = new LinkedHashMap<>();
        withZs.put("xnxq01id", term);
        withZs.put("zs", "1");
        String html = post(url, withZs);
        if (html.contains("kbtable")) {
            return html;
        }
        Map<String, String> onlyTerm = new LinkedHashMap<>();
        onlyTerm.put("xnxq01id", term);
        html = post(url, onlyTerm);
        if (html.contains("kbtable")) {
            return html;
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("xnxq01id", term);
        return get(url, query);
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
            throw new LoginException("登录状态已失效，请重新登录");
        }
        TimetableResult result = TimetableParser.parseHtml(html);
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
