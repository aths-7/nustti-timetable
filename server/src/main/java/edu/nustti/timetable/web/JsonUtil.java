package edu.nustti.timetable.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** 极简 JSON 读写工具（Gson 封装）。 */
public final class JsonUtil {

    public static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    private JsonUtil() {
    }

    public static void write(HttpServletResponse resp, int status, Object body) throws IOException {
        resp.setStatus(status);
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(GSON.toJson(body));
    }

    public static void ok(HttpServletResponse resp, Object body) throws IOException {
        write(resp, HttpServletResponse.SC_OK, body);
    }

    /** 统一返回 {ok:false, message:"..."}，HTTP 状态仍为 200，便于客户端统一处理。 */
    public static void fail(HttpServletResponse resp, String message) throws IOException {
        fail(resp, HttpServletResponse.SC_OK, message, false);
    }

    public static void fail(HttpServletResponse resp, int status, String message, boolean needCaptcha)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("message", message == null ? "" : message);
        if (needCaptcha) {
            body.put("needCaptcha", true);
        }
        write(resp, status, body);
    }

    public static Map<String, Object> okMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", true);
        return map;
    }

    /** 读取请求体 JSON（GET / 表单提交时返回空对象）。 */
    public static JsonObject readJson(HttpServletRequest req) throws IOException {
        req.setCharacterEncoding("UTF-8");
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(req.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        String body = sb.toString().trim();
        if (body.isEmpty()) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    public static String optString(JsonObject obj, String key, String def) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return def;
        }
        String value = obj.get(key).getAsString();
        return value == null ? def : value;
    }

    /** 参数优先取 JSON 体，取不到回落到 query / form 参数。 */
    public static String param(HttpServletRequest req, JsonObject json, String key, String def) {
        String value = optString(json, key, null);
        if (value == null || value.isEmpty()) {
            value = req.getParameter(key);
        }
        return (value == null || value.isEmpty()) ? def : value;
    }
}
