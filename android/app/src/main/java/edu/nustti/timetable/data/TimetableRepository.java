package edu.nustti.timetable.data;

import android.content.Context;

import com.google.gson.Gson;

import edu.nustti.timetable.api.ApiClient;
import edu.nustti.timetable.model.TimetableResult;

/** 课表数据仓库：负责从服务端拉取、写入本地缓存、读取缓存。 */
public class TimetableRepository {

    private static final Gson GSON = new Gson();

    private final SessionStore store;

    public TimetableRepository(Context context) {
        this.store = new SessionStore(context);
    }

    public SessionStore store() {
        return store;
    }

    /** 拉取正式课表（需已登录教务系统），成功后写入缓存。 */
    public TimetableResult fetchFromServer() throws Exception {
        ApiClient.Result result = ApiClient.timetable(store.getBaseUrl(), store.getTerm());
        if (!result.ok) {
            if (result.needLogin) {
                throw new IllegalStateException("服务端会话已失效，请重新登录教务系统");
            }
            throw new IllegalStateException(result.message.isEmpty() ? "课表获取失败" : result.message);
        }
        if (result.data == null) {
            throw new IllegalStateException("课表数据为空");
        }
        if (result.data.term != null && !result.data.term.isEmpty()) {
            store.setTerm(result.data.term, result.data.term);
        }
        cache(result.data, "server");
        return result.data;
    }

    /** 加载演示课表（免登录，便于无教务账号时预览界面）。 */
    public TimetableResult fetchDemo() throws Exception {
        ApiClient.Result result = ApiClient.demo(store.getBaseUrl());
        if (!result.ok || result.data == null) {
            throw new IllegalStateException(result.message.isEmpty() ? "演示课表获取失败" : result.message);
        }
        cache(result.data, "demo");
        return result.data;
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
}
