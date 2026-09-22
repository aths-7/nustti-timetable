package edu.nustti.timetable.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import edu.nustti.timetable.model.TimetableResult;

/**
 * 课表服务端 HTTP 客户端。
 *
 * <p>接口（均由本工程 server 提供）：</p>
 * <pre>
 * GET  /api/health
 * GET  /api/captcha              → image/jpeg（教务系统验证码，交给用户人工填写）
 * POST /api/login                → {"ok":true} | {"ok":false,"needCaptcha":true} | {"ok":false,"message":"..."}
 * GET  /api/timetable?term=...   → {"ok":true,"data":{courses,term,terms,meta}}
 * GET  /api/demo/timetable       → 演示课表，无需登录
 * POST /api/logout
 * </pre>
 *
 * 服务端用 HttpSession 保存教务系统会话，这里手动维护 JSESSIONID Cookie。
 */
public final class ApiClient {

    private static final Gson GSON = new Gson();
    private static final Pattern CHARSET_RE = Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE);
    private static final String COOKIE_NAME = "JSESSIONID";

    private static volatile String sessionCookie;

    private ApiClient() {
    }

    public static void clearSession() {
        sessionCookie = null;
    }

    public static boolean hasSession() {
        return sessionCookie != null;
    }

    /** 统一响应包装。 */
    public static class Result {
        public boolean ok;
        public boolean needCaptcha;
        public boolean needLogin;
        public String captchaField = "";
        public String message = "";
        public JsonObject json;
        public TimetableResult data;
    }

    public static String normalizeBase(String base) {
        String b = base == null ? "" : base.trim();
        if (b.isEmpty()) {
            return "";
        }
        if (!b.startsWith("http://") && !b.startsWith("https://")) {
            b = "http://" + b;
        }
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }

    // ------------------------------------------------------------------ //
    // 业务接口
    // ------------------------------------------------------------------ //

    public static Result login(String base, String studentId, String password, String captcha) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("studentId", studentId);
        payload.addProperty("password", password);
        if (captcha != null && !captcha.isEmpty()) {
            payload.addProperty("captcha", captcha);
        }
        return parseResult(request(normalizeBase(base), "/api/login", "POST", payload.toString()));
    }

    public static Result logout(String base) throws IOException {
        return parseResult(request(normalizeBase(base), "/api/logout", "POST", "{}"));
    }

    public static byte[] captcha(String base) throws IOException {
        String url = normalizeBase(base) + "/api/captcha?t=" + System.currentTimeMillis();
        HttpURLConnection conn = open(url, "GET");
        try {
            captureCookie(conn);
            int code = conn.getResponseCode();
            if (code >= 400) {
                throw new IOException("验证码请求失败：HTTP " + code);
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            in.close();
            byte[] data = buffer.toByteArray();
            String head = new String(data, 0, Math.min(200, data.length), StandardCharsets.UTF_8);
            if (head.startsWith("验证码获取失败") || head.startsWith("请先")) {
                throw new IOException(head.trim());
            }
            return data;
        } finally {
            conn.disconnect();
        }
    }

    public static Result timetable(String base, String term) throws IOException {
        String path = "/api/timetable";
        if (term != null && !term.isEmpty()) {
            path += "?term=" + URLEncoder.encode(term, StandardCharsets.UTF_8);
        }
        return parseResult(request(normalizeBase(base), path, "GET", null));
    }

    public static Result demo(String base) throws IOException {
        return parseResult(request(normalizeBase(base), "/api/demo/timetable", "GET", null));
    }

    // ------------------------------------------------------------------ //
    // 底层 HTTP
    // ------------------------------------------------------------------ //

    private static String request(String base, String path, String method, String jsonBody) throws IOException {
        if (base.isEmpty()) {
            throw new IOException("请先填写服务端地址");
        }
        HttpURLConnection conn = open(base + path, method);
        try {
            if (jsonBody != null) {
                byte[] out = jsonBody.getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(out.length);
                OutputStream os = conn.getOutputStream();
                os.write(out);
                os.flush();
                os.close();
            }
            int code = conn.getResponseCode();
            captureCookie(conn);
            String body = readBody(conn, code);
            if (code >= 400) {
                throw new IOException("服务端返回 HTTP " + code + "：" + trim(body));
            }
            if (body == null || body.trim().isEmpty()) {
                throw new IOException("服务端返回空响应");
            }
            return body;
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String method) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(40000);
        conn.setRequestProperty("Accept", "application/json, image/*, */*");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        if (jsonBody(conn)) {
            conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
        }
        if (sessionCookie != null) {
            conn.setRequestProperty("Cookie", COOKIE_NAME + "=" + sessionCookie);
        }
        return conn;
    }

    private static boolean jsonBody(HttpURLConnection conn) {
        return "POST".equalsIgnoreCase(conn.getRequestMethod());
    }

    private static String readBody(HttpURLConnection conn, int code) throws IOException {
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        in.close();
        byte[] bytes = buffer.toByteArray();
        Charset charset = StandardCharsets.UTF_8;
        String contentType = conn.getContentType();
        if (contentType != null) {
            Matcher m = CHARSET_RE.matcher(contentType);
            if (m.find()) {
                try {
                    charset = Charset.forName(m.group(1));
                } catch (Exception ignored) {
                    charset = StandardCharsets.UTF_8;
                }
            }
        }
        return new String(bytes, charset);
    }

    private static void captureCookie(HttpURLConnection conn) {
        Map<String, List<String>> headers = conn.getHeaderFields();
        if (headers == null) {
            return;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() == null || !"Set-Cookie".equalsIgnoreCase(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            for (String value : entry.getValue()) {
                if (value == null || !value.toUpperCase(Locale.ROOT).startsWith(COOKIE_NAME + "=")) {
                    continue;
                }
                String cookie = value.substring(COOKIE_NAME.length() + 1);
                int semi = cookie.indexOf(';');
                sessionCookie = semi > 0 ? cookie.substring(0, semi) : cookie;
            }
        }
    }

    private static Result parseResult(String body) throws IOException {
        Result result = new Result();
        JsonObject json;
        try {
            json = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            throw new IOException("服务端返回内容无法解析：" + trim(body));
        }
        result.json = json;
        result.ok = json.has("ok") && json.get("ok").getAsBoolean();
        result.needCaptcha = json.has("needCaptcha") && json.get("needCaptcha").getAsBoolean();
        result.needLogin = json.has("needLogin") && json.get("needLogin").getAsBoolean();
        result.captchaField = json.has("captchaField") ? json.get("captchaField").getAsString() : "";
        result.message = json.has("message") ? json.get("message").getAsString() : "";
        if (json.has("data") && json.get("data").isJsonObject()) {
            result.data = GSON.fromJson(json.get("data"), TimetableResult.class);
            if (result.data != null && result.data.meta == null) {
                result.data.meta = new TimetableResult.Meta();
            }
        }
        return result;
    }

    private static String trim(String text) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        return t.length() > 300 ? t.substring(0, 300) + " ..." : t;
    }
}
