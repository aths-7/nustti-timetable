package edu.nustti.timetable.parse;

import edu.nustti.timetable.model.Course;
import edu.nustti.timetable.model.SessionTimes;
import edu.nustti.timetable.model.TimetableResult;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 课表解析：教务系统课表页面(HTML) → 统一课程列表。
 *
 * <p>本类是 Kivy 版 {@code kb_parser.py} 的 Java 移植，保持同一套解析规则（正方 jsxsd 的
 * {@code table#kbtable}、合并单元格 rowspan/colspan、{@code div.kbcontent} 单元格、
 * 周次/单双周/节次文本），以便两端课表数据完全一致。</p>
 */
public final class TimetableParser {

    public static final String[] WEEKDAY_CN =
            {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};

    private static final String SEP_TOKEN = "\u0000SEP\u0000";

    private static final Pattern BR_RE = Pattern.compile("<\\s*br\\s*/?\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HR_RE = Pattern.compile("<\\s*hr[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern BLOCK_RE = Pattern.compile(
            "<\\s*/?\\s*(?:p|div|li|h[1-6]|tr|table|section|article|tbody)\\b[^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG_RE = Pattern.compile("<[^>]+>");
    private static final Pattern ROWSPAN_RE = Pattern.compile("rowspan\\s*=\\s*[\"']?(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLSPAN_RE = Pattern.compile("colspan\\s*=\\s*[\"']?(\\d+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern WEEK_RANGE_RE = Pattern.compile("(\\d{1,2})\\s*[-~—至]\\s*(\\d{1,2})\\s*[（(\\[]?\\s*周");
    private static final Pattern WEEK_LIST_RE = Pattern.compile("\\d{1,2}(?:\\s*[,，、]\\s*\\d{1,2})+\\s*[（(\\[]?\\s*[单双]?\\s*周");
    private static final Pattern WEEK_SINGLE_RE = Pattern.compile("(\\d{1,2})\\s*[（(\\[]?\\s*周");
    private static final Pattern WEEK_ODD_RE = Pattern.compile("[（(]\\s*单\\s*[)）]|单周");
    private static final Pattern WEEK_EVEN_RE = Pattern.compile("[（(]\\s*双\\s*[)）]|双周");
    private static final Pattern WEEK_NOTE_RE = Pattern.compile(
            "\\d{1,2}\\s*[-~—至]?\\s*\\d{0,2}\\s*[（(\\[]?\\s*周[^)）]*[)）]?");
    private static final Pattern WEEK_NOTE_LIST_RE = Pattern.compile(
            "(?:\\d{1,2}\\s*[,，、]\\s*)+\\d{1,2}\\s*[（(\\[]?\\s*[单双]?周[^)）]*[)）]?");

    private static final Pattern SESS_RANGE_RE = Pattern.compile("(\\d{1,2})\\s*[-~—至]\\s*(\\d{1,2})\\s*节");
    private static final Pattern SESS_SINGLE_RE = Pattern.compile("(\\d{1,2})\\s*节");
    private static final Pattern TIME_RANGE_RE = Pattern.compile("(\\d{1,2}:\\d{2})\\s*[-~—至]\\s*(\\d{1,2}:\\d{2})");
    private static final Pattern NUM_RANGE_RE = Pattern.compile("(\\d{1,2})\\s*[-~—至]\\s*(\\d{1,2})");
    private static final Pattern NUM_RE = Pattern.compile("\\d{1,2}");
    private static final Pattern DASH_LINE_RE = Pattern.compile("[-—=*_·]{3,}");
    private static final Pattern ROOM_SHAPE_RE = Pattern.compile("^[A-Za-z]?\\d{1,3}([-#栋号]\\d{0,4})?[A-Za-z]?$");
    private static final Pattern KB_SEP_RE = Pattern.compile("-{3,}");
    private static final Pattern KB_SESS_BRACKET_RE = Pattern.compile("[\\[【（(]([^\\]】)）]*节[^\\]】)）]*)[\\]】)）]");
    private static final Pattern ANNOTATION_RE = Pattern.compile("^(备注|注[:：]|说明)");
    private static final Pattern CN_OR_LATIN_RE = Pattern.compile("[\\u4e00-\\u9fa5A-Za-z]");
    private static final Pattern HAS_DIGIT_RE = Pattern.compile("\\d");
    private static final Pattern HAS_WEEK_SESS_CHAR_RE = Pattern.compile("[周节时天]");

    private static final String[] ROOM_HINTS =
            {"室", "楼", "馆", "场", "机房", "实验", "中心", "校区", "号", "阶梯"};

    private static final Map<String, Integer> CN_NUM = new LinkedHashMap<>();

    static {
        String[] cn = {"一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "十二"};
        for (int i = 0; i < cn.length; i++) {
            CN_NUM.put(cn[i], i + 1);
        }
    }

    private TimetableParser() {
    }

    // ------------------------------------------------------------------ //
    // 文本清洗
    // ------------------------------------------------------------------ //

    /** HTML 片段 → 单行纯文本。 */
    public static String plain(String fragment) {
        if (fragment == null) {
            return "";
        }
        String text = HR_RE.matcher(fragment).replaceAll(" ");
        text = BR_RE.matcher(text).replaceAll(" ");
        text = BLOCK_RE.matcher(text).replaceAll(" ");
        text = TAG_RE.matcher(text).replaceAll("");
        text = Parser.unescapeEntities(text, false).replace('\u00a0', ' ');
        return text.replaceAll("\\s+", " ").trim();
    }

    /** HTML 单元格 → 段落列表；&lt;hr&gt; 视为不同课程的硬分隔，&lt;br&gt;/块级标签视为换行。 */
    public static List<List<String>> htmlCellToSegments(String fragment) {
        if (fragment == null) {
            return new ArrayList<>();
        }
        String text = HR_RE.matcher(fragment).replaceAll(SEP_TOKEN);
        text = BR_RE.matcher(text).replaceAll("\n");
        text = BLOCK_RE.matcher(text).replaceAll("\n");
        text = TAG_RE.matcher(text).replaceAll("");
        text = Parser.unescapeEntities(text, false).replace('\u00a0', ' ');
        text = text.replace(SEP_TOKEN, "\n" + SEP_TOKEN + "\n");
        return linesToSegments(Arrays.asList(text.split("\n", -1)));
    }

    public static List<List<String>> textToSegments(String text) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }
        if (text.contains("<") && text.contains(">")) {
            return htmlCellToSegments(text);
        }
        return linesToSegments(Arrays.asList(text.split("\n", -1)));
    }

    private static List<List<String>> linesToSegments(List<String> lines) {
        List<List<String>> segments = new ArrayList<>();
        List<String> current = new ArrayList<>();
        boolean seenWeek = false;
        for (String raw : lines) {
            String line = raw == null ? "" : raw.replace('\u00a0', ' ').trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.contains(SEP_TOKEN) || DASH_LINE_RE.matcher(line).matches()) {
                if (!current.isEmpty()) {
                    segments.add(current);
                    current = new ArrayList<>();
                }
                seenWeek = false;
                continue;
            }
            // 一个单元格里塞了两门课（常见于教务系统课表）：第二个"周次"行视为新课程起点
            if (seenWeek && !parseWeeks(line).isEmpty()) {
                segments.add(current);
                current = new ArrayList<>();
                seenWeek = false;
            }
            current.add(line);
            if (!parseWeeks(line).isEmpty()) {
                seenWeek = true;
            }
        }
        if (!current.isEmpty()) {
            segments.add(current);
        }
        return segments;
    }

    private static String joinSegments(List<List<String>> segments) {
        if (segments == null || segments.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (List<String> seg : segments) {
            if (seg == null) {
                continue;
            }
            for (String item : seg) {
                if (item != null && !item.isEmpty()) {
                    parts.add(item);
                }
            }
        }
        return String.join(" ", parts);
    }

    private static String firstLine(List<List<String>> segments) {
        if (segments == null) {
            return "";
        }
        for (List<String> seg : segments) {
            if (seg == null) {
                continue;
            }
            for (String item : seg) {
                if (item != null && !item.trim().isEmpty()) {
                    return item.trim();
                }
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ //
    // 语义解析
    // ------------------------------------------------------------------ //

    /** 从 "1-16周(单)" 这类文本解析出具体周次列表。 */
    public static List<Integer> parseWeeks(String text) {
        Set<Integer> weeks = new LinkedHashSet<>();
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }
        String parity = null;
        if (WEEK_ODD_RE.matcher(text).find()) {
            parity = "odd";
        } else if (WEEK_EVEN_RE.matcher(text).find()) {
            parity = "even";
        }
        Matcher range = WEEK_RANGE_RE.matcher(text);
        while (range.find()) {
            int a = Integer.parseInt(range.group(1));
            int b = Integer.parseInt(range.group(2));
            if (a > b) {
                int tmp = a;
                a = b;
                b = tmp;
            }
            for (int w = a; w <= b; w++) {
                weeks.add(w);
            }
        }
        if (weeks.isEmpty()) {
            Matcher list = WEEK_LIST_RE.matcher(text);
            while (list.find()) {
                Matcher num = NUM_RE.matcher(list.group());
                while (num.find()) {
                    weeks.add(Integer.parseInt(num.group()));
                }
            }
        }
        if (weeks.isEmpty()) {
            Matcher single = WEEK_SINGLE_RE.matcher(text);
            while (single.find()) {
                weeks.add(Integer.parseInt(single.group(1)));
            }
        }
        List<Integer> out = new ArrayList<>();
        for (Integer w : weeks) {
            if (w != null && w >= 1 && w <= 60) {
                if ("odd".equals(parity) && w % 2 == 0) {
                    continue;
                }
                if ("even".equals(parity) && w % 2 == 1) {
                    continue;
                }
                out.add(w);
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    private static String weeksNote(String text) {
        String src = text == null ? "" : text;
        Matcher m = WEEK_NOTE_RE.matcher(src);
        if (m.find()) {
            return m.group().trim();
        }
        m = WEEK_NOTE_LIST_RE.matcher(src);
        return m.find() ? m.group().trim() : "";
    }

    /** 从 "(1-2节)" / "第3-4节" 解析小节序号。 */
    public static List<Integer> parseSessions(String text) {
        Set<Integer> out = new LinkedHashSet<>();
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }
        Matcher range = SESS_RANGE_RE.matcher(text);
        while (range.find()) {
            int a = Integer.parseInt(range.group(1));
            int b = Integer.parseInt(range.group(2));
            if (a > b) {
                int tmp = a;
                a = b;
                b = tmp;
            }
            for (int s = a; s <= b; s++) {
                out.add(s);
            }
        }
        if (out.isEmpty()) {
            Matcher single = SESS_SINGLE_RE.matcher(text);
            while (single.find()) {
                out.add(Integer.parseInt(single.group(1)));
            }
        }
        List<Integer> list = new ArrayList<>();
        for (Integer s : out) {
            if (s >= 1 && s <= 20) {
                list.add(s);
            }
        }
        java.util.Collections.sort(list);
        return list;
    }

    /** 解析 "第一大节" / "第三节" 这类中文节次标签。 */
    private static List<Integer> cnBigSessions(String text) {
        List<Integer> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        if (text.contains("大节")) {
            for (Map.Entry<String, Integer> entry : CN_NUM.entrySet()) {
                if (text.contains("第" + entry.getKey() + "大节")) {
                    out.add(2 * entry.getValue() - 1);
                    out.add(2 * entry.getValue());
                    return out;
                }
            }
        }
        for (Map.Entry<String, Integer> entry : CN_NUM.entrySet()) {
            if (text.contains("第" + entry.getKey() + "节")) {
                out.add(entry.getValue());
                return out;
            }
        }
        return out;
    }

    /** 表头/单元格文本 → 星期几(1-7)，识别不出返回 0。 */
    public static int weekdayFromText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return 0;
        }
        String src = text.trim();
        for (int i = 0; i < WEEKDAY_CN.length; i++) {
            if (src.contains(WEEKDAY_CN[i]) || src.contains(WEEKDAY_CN[i].replace("星期", "周"))) {
                return i + 1;
            }
        }
        Matcher m = Pattern.compile("周\\s*([一二三四五六日天])").matcher(src);
        if (m.find()) {
            String ch = m.group(1);
            if ("日".equals(ch) || "天".equals(ch)) {
                return 7;
            }
            return "一二三四五六".indexOf(ch) + 1;
        }
        m = Pattern.compile("星期\\s*([1-7])").matcher(src);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        if (src.matches("[1-7]")) {
            return Integer.parseInt(src);
        }
        return 0;
    }

    private static boolean looksLikeRoom(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String hint : ROOM_HINTS) {
            if (text.contains(hint)) {
                return true;
            }
        }
        if (ROOM_SHAPE_RE.matcher(text.trim()).matches()) {
            return true;
        }
        // "教1-101" / "B305" / "3-201" 这类含数字的短文本，基本可认定是教室
        return HAS_DIGIT_RE.matcher(text).find() && !HAS_WEEK_SESS_CHAR_RE.matcher(text).find();
    }

    /** 把 "05-06-07-08" / "1-4,6" 这类节次串展开成小节号列表。 */
    public static List<Integer> expandNumRanges(String text) {
        Set<Integer> out = new LinkedHashSet<>();
        String src = text == null ? "" : text;
        Matcher m = NUM_RANGE_RE.matcher(src);
        while (m.find()) {
            int a = Integer.parseInt(m.group(1));
            int b = Integer.parseInt(m.group(2));
            if (a > b) {
                int tmp = a;
                a = b;
                b = tmp;
            }
            for (int n = a; n <= b; n++) {
                out.add(n);
            }
        }
        m = NUM_RE.matcher(src);
        while (m.find()) {
            out.add(Integer.parseInt(m.group()));
        }
        List<Integer> list = new ArrayList<>();
        for (Integer n : out) {
            if (n >= 1 && n <= 20) {
                list.add(n);
            }
        }
        java.util.Collections.sort(list);
        return list;
    }

    /** "[05-06-07-08节]" → [5,6,7,8]；无括号节次时用表格行标签推断的值。 */
    private static List<Integer> kbSessions(String weekText, List<Integer> defaultSessions) {
        if (weekText != null) {
            Matcher m = KB_SESS_BRACKET_RE.matcher(weekText);
            if (m.find()) {
                List<Integer> got = expandNumRanges(m.group(1));
                if (!got.isEmpty()) {
                    return got;
                }
            }
        }
        return defaultSessions == null ? new ArrayList<>() : new ArrayList<>(defaultSessions);
    }

    /** 把一个大节的时间段按覆盖的小节数均分（如 "08:00-09:40" 分给 2 小节）。 */
    private static List<List<String>> splitTimeRange(String start, String end, int count) {
        List<List<String>> out = new ArrayList<>();
        if (count <= 1) {
            out.add(Arrays.asList(start, end));
            return out;
        }
        int begin = SessionTimes.parseHhmm(start);
        int finish = SessionTimes.parseHhmm(end);
        if (begin < 0 || finish < 0) {
            out.add(Arrays.asList(start, end));
            return out;
        }
        double step = (finish - begin) / (double) count;
        for (int k = 0; k < count; k++) {
            int s = begin + (int) Math.round(step * k);
            int e = begin + (int) Math.round(step * (k + 1));
            out.add(Arrays.asList(hhmm(s), hhmm(e)));
        }
        return out;
    }

    private static String hhmm(int minutes) {
        int m = Math.max(0, minutes);
        return String.format("%02d:%02d", m / 60, m % 60);
    }

    // ------------------------------------------------------------------ //
    // 表格网格（支持 rowspan / colspan）
    // ------------------------------------------------------------------ //

    static final class Cell {
        String html = "";
        List<List<String>> segments = new ArrayList<>();
        int rowspan = 1;
        int colspan = 1;
        boolean spanned;

        Cell(String html, int rowspan, int colspan, boolean spanned) {
            this.html = html == null ? "" : html;
            this.rowspan = Math.max(1, rowspan);
            this.colspan = Math.max(1, colspan);
            this.spanned = spanned;
            if (!this.html.isEmpty()) {
                this.segments = htmlCellToSegments(this.html);
            }
        }
    }

    private static long key(int r, int c) {
        return r * 1000L + c;
    }

    private static List<List<Cell>> toGrid(Element table) {
        List<List<Cell>> rawRows = new ArrayList<>();
        for (Element tr : table.select("tr")) {
            List<Cell> cells = new ArrayList<>();
            for (Element td : tr.children()) {
                String tag = td.tagName().toLowerCase();
                if (!"td".equals(tag) && !"th".equals(tag)) {
                    continue;
                }
                int rs = 1;
                int cs = 1;
                Matcher m = ROWSPAN_RE.matcher(td.attr("rowspan").isEmpty() ? "" : "rowspan=" + td.attr("rowspan"));
                if (m.find()) {
                    rs = Integer.parseInt(m.group(1));
                }
                m = COLSPAN_RE.matcher(td.attr("colspan").isEmpty() ? "" : "colspan=" + td.attr("colspan"));
                if (m.find()) {
                    cs = Integer.parseInt(m.group(1));
                }
                cells.add(new Cell(td.html(), rs, cs, false));
            }
            if (!cells.isEmpty()) {
                rawRows.add(cells);
            }
        }

        Map<Long, Cell> placed = new LinkedHashMap<>();
        int maxCol = 0;
        for (int r = 0; r < rawRows.size(); r++) {
            int c = 0;
            for (Cell cell : rawRows.get(r)) {
                while (placed.containsKey(key(r, c))) {
                    c++;
                }
                for (int dr = 0; dr < cell.rowspan; dr++) {
                    for (int dc = 0; dc < cell.colspan; dc++) {
                        if (dr == 0 && dc == 0) {
                            placed.put(key(r + dr, c + dc), cell);
                        } else {
                            placed.put(key(r + dr, c + dc), new Cell("", 1, 1, true));
                        }
                    }
                }
                c += cell.colspan;
                maxCol = Math.max(maxCol, c);
            }
        }
        if (placed.isEmpty()) {
            return new ArrayList<>();
        }
        int maxRow = 0;
        for (Long k : placed.keySet()) {
            maxRow = Math.max(maxRow, (int) (k / 1000L));
        }
        List<List<Cell>> grid = new ArrayList<>();
        for (int r = 0; r <= maxRow; r++) {
            List<Cell> row = new ArrayList<>();
            for (int c = 0; c < maxCol; c++) {
                Cell cell = placed.get(key(r, c));
                row.add(cell == null ? new Cell("", 1, 1, false) : cell);
            }
            grid.add(row);
        }
        return grid;
    }

    // ------------------------------------------------------------------ //
    // 单元格 → 课程
    // ------------------------------------------------------------------ //

    static List<Course> coursesFromCell(List<List<String>> segments, int weekday,
                                        List<Integer> defaultSessions) {
        List<Course> courses = new ArrayList<>();
        if (segments == null) {
            return courses;
        }
        for (List<String> rawLines : segments) {
            List<String> lines = new ArrayList<>();
            for (String ln : rawLines) {
                if (ln != null && !ln.trim().isEmpty()) {
                    lines.add(ln.trim());
                }
            }
            if (lines.isEmpty()) {
                continue;
            }
            int weekIdx = -1;
            String weekText = "";
            for (int i = 0; i < lines.size(); i++) {
                if (!parseWeeks(lines.get(i)).isEmpty()) {
                    weekIdx = i;
                    weekText = lines.get(i);
                    break;
                }
            }
            String name = lines.get(0);
            if (weekIdx == 0) {
                name = name.replaceAll("[（(]?[\\d,\\-\\s~—至]*周[^)）]*[)）]?", "").replaceAll("^[ ,，]+|[ ,，]+$", "");
            }
            if (name.trim().isEmpty()) {
                name = lines.get(0);
            }
            String room = "";
            String teacher = "";
            for (int i = 0; i < lines.size(); i++) {
                if (i == 0 || i == weekIdx) {
                    continue;
                }
                String line = lines.get(i);
                String[] parts = line.trim().split("\\s+");
                boolean multi = parts.length > 1;
                boolean anyRoom = false;
                for (String part : parts) {
                    if (looksLikeRoom(part)) {
                        anyRoom = true;
                        break;
                    }
                }
                if (multi && anyRoom) {
                    for (String part : parts) {
                        if (room.isEmpty() && looksLikeRoom(part)) {
                            room = part;
                        } else if (teacher.isEmpty() && !part.equals(room)) {
                            teacher = part;
                        }
                    }
                    continue;
                }
                if (room.isEmpty() && looksLikeRoom(line)) {
                    room = line;
                } else if (teacher.isEmpty() && !line.equals(room)) {
                    teacher = line;
                }
            }
            List<Integer> sessions = parseSessions(weekText);
            if (sessions.isEmpty()) {
                sessions = defaultSessions == null ? new ArrayList<>() : new ArrayList<>(defaultSessions);
            }
            Course course = new Course(name, teacher, room, weekday, sessions, parseWeeks(weekText));
            course.weekRaw = weeksNote(weekText);
            course.raw = String.join(" / ", lines);
            courses.add(course);
        }
        return courses;
    }

    /**
     * 正方教务课表单元格（div.kbcontent / div.kbcontent1）→ 课程列表。
     *
     * <p>同一格可含多门课，用 "-------" 分隔；优先使用含老师/节次的完整版 div.kbcontent，
     * 仅在整格都只有简版时才退化用 div.kbcontent1。简版与完整版内容重复，必须二选一，
     * 否则字段会串行错位（教师被填成周次、课程名变成 "1-8[01-02节]"）。</p>
     */
    public static List<Course> kbCoursesFromCell(String fragment, int weekday,
                                                 List<Integer> defaultSessions) {
        List<Course> courses = new ArrayList<>();
        if (fragment == null || !fragment.contains("kbcontent")) {
            return courses;
        }
        List<String> full = new ArrayList<>();
        List<String> brief = new ArrayList<>();
        Document doc = Jsoup.parseBodyFragment(fragment);
        for (Element div : doc.select("div[class*=kbcontent]")) {
            String cls = div.className().toLowerCase();
            if (cls.contains("kbcontent1")) {
                brief.add(div.html());
            } else if (cls.contains("kbcontent")) {
                full.add(div.html());
            }
        }
        List<String> picked = full.isEmpty() ? brief : full;
        if (picked.isEmpty()) {
            return courses;
        }

        for (String inner : picked) {
            for (String block : KB_SEP_RE.split(inner)) {
                if (block == null || block.trim().isEmpty()) {
                    continue;
                }
                Document bdoc = Jsoup.parseBodyFragment(block);
                Elements fonts = bdoc.select("font[title]");
                Map<String, String> fields = new LinkedHashMap<>();
                for (Element font : fonts) {
                    String fieldKey = plain(font.attr("title"));
                    String value = plain(font.text());
                    if (!fieldKey.isEmpty() && !value.isEmpty() && !fields.containsKey(fieldKey)) {
                        fields.put(fieldKey, value);
                    }
                }
                for (Element font : fonts) {
                    font.remove();
                }
                String name = plain(bdoc.body().html()).trim();
                name = name.replaceAll("^[\\-\\s]+|[\\-\\s]+$", "");
                if (name.isEmpty() || !CN_OR_LATIN_RE.matcher(name).find()) {
                    continue;
                }
                String weekText = "";
                String room = "";
                String teacher = "";
                for (Map.Entry<String, String> entry : fields.entrySet()) {
                    String k = entry.getKey();
                    if (weekText.isEmpty() && k.contains("周")) {
                        weekText = entry.getValue();
                    }
                    if (room.isEmpty() && (k.contains("教室") || k.contains("地点") || k.contains("场地"))) {
                        room = entry.getValue();
                    }
                    if (teacher.isEmpty() && (k.contains("老师") || k.contains("教师") || k.contains("任课"))) {
                        teacher = entry.getValue();
                    }
                }
                Course course = new Course(name, teacher, room, weekday,
                        kbSessions(weekText, defaultSessions), parseWeeks(weekText));
                course.weekRaw = weeksNote(weekText);
                List<String> rawParts = new ArrayList<>();
                for (String part : new String[]{name, teacher, weekText, room}) {
                    if (part != null && !part.isEmpty()) {
                        rawParts.add(part);
                    }
                }
                course.raw = String.join(" / ", rawParts);
                courses.add(course);
            }
        }
        return courses;
    }

    /** 同一门课被拆成相邻小节时（合并单元格/多次出现）合并为一条。 */
    public static List<Course> mergeConsecutive(List<Course> courses) {
        Map<String, Course> buckets = new LinkedHashMap<>();
        for (Course course : courses) {
            Set<Integer> weeks = new LinkedHashSet<>(course.weeks == null ? new ArrayList<>() : course.weeks);
            String key = course.name + "\u0001" + course.weekday + "\u0001" + course.room
                    + "\u0001" + course.teacher + "\u0001" + weeks;
            Course exist = buckets.get(key);
            if (exist == null) {
                Course copy = new Course(course.name, course.teacher, course.room, course.weekday,
                        course.sessions, course.weeks);
                copy.weekRaw = course.weekRaw;
                copy.raw = course.raw;
                buckets.put(key, copy);
            } else {
                Set<Integer> merged = new LinkedHashSet<>(exist.sessions);
                if (course.sessions != null) {
                    merged.addAll(course.sessions);
                }
                List<Integer> sorted = new ArrayList<>(merged);
                java.util.Collections.sort(sorted);
                exist.sessions = sorted;
            }
        }
        return new ArrayList<>(buckets.values());
    }

    // ------------------------------------------------------------------ //
    // 作息时间 / 学期 / 对外接口
    // ------------------------------------------------------------------ //

    /** 若课表表格左侧给出了每一小节的起止时间，抽取出来供界面使用（按大节均分展开）。 */
    public static Map<Integer, List<String>> sessionTimesFromHtml(String html) {
        Map<Integer, List<String>> result = new LinkedHashMap<>();
        if (html == null || html.isEmpty()) {
            return result;
        }
        for (Element table : Jsoup.parse(html).select("table")) {
            List<List<Cell>> grid = toGrid(table);
            if (grid.isEmpty()) {
                continue;
            }
            result = new LinkedHashMap<>();
            int order = 0;
            for (List<Cell> row : grid) {
                if (row.isEmpty()) {
                    continue;
                }
                String label = plain(row.get(0).html);
                Matcher m = TIME_RANGE_RE.matcher(label);
                if (!m.find()) {
                    continue;
                }
                order++;
                String start = m.group(1);
                String end = m.group(2);
                String head = label.substring(0, Math.max(0, label.indexOf(start)));
                List<Integer> nums = expandNumRanges(head);
                if (nums.isEmpty()) {
                    nums = new ArrayList<>(Arrays.asList(order));
                }
                List<List<String>> pairs = splitTimeRange(start, end, nums.size());
                for (int i = 0; i < nums.size() && i < pairs.size(); i++) {
                    int num = nums.get(i);
                    if (num >= 1 && num <= 20) {
                        result.put(num, pairs.get(i));
                    }
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
        }
        return result;
    }

    /** 从课表页面解析可选学期列表 [(value, label)]。 */
    public static List<TimetableResult.TermOption> parseTerms(String html) {
        List<TimetableResult.TermOption> terms = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (html == null || html.isEmpty()) {
            return terms;
        }
        Document doc = Jsoup.parse(html);
        for (Element select : doc.select("select")) {
            String name = select.attr("name");
            if (!(name.startsWith("xnxq01id") || name.startsWith("xnxqid") || name.startsWith("xnxq"))) {
                continue;
            }
            for (Element option : select.select("option")) {
                String value = option.attr("value").trim();
                String label = plain(option.html());
                if (!value.isEmpty() && !label.isEmpty() && !seen.contains(value)
                        && value.matches(".*\\d{4}.*")) {
                    seen.add(value);
                    terms.add(new TimetableResult.TermOption(value, label));
                }
            }
        }
        if (terms.isEmpty()) {
            for (Element option : doc.select("option")) {
                String value = option.attr("value").trim();
                String label = plain(option.html());
                if (!value.isEmpty() && !seen.contains(value)
                        && (value.matches("\\d{4}-\\d{4}-\\d") || value.matches(".*\\d{4}-\\d{4}.*"))) {
                    seen.add(value);
                    terms.add(new TimetableResult.TermOption(value, label));
                }
            }
        }
        return terms;
    }

    /** 课表页当前选中的学期（用于未显式指定学期时回填）。 */
    public static String parseActiveTerm(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        for (Element option : Jsoup.parse(html).select("option[selected]")) {
            String value = option.attr("value").trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    /** 解析课表页面 HTML，返回课程列表与元数据。 */
    public static TimetableResult parseHtml(String html) {
        TimetableResult result = new TimetableResult();
        if (html == null || html.isEmpty()) {
            result.meta = TimetableResult.Meta.error("页面内容为空");
            return result;
        }
        Document doc = Jsoup.parse(html);
        List<Element> tables = new ArrayList<>(doc.select("table"));
        if (tables.isEmpty()) {
            result.meta = TimetableResult.Meta.error("页面中未找到任何表格");
            return result;
        }
        tables.sort(Comparator.comparingInt((Element t) -> isKbTable(t) ? 0 : 1));

        for (Element table : tables) {
            List<List<Cell>> grid = toGrid(table);
            if (grid.size() < 2) {
                continue;
            }
            List<List<List<List<String>>>> segGrid = new ArrayList<>();
            List<List<String>> rawGrid = new ArrayList<>();
            for (List<Cell> row : grid) {
                List<List<List<String>>> segRow = new ArrayList<>();
                List<String> rawRow = new ArrayList<>();
                Cell first = row.isEmpty() ? null : row.get(0);
                // 跳过"备注/说明"这类跨列合并的脚注行（不是课表数据）
                boolean skipRow = first != null
                        && (first.colspan > 1 || ANNOTATION_RE.matcher(plain(first.html)).find());
                for (Cell cell : row) {
                    if (skipRow || cell.spanned) {
                        segRow.add(null);
                        rawRow.add("");
                    } else {
                        segRow.add(cell.segments);
                        rawRow.add(cell.html);
                    }
                }
                segGrid.add(segRow);
                rawGrid.add(rawRow);
            }

            int headerIdx = findHeaderRow(segGrid);
            if (headerIdx < 0) {
                continue;
            }
            List<Course> courses = buildFromGrid(segGrid, grid, headerIdx, rawGrid);
            if (courses.isEmpty()) {
                continue;
            }
            int minWeek = Integer.MAX_VALUE;
            int maxWeek = Integer.MIN_VALUE;
            for (Course c : courses) {
                for (Integer w : c.weeks) {
                    minWeek = Math.min(minWeek, w);
                    maxWeek = Math.max(maxWeek, w);
                }
            }
            TimetableResult.Meta meta = new TimetableResult.Meta();
            meta.tables = tables.size();
            meta.headerRow = headerIdx;
            meta.minWeek = minWeek == Integer.MAX_VALUE ? null : minWeek;
            meta.maxWeek = maxWeek == Integer.MIN_VALUE ? null : maxWeek;
            if (headerIdx < grid.size()) {
                for (Cell cell : grid.get(headerIdx)) {
                    meta.columns.add(plain(cell.html));
                }
            }
            Map<Integer, List<String>> times = sessionTimesFromHtml(html);
            for (Map.Entry<Integer, List<String>> entry : times.entrySet()) {
                meta.sessionTimes.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            result.courses = courses;
            result.meta = meta;
            return result;
        }
        result.meta = TimetableResult.Meta.error("未识别到课表表格结构");
        return result;
    }

    private static boolean isKbTable(Element table) {
        if ("kbtable".equalsIgnoreCase(table.id())) {
            return true;
        }
        return table.className().toLowerCase().contains("kbtable");
    }

    private static int findHeaderRow(List<List<List<List<String>>>> segGrid) {
        for (int i = 0; i < segGrid.size(); i++) {
            for (List<List<String>> cell : segGrid.get(i)) {
                if (cell != null && weekdayFromText(firstLine(cell)) > 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static List<Course> buildFromGrid(List<List<List<List<String>>>> segGrid,
                                              List<List<Cell>> spanGrid,
                                              int headerIdx,
                                              List<List<String>> rawGrid) {
        List<Course> courses = new ArrayList<>();
        Map<Integer, Integer> colWeekday = new LinkedHashMap<>();
        List<List<List<String>>> header = segGrid.get(headerIdx);
        for (int c = 0; c < header.size(); c++) {
            List<List<String>> cell = header.get(c);
            if (cell == null) {
                continue;
            }
            int wd = weekdayFromText(firstLine(cell));
            if (wd > 0) {
                colWeekday.put(c, wd);
            }
        }
        if (colWeekday.isEmpty()) {
            return courses;
        }

        int dataStart = headerIdx + 1;
        int dataCount = segGrid.size() - dataStart;
        boolean smallRows = dataCount >= 9; // 行数多 => 每行一小节，否则每行一大节(2小节)

        for (int i = dataStart; i < segGrid.size(); i++) {
            int idx = i - dataStart;
            List<List<List<String>>> row = segGrid.get(i);
            List<String> rawRow = i < rawGrid.size() ? rawGrid.get(i) : null;
            String label = row.isEmpty() ? "" : joinSegments(row.get(0));
            Matcher tm = TIME_RANGE_RE.matcher(label);
            String head = label;
            if (tm.find()) {
                head = label.substring(0, Math.max(0, label.indexOf(tm.group())));
            }
            List<Integer> sessHere = expandNumRanges(head);
            if (sessHere.isEmpty()) {
                sessHere = parseSessions(head);
            }
            if (sessHere.isEmpty()) {
                sessHere = cnBigSessions(label);
            }
            if (sessHere.isEmpty()) {
                sessHere = positional(idx, smallRows);
            }

            for (Map.Entry<Integer, Integer> entry : colWeekday.entrySet()) {
                int c = entry.getKey();
                int wd = entry.getValue();
                if (c >= row.size()) {
                    continue;
                }
                List<List<String>> cell = row.get(c);
                String raw = (rawRow != null && c < rawRow.size()) ? rawRow.get(c) : "";
                if ((cell == null || cell.isEmpty()) && (raw == null || raw.isEmpty())) {
                    continue;
                }
                int span = 1;
                if (spanGrid != null && i < spanGrid.size() && c < spanGrid.get(i).size()) {
                    span = spanGrid.get(i).get(c).rowspan;
                }
                List<Integer> sess;
                if (span > 1) {
                    Set<Integer> merged = new LinkedHashSet<>();
                    for (int k = idx; k < idx + span; k++) {
                        merged.addAll(positional(k, smallRows));
                    }
                    sess = new ArrayList<>(merged);
                    java.util.Collections.sort(sess);
                    List<Integer> byText = parseSessions(joinSegments(cell));
                    if (!byText.isEmpty()) {
                        sess = byText;
                    }
                } else {
                    sess = sessHere;
                }
                List<Course> kb = raw != null && !raw.isEmpty()
                        ? kbCoursesFromCell(raw, wd, sess) : new ArrayList<>();
                if (!kb.isEmpty()) {
                    courses.addAll(kb);
                } else {
                    courses.addAll(coursesFromCell(cell, wd, sess));
                }
            }
        }
        return mergeConsecutive(courses);
    }

    private static List<Integer> positional(int idx, boolean smallRows) {
        if (smallRows) {
            return new ArrayList<>(Arrays.asList(idx + 1));
        }
        return new ArrayList<>(Arrays.asList(2 * idx + 1, 2 * idx + 2));
    }
}
