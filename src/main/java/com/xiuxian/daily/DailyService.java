package com.xiuxian.daily;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.xiuxian.common.ApiException;
import com.xiuxian.common.Checks;
import com.xiuxian.common.DayClock;
import com.xiuxian.common.ListSync;
import com.xiuxian.path.PathService;

/**
 * 今日功课与日课模板。某个修炼日第一次被访问时按模板生成当天功课，并记入 day_meta，
 * 所以用户删掉的功课不会被重新生成。
 */
@Service
public class DailyService {

    public record TodayView(String day, List<DailyItem> items) {
    }

    public record RoutineInput(Long id, String name, Long pathId, String weekdays, String note, Boolean enabled) {
    }

    private static final String ITEM_SELECT = """
            SELECT d.id, d.day, d.name, d.path_id, p.name AS path_name, p.color, d.note, d.done, d.done_at,
                   d.routine_id, d.sort
            FROM daily_item d LEFT JOIN path p ON p.id = d.path_id
            """;

    private static final RowMapper<DailyItem> ITEM = (rs, i) -> new DailyItem(
            rs.getLong("id"), rs.getString("day"), rs.getString("name"), nullableLong(rs, "path_id"),
            rs.getString("path_name"), rs.getString("color"), rs.getString("note"), rs.getInt("done") == 1,
            nullableLong(rs, "done_at"), nullableLong(rs, "routine_id"), rs.getInt("sort"));

    private static final RowMapper<Routine> ROUTINE = (rs, i) -> new Routine(
            rs.getLong("id"), rs.getString("name"), nullableLong(rs, "path_id"), rs.getString("weekdays"),
            rs.getString("note"), rs.getInt("sort"), rs.getInt("enabled") == 1);

    private final Object lock = new Object();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final DayClock clock;
    private final PathService paths;

