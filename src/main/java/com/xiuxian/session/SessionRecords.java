package com.xiuxian.session;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import com.xiuxian.common.DayClock;

/** 查询修炼记录（SessionRecord），时长由分段汇总。静室和藏经阁共用。 */
@Component
public class SessionRecords {

    private static final String SELECT = """
            SELECT s.id, s.method_id, m.name AS method_name, m.subject, s.chapter, m.path_id, p.name AS path_name,
                   p.color, s.source, s.started_at, s.ended_at, s.note,
                   (SELECT COALESCE(SUM(g.end_at - g.start_at), 0) FROM segment g
                     WHERE g.session_id = s.id AND g.end_at IS NOT NULL) AS ms
            FROM session s
            JOIN method m ON m.id = s.method_id
            JOIN path p ON p.id = m.path_id
            """;

    private final JdbcTemplate jdbc;
    private final DayClock clock;
    private final RowMapper<SessionRecord> mapper;

    public SessionRecords(JdbcTemplate jdbc, DayClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.mapper = (rs, i) -> {
            long endedAt = rs.getLong("ended_at");
            Long ended = rs.wasNull() ? null : endedAt;
            long startedAt = rs.getLong("started_at");
            return new SessionRecord(rs.getLong("id"), rs.getLong("method_id"), rs.getString("method_name"),
                    rs.getString("subject"), rs.getString("chapter"), rs.getLong("path_id"),
                    rs.getString("path_name"), rs.getString("color"), rs.getString("source"), startedAt, ended,
                    rs.getLong("ms") / 1000, clock.dayOf(startedAt).toString(), rs.getString("note"));
        };
    }

    /** tail 是 WHERE / ORDER BY / LIMIT 子句，参数用 ? 占位。 */
    public List<SessionRecord> find(String tail, Object... args) {
        clock.dayStartHour(); // 先把设置读进缓存，映射每行算修炼日时就不会再嵌套查库
        return jdbc.query(SELECT + tail, mapper, args);
    }

    public Optional<SessionRecord> byId(long id) {
        return find("WHERE s.id = ?", id).stream().findFirst();
    }
}
