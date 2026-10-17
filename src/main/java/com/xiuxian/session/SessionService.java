package com.xiuxian.session;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.xiuxian.common.ApiException;
import com.xiuxian.common.Checks;
import com.xiuxian.common.DayClock;
import com.xiuxian.common.Ledger;
import com.xiuxian.common.Realms;
import com.xiuxian.common.Realms.RealmInfo;
import com.xiuxian.daily.DailyService;

/**
 * 静室：入定计时的状态机（RUNNING ⇄ PAUSED → DONE）、出关结算和补记。
 * 同一时刻最多一个进行中的修炼；改状态的操作都在同一把锁和一个事务里完成。
 */
@Service
public class SessionService {

    /** 不足一分钟的入定视为误触。 */
    private static final long MIN_MILLIS = 60_000;

    private record Row(long id, long methodId, long pathId, boolean body, String status, long startedAt) {
    }

    private record MethodRow(long id, String chapter, long pathId, boolean body, boolean archived) {
    }

    private static final RowMapper<Row> ROW = (rs, i) -> new Row(rs.getLong("id"), rs.getLong("method_id"),
            rs.getLong("path_id"), rs.getInt("body") == 1, rs.getString("status"), rs.getLong("started_at"));

    private static final String ROW_SELECT = """
            SELECT s.id, s.method_id, m.path_id, p.body, s.status, s.started_at
            FROM session s JOIN method m ON m.id = s.method_id JOIN path p ON p.id = m.path_id
            """;

    private final Object lock = new Object();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final DayClock clock;
    private final Ledger ledger;
    private final SessionRecords records;
    private final DailyService daily;

