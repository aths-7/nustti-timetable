package edu.nustti.timetable.web;

/** HTTP 会话属性名集中定义，避免各处硬编码字符串。 */
final class SessionKeys {

    /** edu.nustti.timetable.edu.JwglClient：持有教务系统会话 Cookie */
    static final String CLIENT = "jwglClient";
    /** 教务系统基地址 */
    static final String BASE = "jwglBase";
    /** 登录是否成功 */
    static final String LOGGED_IN = "loggedIn";
    /** 待验证码确认时的学号 / 密码（仅内存，登出即清） */
    static final String PENDING_USER = "pendingUser";
    static final String PENDING_PASSWORD = "pendingPassword";
    /** 教务系统要求填写的验证码字段名 */
    static final String CAPTCHA_FIELD = "captchaField";

    private SessionKeys() {
    }
}
