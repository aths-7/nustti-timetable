package edu.nustti.timetable.web;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

/**
 * GET /api/demo/timetable —— 演示课表（无需教务系统账号）。
 *
 * <p>用于验证「服务端 → 客户端 → 三种视图」整条链路，含"同一格多门课"样本。</p>
 */
public class DemoServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> body = JsonUtil.okMap();
        body.put("data", edu.nustti.timetable.parse.DemoData.build());
        JsonUtil.ok(resp, body);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        doGet(req, resp);
    }
}
