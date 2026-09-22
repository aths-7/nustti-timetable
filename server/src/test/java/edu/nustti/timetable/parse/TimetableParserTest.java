package edu.nustti.timetable.parse;

import edu.nustti.timetable.model.Course;
import edu.nustti.timetable.model.TimetableResult;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 课表解析回归测试（无需教务系统账号）。
 *
 * <p>样例覆盖：合并单元格(rowspan/colspan)、正方 div.kbcontent 完整版单元格、
 * 单双周、节次周次文本、备注行跳过、作息时间抽取。</p>
 */
public class TimetableParserTest {

    private static final String SAMPLE = ""
            + "<html><body><table id=\"kbtable\" class=\"table\">"
            + "<tr><th rowspan=\"2\">节次</th><th>星期一</th><th>星期二</th><th>星期三</th>"
            + "<th>星期四</th><th>星期五</th><th>星期六</th><th>星期日</th></tr>"
            + "<tr><th>1-2节<br>08:00-09:40</th><th>3-4节<br>09:50-11:30</th><th>5-6节<br>13:30-15:10</th>"
            + "<th>7-8节<br>15:20-17:00</th><th>9-10节<br>18:30-20:10</th><th></th><th></th></tr>"
            + "<tr><td>第一大节<br>08:00-09:40</td>"
            + "<td><div class=\"kbcontent1\">高等数学A<br>1-16周<br>教1-101</div>"
            + "<div class=\"kbcontent\" style=\"display:none\">高等数学A"
            + "<font title='老师'>王建国</font><font title='周次(节次)'>1-16(周)[01-02节]</font>"
            + "<font title='教室'>教1-101</font></div></td>"
            + "<td><div class=\"kbcontent1\">线性代数<br>1-16周(单)<br>教2-205</div>"
            + "<div class=\"kbcontent\" style=\"display:none\">线性代数"
            + "<font title='老师'>张伟</font><font title='周次(节次)'>1-16(周)(单)[01-02节]</font>"
            + "<font title='教室'>教2-205</font></div></td>"
            + "<td></td><td></td><td></td><td></td><td></td></tr>"
            + "<tr><td>第二大节<br>09:50-11:30</td><td></td><td></td>"
            + "<td><div class=\"kbcontent1\">程序设计基础<br>1-16周<br>机房-506</div>"
            + "<div class=\"kbcontent\" style=\"display:none\">程序设计基础"
            + "<font title='老师'>陈斌</font><font title='周次(节次)'>1-16(周)[01-02节]</font>"
            + "<font title='教室'>机房-506</font></div>"
            + "----------------------------------------------"
            + "<div class=\"kbcontent1\">数据结构<br>1-10周<br>教3-210</div>"
            + "<div class=\"kbcontent\" style=\"display:none\">数据结构"
            + "<font title='老师'>赵敏</font><font title='周次(节次)'>1-10(周)[01-02节]</font>"
            + "<font title='教室'>教3-210</font></div></td>"
            + "<td></td><td></td><td></td><td></td></tr>"
            + "<tr><td colspan=\"8\">备注：本表为测试课表，如有变动以教务系统为准</td></tr>"
            + "</table></body></html>";

    @Test
    public void parsesMergedAndKbContentCells() {
        TimetableResult result = TimetableParser.parseHtml(SAMPLE);
        List<Course> courses = result.courses;
        assertFalse("应解析出课程", courses.isEmpty());

        Course math = find(courses, "高等数学A");
        assertEquals(1, math.weekday);
        assertEquals("王建国", math.teacher);
        assertEquals("教1-101", math.room);
        assertEquals(16, math.weeks.size());
        assertEquals("[1, 2]", math.sessions.toString());

        Course alg = find(courses, "线性代数");
        assertEquals(2, alg.weekday);
        assertEquals("张伟", alg.teacher);
        // 单周：1..15 共 8 周
        assertEquals(8, alg.weeks.size());
        assertTrue("单周课程不应含双周", !alg.weeks.contains(2));

        // 同一格两门课（hr 分隔）都要解析出来
        Course prog = find(courses, "程序设计基础");
        assertEquals(3, prog.weekday);
        assertEquals("机房-506", prog.room);
        Course ds = find(courses, "数据结构");
        assertEquals("教3-210", ds.room);
        assertEquals(10, ds.weeks.size());

        // 备注行必须跳过
        for (Course c : courses) {
            assertFalse("备注行不应变成课程：" + c.name, c.name.contains("备注"));
        }
    }

    @Test
    public void extractsSessionTimesAndColumns() {
        TimetableResult result = TimetableParser.parseHtml(SAMPLE);
        assertEquals(7 + 1, result.meta.columns.size());
        assertTrue(result.meta.sessionTimes.containsKey("1"));
        assertTrue(result.meta.sessionTimes.containsKey("2"));
        assertEquals("[08:00, 09:40]", result.meta.sessionTimes.get("1").toString());
        assertEquals(Integer.valueOf(1), result.meta.minWeek);
        assertEquals(Integer.valueOf(16), result.meta.maxWeek);
    }

    @Test
    public void parsesPlainHtmlTableWithoutKbClass() {
        String html = "<table><tr><th>节次</th><th>星期一</th><th>星期二</th></tr>"
                + "<tr><td>1-2节 08:00-09:40</td>"
                + "<td>大学英语(3)<br>李梅<br>1-12周<br>外语楼-302</td><td></td></tr></table>";
        TimetableResult result = TimetableParser.parseHtml(html);
        assertEquals(1, result.courses.size());
        Course c = result.courses.get(0);
        assertEquals("大学英语(3)", c.name);
        assertEquals("李梅", c.teacher);
        assertEquals("外语楼-302", c.room);
        assertEquals(1, c.weekday);
        assertEquals(12, c.weeks.size());
    }

    @Test
    public void expandsSessionRangeStrings() {
        assertEquals("[5, 6, 7, 8]", TimetableParser.expandNumRanges("05-06-07-08").toString());
        assertEquals("[1, 2, 3, 4, 6]", TimetableParser.expandNumRanges("1-4,6").toString());
        // 单双周筛选（与桌面版 kb_parser.parse_weeks 行为一致）
        assertEquals("[1, 3, 5, 7, 9, 11, 13, 15]", TimetableParser.parseWeeks("1-16周(单)").toString());
        assertEquals("[2, 4, 6, 8, 10, 12]", TimetableParser.parseWeeks("2,4,6,8,10,12(双周)").toString());
        assertEquals("[3, 4, 5]", TimetableParser.parseWeeks("3-5周").toString());
    }

    private static Course find(List<Course> courses, String name) {
        for (Course c : courses) {
            if (name.equals(c.name)) {
                return c;
            }
        }
        throw new AssertionError("未找到课程：" + name + "，实际为 " + courses);
    }
}
