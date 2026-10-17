package com.xiuxian.common;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 时长账本：所有"修了多久"都从分段算出来，只算 DONE 的修炼。
 * 按区间统计时把分段裁剪到 [from, to) 内，跨日的修炼因此会被拆到两天。
 */
@Component
public class Ledger {

    /** 一个已结束的分段，带上它所属的功法和道脉。 */
    public record Seg(long sessionId, long methodId, long pathId, boolean body, long start, long end) {
    }

    private final JdbcTemplate jdbc;

    public Ledger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 分段落在 [from, to) 内的毫秒数。 */
    public static long overlapMillis(long start, long end, long from, long to) {
        return Math.max(0, Math.min(end, to) - Math.max(start, from));
    }

    /** 与 [from, to) 有交集的 DONE 分段（未裁剪）。 */
    public List<Seg> doneSegments(long from, long to) {
        return jdbc.query("""
                SELECT g.session_id, s.method_id, m.path_id, p.body, g.start_at, g.end_at
                FROM segment g
                JOIN session s ON s.id = g.session_id
                JOIN method m ON m.id = s.method_id
                JOIN path p ON p.id = m.path_id
                WHERE s.status = 'DONE' AND g.end_at IS NOT NULL AND g.start_at < ? AND g.end_at > ?
                """,
                (rs, i) -> new Seg(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4) == 1,
                        rs.getLong(5), rs.getLong(6)),
                to, from);
    }

    /** [from, to) 内各道脉的 DONE 秒数（没有修炼的道脉不在结果里）。 */
    public Map<Long, Long> secondsByPath(long from, long to) {
        Map<Long, Long> millis = new HashMap<>();
        for (Seg s : doneSegments(from, to)) {
            millis.merge(s.pathId(), overlapMillis(s.start(), s.end(), from, to), Long::sum);
        }
        Map<Long, Long> seconds = new HashMap<>();
        millis.forEach((pathId, ms) -> seconds.put(pathId, ms / 1000));
        return seconds;
    }

    /** [from, to) 内的 DONE 总秒数（各道脉秒数之和，和分布图对得上）。 */
    public long totalSeconds(long from, long to) {
        return secondsByPath(from, to).values().stream().mapToLong(Long::longValue).sum();
    }

    /** 全部 DONE 修炼按功法汇总的秒数。 */
    public Map<Long, Long> allTimeByMethod() {
        Map<Long, Long> out = new HashMap<>();
        jdbc.query("""
                SELECT s.method_id, SUM(g.end_at - g.start_at)
                FROM segment g JOIN session s ON s.id = g.session_id
                WHERE s.status = 'DONE' AND g.end_at IS NOT NULL
                GROUP BY s.method_id
                """, rs -> {
                    out.put(rs.getLong(1), rs.getLong(2) / 1000);
                });
        return out;
    }

    public long allTimeForMethod(long methodId) {
        Long ms = jdbc.queryForObject("""
                SELECT COALESCE(SUM(g.end_at - g.start_at), 0)
                FROM segment g JOIN session s ON s.id = g.session_id
                WHERE s.status = 'DONE' AND g.end_at IS NOT NULL AND s.method_id = ?
                """, Long.class, methodId);
        return ms == null ? 0 : ms / 1000;
    }

    /** 全部 DONE 修炼的秒数：body=true 为体修，false 为修为。 */
    public long allTimeByBody(boolean body) {
        Long ms = jdbc.queryForObject("""
                SELECT COALESCE(SUM(g.end_at - g.start_at), 0)
                FROM segment g
                JOIN session s ON s.id = g.session_id
                JOIN method m ON m.id = s.method_id
                JOIN path p ON p.id = m.path_id
                WHERE s.status = 'DONE' AND g.end_at IS NOT NULL AND p.body = ?
                """, Long.class, body ? 1 : 0);
        return ms == null ? 0 : ms / 1000;
    }
}
