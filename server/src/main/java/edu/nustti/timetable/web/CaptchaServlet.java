package edu.nustti.timetable.web;

import edu.nustti.timetable.edu.JwglClient;
import edu.nustti.timetable.edu.JwglException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.io.OutputStream;

/**
 * GET /api/captcha —— 返回教务系统当前会话的验证码图片（image/jpeg）。
 *
 * <p>客户端把返回的字节流直接显示为图片，用户填写后随 /api/login 一起提交。</p>
 */
public class CaptchaServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession(true);
        String base = JsonUtil.param(req, null, "base", null);
        JwglClient client = (JwglClient) session.getAttribute(SessionKeys.CLIENT);
        if (client == null || (base != null && !base.equals(session.getAttribute(SessionKeys.BASE)))) {
            String useBase = base != null ? base : JwglClient.DEFAULT_BASE;
            client = new JwglClient(useBase);
            session.setAttribute(SessionKeys.CLIENT, client);
            session.setAttribute(SessionKeys.BASE, useBase);
            session.setAttribute(SessionKeys.LOGGED_IN, false);
            try {
                client.openLoginPage();
            } catch (JwglException e) {
                writeText(resp, e.getMessage());
                return;
            }
        }
        try {
            byte[] image = client.fetchCaptcha();
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType("image/jpeg");
            resp.setHeader("Cache-Control", "no-store");
            try (OutputStream out = resp.getOutputStream()) {
                out.write(image);
            }
        } catch (JwglException e) {
            writeText(resp, e.getMessage());
        }
    }

    private void writeText(HttpServletResponse resp, String message) throws IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/plain;charset=UTF-8");
        resp.getWriter().write("验证码获取失败：" + message);
    }
}
