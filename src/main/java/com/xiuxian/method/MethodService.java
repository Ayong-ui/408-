package com.xiuxian.method;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import com.xiuxian.common.ApiException;
import com.xiuxian.common.Checks;
import com.xiuxian.common.DayClock;
import com.xiuxian.common.Ledger;
import com.xiuxian.common.Realms;
import com.xiuxian.path.PathService;
import com.xiuxian.session.SessionRecord;
import com.xiuxian.session.SessionRecords;

/** 藏经阁：功法的增删改查、封存与散功。熟练度按累计时长现算。 */
@Service
public class MethodService {

    public record MethodInput(String name, String subject, String chapter, Long pathId, String description,
                              Integer sort) {
    }

    private record Valid(String name, String subject, String chapter, long pathId, String description) {
    }

    private static final String SELECT = """
            SELECT m.id, m.name, m.subject, m.chapter, m.description, m.path_id, p.name AS path_name, p.color,
                   m.archived, m.sort,
                   (SELECT MAX(s.ended_at) FROM session s WHERE s.method_id = m.id AND s.status = 'DONE') AS last_used_at
            FROM method m JOIN path p ON p.id = m.path_id
            """;

    private final JdbcTemplate jdbc;
    private final Ledger ledger;
    private final DayClock clock;
    private final SessionRecords records;
    private final PathService paths;

    public MethodService(JdbcTemplate jdbc, Ledger ledger, DayClock clock, SessionRecords records, PathService paths) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.clock = clock;
        this.records = records;
        this.paths = paths;
    }

    /** archived：false 只要未封存的，true 只要封存的，all 全要。 */
    public List<MethodBrief> list(String archived) {
        String where = switch (archived == null ? "false" : archived) {
            case "false" -> "WHERE m.archived = 0";
            case "true" -> "WHERE m.archived = 1";
            case "all" -> "";
            default -> throw ApiException.badRequest("archived 只能是 false / true / all");
        };
        return jdbc.query(SELECT + where + " ORDER BY m.sort, m.id", mapper(ledger.allTimeByMethod()));
    }

    public MethodBrief get(long id) {
        long total = ledger.allTimeForMethod(id);
        return jdbc.query(SELECT + " WHERE m.id = ?", mapper(Map.of(id, total)), id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("未找到此功法"));
    }

    public MethodDetail detail(long id) {
        MethodBrief brief = get(id);
        List<MethodDetail.ChapterStat> chapters = jdbc.query("""
                SELECT COALESCE(NULLIF(TRIM(s.chapter), ''), '未分篇') AS ch, COUNT(*) AS n,
                       SUM((SELECT COALESCE(SUM(g.end_at - g.start_at), 0) FROM segment g
                            WHERE g.session_id = s.id AND g.end_at IS NOT NULL)) AS ms
                FROM session s
                WHERE s.method_id = ? AND s.status = 'DONE'
                GROUP BY ch
                ORDER BY ms DESC
                """, (rs, i) -> new MethodDetail.ChapterStat(rs.getString("ch"), rs.getLong("ms") / 1000,
                rs.getInt("n")), id);
        List<SessionRecord> recent = records.find(
                "WHERE s.method_id = ? AND s.status = 'DONE' ORDER BY s.started_at DESC LIMIT 20", id);

        LocalDate today = clock.today();
        LocalDate first = today.minusDays(29);
        List<Ledger.Seg> segs = ledger.doneSegments(clock.startOf(first), clock.endOf(today)).stream()
                .filter(s -> s.methodId() == id)
                .toList();
        List<MethodDetail.DayStat> daily = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
            long from = clock.startOf(d);
            long to = clock.endOf(d);
            long ms = 0;
            for (Ledger.Seg s : segs) {
                ms += Ledger.overlapMillis(s.start(), s.end(), from, to);
            }
            daily.add(new MethodDetail.DayStat(d.toString(), ms / 1000));
        }
        return new MethodDetail(brief, chapters, recent, daily);
    }

    public MethodBrief create(MethodInput in) {
        Valid v = validate(in);
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(sort), 0) FROM method", Integer.class);
        long id = jdbc.queryForObject("""
                INSERT INTO method(path_id, name, subject, chapter, description, sort, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, v.pathId(), v.name(), v.subject(), v.chapter(), v.description(),
                (max == null ? 0 : max) + 1, clock.now());
        return get(id);
    }

    public MethodBrief update(long id, MethodInput in) {
        MethodBrief old = get(id);
        Valid v = validate(in);
        jdbc.update("UPDATE method SET path_id = ?, name = ?, subject = ?, chapter = ?, description = ?, sort = ? "
                        + "WHERE id = ?",
                v.pathId(), v.name(), v.subject(), v.chapter(), v.description(),
                in.sort() == null ? old.sort() : in.sort(), id);
        return get(id);
    }

    /** 封存（束之高阁）或取回。正在修炼的功法不能封存。 */
    public MethodBrief setArchived(long id, boolean archived) {
        get(id);
        if (archived && inProgress(id)) {
            throw ApiException.conflict("此功法正在修炼中，先出关再封存");
        }
        jdbc.update("UPDATE method SET archived = ? WHERE id = ?", archived ? 1 : 0, id);
        return get(id);
    }

    /** 散功：连同全部修炼记录一起删除（外键级联）。 */
    public void delete(long id) {
        get(id);
        if (inProgress(id)) {
            throw ApiException.conflict("此功法正在修炼中，先出关再散功");
        }
        jdbc.update("DELETE FROM method WHERE id = ?", id);
    }

    private boolean inProgress(long id) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM session WHERE method_id = ? AND status IN ('RUNNING', 'PAUSED')",
                Integer.class, id);
        return n != null && n > 0;
    }

    private Valid validate(MethodInput in) {
        if (in == null) {
            throw ApiException.badRequest("功法内容不能为空");
        }
        if (in.pathId() == null) {
            throw ApiException.badRequest("请选择道脉");
        }
        if (paths.find(in.pathId()).isEmpty()) {
            throw ApiException.badRequest("未找到此道脉");
        }
        return new Valid(Checks.required(in.name(), "功法名", 1, 20),
                Checks.optional(in.subject(), "科目", 30),
                Checks.optional(in.chapter(), "篇章", 30),
                in.pathId(),
                Checks.optional(in.description(), "描述", 200));
    }

    private static RowMapper<MethodBrief> mapper(Map<Long, Long> totals) {
        return (rs, i) -> {
            long id = rs.getLong("id");
            long total = totals.getOrDefault(id, 0L);
            long last = rs.getLong("last_used_at");
            Long lastUsedAt = rs.wasNull() ? null : last;
            return new MethodBrief(id, rs.getString("name"), rs.getString("subject"), rs.getString("chapter"),
                    rs.getString("description"), rs.getLong("path_id"), rs.getString("path_name"),
                    rs.getString("color"), rs.getInt("archived") == 1, rs.getInt("sort"), total,
                    Realms.of(Realms.Kind.MASTERY, total), lastUsedAt);
        };
    }
}
