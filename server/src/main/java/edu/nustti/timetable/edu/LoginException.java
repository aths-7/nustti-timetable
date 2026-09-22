package edu.nustti.timetable.edu;

/** 登录失败（账号 / 密码 / 验证码错误，或登录状态失效）。 */
public class LoginException extends JwglException {

    private static final long serialVersionUID = 1L;

    public LoginException(String message) {
        super(message);
    }

    public LoginException(String message, Throwable cause) {
        super(message, cause);
    }
}
