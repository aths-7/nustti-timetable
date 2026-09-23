package edu.nustti.timetable.edu;

/** 进程内教务系统会话：保存已登录的 {@link JwglClient}，供课表仓库复用（App 重启后自动重新登录）。 */
public final class JwglSession {

    private static JwglClient client;

    private JwglSession() {
    }

    public static synchronized JwglClient current() {
        return client;
    }

    public static synchronized void set(JwglClient value) {
        client = value;
    }

    public static synchronized boolean ready() {
        return client != null && client.isLoggedIn();
    }

    public static synchronized void clear() {
        if (client != null) {
            client.logout();
        }
        client = null;
    }
}
