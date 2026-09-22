package edu.nustti.timetable.web;

import com.google.gson.JsonObject;
import edu.nustti.timetable.edu.JwglClient;
import edu.nustti.timetable.edu.JwglException;
import edu.nustti.timetable.edu.LoginException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POST /api/login —— 登录教务系统（两步式，验证码由客户端弹出让用户人工输入）。
 *
 * <pre>
 * 第 1 次请求  {"studentId":"...","password":"..."}
 *   → {"ok":false,"needCaptcha":true,"message":"..."}   教务系统要求验证码
 * 第 2 次请求  {"studentId":"...","password":"...","captcha":"AB12"}
 *   → {"ok":true,"loggedIn":true}
 * </pre>
 *
 * 也支持 GET 便于浏览器冒烟测试：/api/login?studentId=..&password=..&captcha=..
 */
public class LoginServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        handle(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        handle(req, resp);
    }

    private void handle(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession(true);
        JsonObject json = JsonUtil.readJson(req);

        String base = JsonUtil.param(req, json, "base", JwglClient.DEFAULT_BASE);
        String studentId = JsonUtil.param(req, json, "studentId", "");
        String password = JsonUtil.param(req, json, "password", "");
        String captcha = JsonUtil.param(req, json, "captcha", "");

        if (studentId.isEmpty() || password.isEmpty()) {
            JsonUtil.fail(resp, "请填写学号与密码");
            return;
        }

        JwglClient client = (JwglClient) session.getAttribute(SessionKeys.CLIENT);
        if (client == null || !base.equals(session.getAttribute(SessionKeys.BASE))) {
            client = new JwglClient(base);
            session.setAttribute(SessionKeys.CLIENT, client);
            session.setAttribute(SessionKeys.BASE, base);
            session.setAttribute(SessionKeys.LOGGED_IN, false);
        }

        try {
            if (!Boolean.TRUE.equals(session.getAttribute(SessionKeys.LOGGED_IN))) {
                if (client.openLoginPage()) {
                    session.setAttribute(SessionKeys.LOGGED_IN, true);
                }
            }
            if (Boolean.TRUE.equals(session.getAttribute(SessionKeys.LOGGED_IN))) {
                respondOk(resp, session, client, "当前会话已登录");
                return;
            }

            JwglClient.LoginStep step;
            if (!captcha.isEmpty()) {
                String field = (String) session.getAttribute(SessionKeys.CAPTCHA_FIELD);
                step = client.loginSubmitWithCaptcha(studentId, password,
                        field == null ? "SafeCode" : field, captcha);
            } else {
                step = client.loginSubmit(studentId, password);
            }

            if (step.loggedIn) {
                session.setAttribute(SessionKeys.LOGGED_IN, true);
                session.removeAttribute(SessionKeys.PENDING_USER);
                session.removeAttribute(SessionKeys.PENDING_PASSWORD);
                session.removeAttribute(SessionKeys.CAPTCHA_FIELD);
                respondOk(resp, session, client, "登录成功");
                return;
            }

            if (step.needCaptcha) {
                session.setAttribute(SessionKeys.PENDING_USER, studentId);
                session.setAttribute(SessionKeys.PENDING_PASSWORD, password);
                session.setAttribute(SessionKeys.CAPTCHA_FIELD, step.captchaField);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("ok", false);
                body.put("needCaptcha", true);
                body.put("captchaField", step.captchaField);
                body.put("message", step.message.isEmpty() ? "教务系统要求输入验证码" : step.message);
                JsonUtil.ok(resp, body);
                return;
            }
            JsonUtil.fail(resp, step.message.isEmpty() ? "登录失败" : step.message);
        } catch (LoginException e) {
            session.setAttribute(SessionKeys.LOGGED_IN, false);
            JsonUtil.fail(resp, e.getMessage());
        } catch (JwglException e) {
            JsonUtil.fail(resp, e.getMessage());
        }
    }

    private void respondOk(HttpServletResponse resp, HttpSession session, JwglClient client, String message)
            throws IOException {
        Map<String, Object> body = JsonUtil.okMap();
        body.put("loggedIn", true);
        body.put("message", message);
        body.put("jwglBase", client.base());
        body.put("logs", client.logs());
        JsonUtil.ok(resp, body);
    }
}
