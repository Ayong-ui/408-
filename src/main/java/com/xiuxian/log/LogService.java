package com.xiuxian.log;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import com.xiuxian.common.ApiException;
import com.xiuxian.common.Checks;
import com.xiuxian.common.DayClock;

/** 修行日志：每个修炼日一篇，完全可选。传音玉简往今天的日志末尾追加一行。 */
@Service
public class LogService {

    public record LogEntry(String day, String content, Long updatedAt) {
    }

    private static final int MAX_CHARS = 5000;
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final RowMapper<LogEntry> MAPPER = (rs, i) -> new LogEntry(rs.getString("day"),
            rs.getString("content"), rs.getLong("updated_at"));

    private final Object lock = new Object();
    private final JdbcTemplate jdbc;
    private final DayClock clock;

    public LogService(JdbcTemplate jdbc, DayClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public List<LogEntry> list(Integer limit) {
        int lim = limit == null ? 30 : Math.max(1, Math.min(365, limit));
        return jdbc.query("SELECT day, content, updated_at FROM daily_log ORDER BY day DESC LIMIT ?", MAPPER, lim);
    }

    public Optional<LogEntry> find(LocalDate day) {
        return jdbc.query("SELECT day, content, updated_at FROM daily_log WHERE day = ?", MAPPER, day.toString())
                .stream().findFirst();
    }

    public LogEntry get(String day) {
        LocalDate d = Checks.day(day);
        return find(d).orElse(new LogEntry(d.toString(), "", null));
    }

    /** 内容去掉首尾空白后为空就删掉这天的日志。 */
    public LogEntry put(String day, String content) {
        LocalDate d = Checks.day(day);
        String c = content == null ? "" : content.strip();
        checkLength(c, "日志不能超过 5000 字");
        synchronized (lock) {
            if (c.isEmpty()) {
                jdbc.update("DELETE FROM daily_log WHERE day = ?", d.toString());
            } else {
                upsert(d, c, clock.now());
            }
        }
        return get(d.toString());
    }

    /** 传音玉简：在今天日志末尾追加一行 [HH:mm] text。 */
    public LogEntry append(String text) {
        String t = Checks.required(text, "传音", 1, 500).replaceAll("\\s*\\R\\s*", " ");
        long now = clock.now();
        LocalDate today = clock.dayOf(now);
        String line = "[" + clock.at(now).format(HHMM) + "] " + t;
        synchronized (lock) {
            String old = find(today).map(LogEntry::content).orElse("");
            String content = old.isEmpty() ? line : old + "\n" + line;
            checkLength(content, "今日日志已满（5000 字）");
            upsert(today, content, now);
        }
        return get(today.toString());
    }

    private void upsert(LocalDate day, String content, long now) {
        jdbc.update("""
                INSERT INTO daily_log(day, content, updated_at) VALUES (?, ?, ?)
                ON CONFLICT(day) DO UPDATE SET content = excluded.content, updated_at = excluded.updated_at
                """, day.toString(), content, now);
    }

    private static void checkLength(String s, String message) {
        if (s.codePointCount(0, s.length()) > MAX_CHARS) {
            throw ApiException.badRequest(message);
        }
    }
}
