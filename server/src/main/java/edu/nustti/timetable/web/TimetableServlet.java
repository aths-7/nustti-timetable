package edu.nustti.timetable.web;

import com.google.gson.JsonObject;
import edu.nustti.timetable.edu.JwglClient;
import edu.nustti.timetable.edu.JwglException;
import edu.nustti.timetable.edu.LoginException;
import edu.nustti.timetable.model.TimetableResult;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /api/timetable?term=2026-2027-1 —— 返回教务系统课表（JSON）。
 *
 * <p>需先通过 /api/login 登录；term 省略时取教务系统当前学期。</p>
 */
public class TimetableServlet extends HttpServlet {

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
        String term = JsonUtil.param(req, json, "term", null);

        Object attr = session.getAttribute(SessionKeys.CLIENT);
        if (!(attr instanceof JwglClient) || !Boolean.TRUE.equals(session.getAttribute(SessionKeys.LOGGED_IN))) {
            Map<String, Object> body = JsonUtil.okMap();
            body.put("ok", false);
            body.put("needLogin", true);
            body.put("message", "请先登录教务系统（或使用演示数据）");
            JsonUtil.ok(resp, body);
            return;
        }

        JwglClient client = (JwglClient) attr;
        try {
            TimetableResult result = client.fetchTimetable(term == null ? "" : term);
            Map<String, Object> body = JsonUtil.okMap();
            body.put("data", result);
            body.put("logs", client.logs());
            JsonUtil.ok(resp, body);
        } catch (LoginException e) {
            session.setAttribute(SessionKeys.LOGGED_IN, false);
            Map<String, Object> body = JsonUtil.okMap();
            body.put("ok", false);
            body.put("needLogin", true);
            body.put("message", e.getMessage());
            JsonUtil.ok(resp, body);
        } catch (JwglException e) {
            JsonUtil.fail(resp, e.getMessage());
        }
    }
}