    public SessionService(JdbcTemplate jdbc, TransactionTemplate tx, DayClock clock, Ledger ledger,
                          SessionRecords records, DailyService daily) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.ledger = ledger;
        this.records = records;
        this.daily = daily;
    }

    public ActiveView view() {
        long now = clock.now();
        return new ActiveView(now, active(now).orElse(null));
    }

    public Optional<ActiveSession> active(long now) {
        // 先取出行再算分段，不在结果集还开着的时候嵌套查询
        Optional<ActiveSession> head = jdbc.query("""
                SELECT s.id, s.method_id, m.name, m.subject, s.chapter, m.path_id, p.color, s.status, s.started_at,
                       s.target_seconds, s.last_active_at
                FROM session s JOIN method m ON m.id = s.method_id JOIN path p ON p.id = m.path_id
                WHERE s.status IN ('RUNNING', 'PAUSED')
                ORDER BY s.started_at DESC LIMIT 1
                """, (rs, i) -> {
                    long target = rs.getLong("target_seconds");
                    Long targetSeconds = rs.wasNull() ? null : target;
                    return new ActiveSession(rs.getLong("id"), rs.getLong("method_id"), rs.getString("name"),
                            rs.getString("subject"), rs.getString("chapter"), rs.getLong("path_id"),
                            rs.getString("color"), rs.getString("status"), rs.getLong("started_at"), targetSeconds,
                            0, null, rs.getLong("last_active_at"));
                }).stream().findFirst();
        return head.map(a -> {
            Long openStart = openSegmentStart(a.id());
            long ms = closedMillis(a.id()) + (openStart == null ? 0 : Math.max(0, now - openStart));
            return new ActiveSession(a.id(), a.methodId(), a.methodName(), a.subject(), a.chapter(), a.pathId(),
                    a.color(), a.status(), a.startedAt(), a.targetSeconds(), ms / 1000, openStart, a.lastActiveAt());
        });
    }

    /** 最近修炼的功法：进行中的优先，否则取最近出关的。 */
    public Long lastMethodId(ActiveSession active) {
        if (active != null) {
            return active.methodId();
        }
        return jdbc.query("SELECT method_id FROM session WHERE status = 'DONE' ORDER BY ended_at DESC, id DESC LIMIT 1",
                (rs, i) -> rs.getLong(1)).stream().findFirst().orElse(null);
    }

    public ActiveView start(Long methodId, Integer targetMinutes) {
        if (methodId == null) {
            throw ApiException.badRequest("请选择功法");
        }
        if (targetMinutes != null && (targetMinutes < 1 || targetMinutes > 720)) {
            throw ApiException.badRequest("目标时长需在 1–720 分钟之间");
        }
        Long targetSeconds = targetMinutes == null ? null : targetMinutes * 60L;
        synchronized (lock) {
            return tx.execute(status -> {
                long now = clock.now();
                if (active(now).isPresent()) {
                    throw ApiException.conflict("已在入定中");
                }
                MethodRow m = method(methodId);
                if (m.archived()) {
                    throw ApiException.conflict("此功法已束之高阁，先取下来再修");
                }
                long id = jdbc.queryForObject("""
                        INSERT INTO session(method_id, chapter, source, status, target_seconds, started_at, last_active_at)
                        VALUES (?, ?, 'TIMER', 'RUNNING', ?, ?, ?) RETURNING id
                        """, Long.class, methodId, m.chapter(), targetSeconds, now, now);
                jdbc.update("INSERT INTO segment(session_id, start_at) VALUES (?, ?)", id, now);
                return new ActiveView(now, active(now).orElseThrow());
            });
        }
    }

    public ActiveView pause(long id) {
        return changeActive(id, (row, now) -> {
            if (!"RUNNING".equals(row.status())) {
                throw ApiException.conflict("此刻并未在入定");
            }
            jdbc.update("UPDATE segment SET end_at = ? WHERE session_id = ? AND end_at IS NULL", now, id);
            jdbc.update("UPDATE session SET status = 'PAUSED', last_active_at = ? WHERE id = ?", now, id);
        });
    }

    public ActiveView resume(long id) {
        return changeActive(id, (row, now) -> {
            if (!"PAUSED".equals(row.status())) {
                throw ApiException.conflict("此刻并未在调息");
            }
            jdbc.update("INSERT INTO segment(session_id, start_at) VALUES (?, ?)", id, now);
            jdbc.update("UPDATE session SET status = 'RUNNING', last_active_at = ? WHERE id = ?", now, id);
        });
    }

    /** 回应"道友还在否"：只刷新最近活动时间。 */
    public ActiveView touch(long id) {
        return changeActive(id, (row, now) ->
                jdbc.update("UPDATE session SET last_active_at = ? WHERE id = ?", now, id));
    }

    /**
     * 出关。endAt 用于"忘关了，按某时出关"：之后的分段删掉，跨过它的分段截到它。
     * 有效时长不足一分钟的直接删除。
     */
    public Settlement finish(long id, Long endAt) {
        // 在事务外生成今日功课：结算里要给"顺手勾上"的建议
        daily.ensureGenerated(clock.today());
        synchronized (lock) {
            return tx.execute(status -> {
                long now = clock.now();
                Row row = requireActive(id);
                long end;
                if (endAt != null) {
                    if (endAt <= row.startedAt() || endAt > now) {
                        throw ApiException.badRequest("出关时间需在入定之后、此刻之前");
                    }
                    jdbc.update("DELETE FROM segment WHERE session_id = ? AND start_at >= ?", id, endAt);
                    jdbc.update("UPDATE segment SET end_at = ? WHERE session_id = ? AND (end_at IS NULL OR end_at > ?)",
                            endAt, id, endAt);
                    end = endAt;
                } else {
                    jdbc.update("UPDATE segment SET end_at = ? WHERE session_id = ? AND end_at IS NULL", now, id);
                    end = now;
                }
                if (closedMillis(id) < MIN_MILLIS) {
                    jdbc.update("DELETE FROM session WHERE id = ?", id);
                    return Settlement.discardedOne();
                }
                long methodBefore = ledger.allTimeForMethod(row.methodId());
                long realmBefore = ledger.allTimeByBody(row.body());
                jdbc.update("UPDATE session SET status = 'DONE', ended_at = ?, last_active_at = ? WHERE id = ?",
                        end, now, id);
                return settle(id, row.methodId(), row.pathId(), row.body(), methodBefore, realmBefore);
            });
        }
    }

    /** 补记：健身房、图书馆没带电脑、忘了开计时。 */
    public Settlement manual(Long methodId, Long startAt, Integer minutes, String chapter, String note) {
        if (methodId == null) {
            throw ApiException.badRequest("请选择功法");
        }
        if (startAt == null || startAt <= 0) {
            throw ApiException.badRequest("请填写开始时间");
        }
        if (minutes == null || minutes < 1 || minutes > 720) {
            throw ApiException.badRequest("时长需在 1–720 分钟之间");
        }
        String ch = Checks.optional(chapter, "篇章", 30);
        String nt = Checks.optional(note, "备注", 200);
        daily.ensureGenerated(clock.today());
        synchronized (lock) {
            return tx.execute(status -> {
                long now = clock.now();
                long end = startAt + minutes * 60_000L;
                if (end > now + 60_000) {
                    throw ApiException.badRequest("补记的修炼不能结束在未来");
                }
                MethodRow m = method(methodId);
                long methodBefore = ledger.allTimeForMethod(methodId);
                long realmBefore = ledger.allTimeByBody(m.body());
                long id = jdbc.queryForObject("""
                        INSERT INTO session(method_id, chapter, source, status, started_at, ended_at, last_active_at, note)
                        VALUES (?, ?, 'MANUAL', 'DONE', ?, ?, ?, ?) RETURNING id
                        """, Long.class, methodId, ch != null ? ch : m.chapter(), startAt, end, now, nt);
                jdbc.update("INSERT INTO segment(session_id, start_at, end_at) VALUES (?, ?, ?)", id, startAt, end);
                return settle(id, methodId, m.pathId(), m.body(), methodBefore, realmBefore);
            });
        }
    }

    /** from/to 为修炼日（含两端），默认最近 30 天。 */
    public List<SessionRecord> list(String from, String to, Long methodId, Integer limit) {
        LocalDate t = to == null || to.isBlank() ? clock.today() : Checks.day(to);
        LocalDate f = from == null || from.isBlank() ? t.minusDays(29) : Checks.day(from);
        if (f.isAfter(t)) {
            throw ApiException.badRequest("起始日期不能晚于结束日期");
        }
        int lim = limit == null ? 200 : Math.max(1, Math.min(1000, limit));
        StringBuilder tail = new StringBuilder("WHERE s.status = 'DONE' AND s.started_at >= ? AND s.started_at < ?");
        List<Object> args = new ArrayList<>(List.of(clock.startOf(f), clock.endOf(t)));
        if (methodId != null) {
            tail.append(" AND s.method_id = ?");
            args.add(methodId);
        }
        tail.append(" ORDER BY s.started_at DESC LIMIT ?");
        args.add(lim);
        return records.find(tail.toString(), args.toArray());
    }

    /** 删一条记录，进行中的也可以删（"此次不算"）。 */
    public void delete(long id) {
        synchronized (lock) {
            if (jdbc.update("DELETE FROM session WHERE id = ?", id) == 0) {
                throw ApiException.notFound("未找到此次修炼");
            }
        }
    }

    private Settlement settle(long sessionId, long methodId, long pathId, boolean body, long methodBefore,
                              long realmBefore) {
        SessionRecord rec = records.byId(sessionId).orElseThrow();
        RealmInfo masteryBefore = Realms.of(Realms.Kind.MASTERY, methodBefore);
        RealmInfo masteryAfter = Realms.of(Realms.Kind.MASTERY, ledger.allTimeForMethod(methodId));

        Realms.Kind kind = body ? Realms.Kind.BODY : Realms.Kind.CULTIVATION;
        RealmInfo before = Realms.of(kind, realmBefore);
        RealmInfo after = Realms.of(kind, ledger.allTimeByBody(body));
        boolean breakthrough = after.index() > before.index();
        boolean major = kind == Realms.Kind.CULTIVATION && !after.major().equals(before.major());

        LocalDate today = clock.today();
        long todaySeconds = ledger.totalSeconds(clock.startOf(today), clock.endOf(today));
        Settlement.SuggestItem suggest = jdbc.query("""
                        SELECT id, name FROM daily_item WHERE day = ? AND path_id = ? AND done = 0
                        ORDER BY sort, id LIMIT 1
                        """, (rs, i) -> new Settlement.SuggestItem(rs.getLong(1), rs.getString(2)),
                today.toString(), pathId).stream().findFirst().orElse(null);

        return new Settlement(false, rec,
                new Settlement.MethodPart(methodId, rec.methodName(), rec.chapter(), masteryBefore, masteryAfter),
                new Settlement.RealmPart(kind.name(), before, after, breakthrough, major),
                todaySeconds, suggest);
    }

    private ActiveView changeActive(long id, BiConsumer<Row, Long> change) {
        synchronized (lock) {
            return tx.execute(status -> {
                long now = clock.now();
                change.accept(requireActive(id), now);
                return new ActiveView(now, active(now).orElse(null));
            });
        }
    }

    private Row requireActive(long id) {
        Row row = jdbc.query(ROW_SELECT + " WHERE s.id = ?", ROW, id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("未找到此次修炼"));
        if (!"RUNNING".equals(row.status()) && !"PAUSED".equals(row.status())) {
            throw ApiException.conflict("此次修炼已出关");
        }
        return row;
    }

    private MethodRow method(long methodId) {
        return jdbc.query("""
                        SELECT m.id, m.chapter, m.path_id, p.body, m.archived
                        FROM method m JOIN path p ON p.id = m.path_id WHERE m.id = ?
                        """,
                (rs, i) -> new MethodRow(rs.getLong("id"), rs.getString("chapter"), rs.getLong("path_id"),
                        rs.getInt("body") == 1, rs.getInt("archived") == 1),
                methodId).stream().findFirst().orElseThrow(() -> ApiException.notFound("未找到此功法"));
    }

    private long closedMillis(long sessionId) {
        Long ms = jdbc.queryForObject(
                "SELECT COALESCE(SUM(end_at - start_at), 0) FROM segment WHERE session_id = ? AND end_at IS NOT NULL",
                Long.class, sessionId);
        return ms == null ? 0 : ms;
    }

    private Long openSegmentStart(long sessionId) {
        return jdbc.query("SELECT start_at FROM segment WHERE session_id = ? AND end_at IS NULL "
                        + "ORDER BY start_at DESC LIMIT 1", (rs, i) -> rs.getLong(1), sessionId)
                .stream().findFirst().orElse(null);
    }
}
