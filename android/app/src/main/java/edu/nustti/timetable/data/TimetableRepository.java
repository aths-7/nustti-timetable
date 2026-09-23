package edu.nustti.timetable.data;

import android.content.Context;

import com.google.gson.Gson;

import edu.nustti.timetable.edu.JwglClient;
import edu.nustti.timetable.edu.JwglSession;
import edu.nustti.timetable.edu.LoginException;
import edu.nustti.timetable.model.TimetableResult;
import edu.nustti.timetable.parse.DemoData;

/**
 * 课表数据仓库：直接向南京理工大学泰州科技学院教务系统官网抓取课表、写入本地缓存、读取缓存。
 *
 * <p>客户端不再依赖任何中间服务端；登录会话保存在 {@link JwglSession}（进程内），
 * App 重启后会用本地保存的学号 / 密码自动重新登录一次。</p>
 */
public class TimetableRepository {

    private static final Gson GSON = new Gson();

    private final SessionStore store;

    public TimetableRepository(Context context) {
        this.store = new SessionStore(context);
    }

    public SessionStore store() {
        return store;
    }

    /** 直连教务系统官网拉取课表；会话失效时用本地凭据自动重登一次。 */
    public TimetableResult fetchFromJwgl() throws Exception {
        JwglClient client = JwglSession.current();
        if (client == null || !client.isLoggedIn()) {
            client = loginJwgl();
        }
        TimetableResult data;
        try {
            data = client.fetchTimetable(store.getTerm());
        } catch (LoginException e) {
            client = loginJwgl();
            data = client.fetchTimetable(store.getTerm());
        }
        if (data.term != null && !data.term.isEmpty()) {
            store.setTerm(data.term, termLabel(data));
        }
        cache(data, "jwgl");
        return data;
    }

    /** 用本地保存的学号 / 密码登录教务系统官网，成功后把会话放入 {@link JwglSession}。 */
    public JwglClient loginJwgl() throws Exception {
        String studentId = store.getStudentId();
        String password = store.getPassword();
        if (studentId.isEmpty() || password.isEmpty()) {
            throw new IllegalStateException("请先填写学号与密码");
        }
        JwglClient client = new JwglClient(store.getBaseUrl());
        client.login(studentId, password);
        JwglSession.set(client);
        return client;
    }

    /** 演示课表：本地生成，无需网络与登录。 */
    public TimetableResult fetchDemo() throws Exception {
        TimetableResult data = DemoData.build();
        cache(data, "demo");
        return data;
    }

    public void cache(TimetableResult data, String from) {
        store.saveTimetable(GSON.toJson(data), from);
    }

    /** 读取本地缓存，无缓存返回 null。 */
    public TimetableResult cached() {
        String json = store.loadTimetableJson();
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            TimetableResult data = GSON.fromJson(json, TimetableResult.class);
            if (data == null) {
                return null;
            }
            if (data.meta == null) {
                data.meta = new TimetableResult.Meta();
            }
            if (data.courses == null) {
                data.courses = new java.util.ArrayList<>();
            }
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    /** 取学期展示名（学期列表里匹配不到时退回学期编号本身）。 */
    private String termLabel(TimetableResult data) {
        if (data.terms != null) {
            for (TimetableResult.TermOption option : data.terms) {
                if (option.value.equals(data.term)) {
                    return option.label;
                }
            }
        }
        return data.term;
    }
}
