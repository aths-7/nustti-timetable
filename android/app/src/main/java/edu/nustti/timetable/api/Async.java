package edu.nustti.timetable.api;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 极简异步工具：后台线程执行网络请求，结果回主线程。 */
public final class Async {

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Task<T> {
        T run() throws Exception;
    }

    public interface Done<T> {
        void onResult(T value);
    }

    public interface Fail {
        void onError(Exception e);
    }

    private Async() {
    }

    public static <T> void run(final Task<T> task, final Done<T> done, final Fail fail) {
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final T value = task.run();
                    if (done != null) {
                        MAIN.post(new Runnable() {
                            @Override
                            public void run() {
                                done.onResult(value);
                            }
                        });
                    }
                } catch (final Exception e) {
                    if (fail != null) {
                        MAIN.post(new Runnable() {
                            @Override
                            public void run() {
                                fail.onError(e);
                            }
                        });
                    }
                }
            }
        });
    }
}