    public DailyService(JdbcTemplate jdbc, TransactionTemplate tx, DayClock clock, PathService paths) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.paths = paths;
    }

    /** 该修炼日还没生成过功课时按模板生成。 */
    public void ensureGenerated(LocalDate day) {
        synchronized (lock) {
            tx.executeWithoutResult(status -> {
                String d = day.toString();
                Integer generated = jdbc.queryForObject("SELECT COUNT(*) FROM day_meta WHERE day = ?", Integer.class, d);
                if (generated != null && generated > 0) {
                    return;
                }
                jdbc.update("""
                        INSERT INTO daily_item(day, routine_id, name, path_id, note, sort)
                        SELECT ?, id, name, path_id, note, sort FROM routine
                        WHERE enabled = 1 AND instr(weekdays, ?) > 0
                        ORDER BY sort, id
                        """, d, weekday(day));
                jdbc.update("INSERT INTO day_meta(day, generated) VALUES (?, 1)", d);
            });
        }
    }

    public TodayView today() {
        LocalDate day = clock.today();
        ensureGenerated(day);
        return new TodayView(day.toString(), items(day));
    }

    public List<DailyItem> items(LocalDate day) {
        return jdbc.query(ITEM_SELECT + " WHERE d.day = ? ORDER BY d.sort, d.id", ITEM, day.toString());
    }

    public DailyItem item(long id) {
        return jdbc.query(ITEM_SELECT + " WHERE d.id = ?", ITEM, id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("未找到此功课"));
    }

    public DailyItem add(String name, Long pathId, String note) {
        LocalDate day = clock.today();
        ensureGenerated(day);
        String n = Checks.required(name, "功课名", 1, 20);
        String nt = Checks.optional(note, "备注", 40);
        requirePath(pathId);
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(sort), 0) FROM daily_item WHERE day = ?",
                Integer.class, day.toString());
        long id = jdbc.queryForObject(
                "INSERT INTO daily_item(day, name, path_id, note, sort) VALUES (?, ?, ?, ?, ?) RETURNING id",
                Long.class, day.toString(), n, pathId, nt, (max == null ? 0 : max) + 1);
        return item(id);
    }

    /** 只改 patch 里出现的字段。 */
    public DailyItem update(long id, Map<String, Object> patch) {
        DailyItem old = item(id);
        String name = old.name();
        Long pathId = old.pathId();
        String note = old.note();
        boolean done = old.done();
        Long doneAt = old.doneAt();

        if (patch.containsKey("name")) {
            name = Checks.required(text(patch.get("name")), "功课名", 1, 20);
        }
        if (patch.containsKey("pathId")) {
            pathId = toLong(patch.get("pathId"));
            requirePath(pathId);
        }
        if (patch.containsKey("note")) {
            note = Checks.optional(text(patch.get("note")), "备注", 40);
        }
        if (patch.containsKey("done")) {
            if (!(patch.get("done") instanceof Boolean b)) {
                throw ApiException.badRequest("done 需为 true 或 false");
            }
            if (b && !done) {
                doneAt = clock.now();
            } else if (!b) {
                doneAt = null;
            }
            done = b;
        }
        jdbc.update("UPDATE daily_item SET name = ?, path_id = ?, note = ?, done = ?, done_at = ? WHERE id = ?",
                name, pathId, note, done ? 1 : 0, doneAt, id);
        return item(id);
    }

    public void delete(long id) {
        item(id);
        jdbc.update("DELETE FROM daily_item WHERE id = ?", id);
    }

    public List<Routine> routines() {
        return jdbc.query("SELECT * FROM routine ORDER BY sort, id", ROUTINE);
    }

    /** 整表同步日课模板；今天已生成过功课时，新增且包含今天星期的模板同时追加到今天。 */
    public List<Routine> syncRoutines(List<RoutineInput> input) {
        List<RoutineInput> clean = input.stream().map(this::validate).toList();
        LocalDate today = clock.today();
        synchronized (lock) {
            tx.executeWithoutResult(status -> {
                ListSync.Result result = ListSync.sync(jdbc, "routine", clean, RoutineInput::id,
                        new ListSync.Writer<>() {
                            @Override
                            public long insert(RoutineInput r, int sort) {
                                return jdbc.queryForObject("INSERT INTO routine(name, path_id, weekdays, note, sort, enabled) "
                                                + "VALUES (?, ?, ?, ?, ?, ?) RETURNING id", Long.class,
                                        r.name(), r.pathId(), r.weekdays(), r.note(), sort, r.enabled() ? 1 : 0);
                            }

                            @Override
                            public void update(long id, RoutineInput r, int sort) {
                                jdbc.update("UPDATE routine SET name = ?, path_id = ?, weekdays = ?, note = ?, sort = ?, "
                                                + "enabled = ? WHERE id = ?",
                                        r.name(), r.pathId(), r.weekdays(), r.note(), sort, r.enabled() ? 1 : 0, id);
                            }
                        });

                String d = today.toString();
                Integer generated = jdbc.queryForObject("SELECT COUNT(*) FROM day_meta WHERE day = ?", Integer.class, d);
                if (generated == null || generated == 0) {
                    return;
                }
                String wd = weekday(today);
                for (int i = 0; i < clean.size(); i++) {
                    long routineId = result.ids().get(i);
                    RoutineInput r = clean.get(i);
                    if (result.inserted().contains(routineId) && r.enabled() && r.weekdays().contains(wd)) {
                        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(sort), 0) FROM daily_item WHERE day = ?",
                                Integer.class, d);
                        jdbc.update("INSERT INTO daily_item(day, routine_id, name, path_id, note, sort) "
                                        + "VALUES (?, ?, ?, ?, ?, ?)",
                                d, routineId, r.name(), r.pathId(), r.note(), (max == null ? 0 : max) + 1);
                    }
                }
            });
        }
        return routines();
    }

    private RoutineInput validate(RoutineInput r) {
        if (r == null) {
            throw ApiException.badRequest("日课模板不能为空");
        }
        String name = Checks.required(r.name(), "日课名", 1, 20);
        requirePath(r.pathId());
        return new RoutineInput(r.id(), name, r.pathId(), normalizeWeekdays(r.weekdays()),
                Checks.optional(r.note(), "备注", 40), Checks.bool(r.enabled(), true));
    }

    /** "531" → "135"；只能由 1–7 组成且不为空。 */
    static String normalizeWeekdays(String weekdays) {
        if (weekdays == null || weekdays.isBlank()) {
            throw ApiException.badRequest("至少选一天");
        }
        TreeSet<Character> days = new TreeSet<>();
        for (char c : weekdays.strip().toCharArray()) {
            if (c < '1' || c > '7') {
                throw ApiException.badRequest("星期只能由 1–7 组成");
            }
            days.add(c);
        }
        StringBuilder sb = new StringBuilder();
        days.forEach(sb::append);
        return sb.toString();
    }

    private void requirePath(Long pathId) {
        if (pathId != null && paths.find(pathId).isEmpty()) {
            throw ApiException.badRequest("未找到此道脉");
        }
    }

    private static String weekday(LocalDate day) {
        return String.valueOf(day.getDayOfWeek().getValue());
    }

    private static String text(Object v) {
        return v == null ? null : v.toString();
    }

    private static Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("pathId 需为数字");
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }
}
