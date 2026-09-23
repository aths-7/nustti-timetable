package edu.nustti.timetable.parse;

import edu.nustti.timetable.model.Course;
import edu.nustti.timetable.model.SessionTimes;
import edu.nustti.timetable.model.TimetableResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 演示课表（与桌面版 tt_model.demo_courses 完全一致）。
 *
 * <p>用途：没有教务系统账号 / 不想联网时，也能验证「解析 → 客户端 → 三种视图」整条链路。
 * 其中最后两条是"重修 / 分班撞课"样本，用于验证同一格多门课的渲染与切换。</p>
 */
public final class DemoData {

    private DemoData() {
    }

    public static TimetableResult build() {
        TimetableResult result = new TimetableResult();
        result.term = "demo";
        result.terms = new ArrayList<>(Arrays.asList(new TimetableResult.TermOption("demo", "演示数据")));

        result.courses.add(course("高等数学A", "王建国", "教1-101", 1, 1, 2, 1, 16));
        result.courses.add(course("大学英语(3)", "李梅", "外语楼-302", 1, 5, 6, 1, 12));
        result.courses.add(course("线性代数", "张伟", "教2-205", 2, 1, 2, 1, 16));
        result.courses.add(course("大学物理", "刘洋", "理科楼-401", 2, 5, 6, 1, 14));
        result.courses.add(course("程序设计基础", "陈斌", "机房-506", 3, 3, 4, 1, 16));
        result.courses.add(course("数据结构", "赵敏", "教3-210", 3, 5, 6, 1, 10));
        result.courses.add(course("体育(羽毛球)", "孙磊", "体育馆", 4, 3, 4, 1, 16));
        result.courses.add(course("形势与政策", "周涛", "教1-208", 4, 7, 8, 1, 8));
        result.courses.add(course("操作系统", "吴强", "教2-303", 5, 1, 2, 1, 16));
        result.courses.add(course("计算机网络", "郑华", "教2-110", 5, 5, 6, 1, 12));
        result.courses.add(course("马克思主义基本原理", "何芳", "教1-305", 5, 9, 10, 1, 16));
        // 撞课样本：与上面"大学英语(3)" / "计算机网络"同一时段
        result.courses.add(course("大学英语(3)", "李梅", "外语楼-302", 3, 5, 6, 1, 12));
        result.courses.add(course("计算机网络(实验)", "郑华", "机房-301", 5, 5, 6, 1, 12));

        result.meta.minWeek = 1;
        result.meta.maxWeek = 17;
        result.meta.tables = 1;
        result.meta.headerRow = 0;
        result.meta.columns = new ArrayList<>(Arrays.asList(
                "节次", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"));
        result.meta.sessionTimes = SessionTimes.defaultsAsMap();
        return result;
    }

    private static Course course(String name, String teacher, String room, int weekday,
                                 int start, int end, int fromWeek, int toWeek) {
        List<Integer> sessions = new ArrayList<>(Arrays.asList(start, end));
        List<Integer> weeks = new ArrayList<>();
        for (int w = fromWeek; w <= toWeek; w++) {
            weeks.add(w);
        }
        Course c = new Course(name, teacher, room, weekday, sessions, weeks);
        c.weekRaw = fromWeek + "-" + toWeek + "周";
        c.raw = name + " / " + teacher + " / " + room;
        return c;
    }
}
