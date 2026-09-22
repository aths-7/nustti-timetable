package edu.nustti.timetable.web;

import edu.nustti.timetable.edu.JwglClient;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/** GET /api/health —— 服务存活与登录状态探测。 */
public class HealthServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> body = JsonUtil.okMap();
        body.put("service", "nustti-timetable-server");
        body.put("version", "1.0.0");
        body.put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        Object client = req.getSession().getAttribute(SessionKeys.CLIENT);
        JwglClient jwgl = client instanceof JwglClient ? (JwglClient) client : null;
        body.put("jwglBase", jwgl == null ? JwglClient.DEFAULT_BASE : jwgl.base());
        body.put("loggedIn", Boolean.TRUE.equals(req.getSession().getAttribute(SessionKeys.LOGGED_IN)));
        JsonUtil.ok(resp, body);
    }
}
