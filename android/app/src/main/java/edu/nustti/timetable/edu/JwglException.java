package edu.nustti.timetable.edu;

/** 教务系统交互失败（网络异常、页面结构无法识别等）。 */
public class JwglException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JwglException(String message) {
        super(message);
    }

    public JwglException(String message, Throwable cause) {
        super(message, cause);
    }
}
