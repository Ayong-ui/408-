package com.xiuxian.observatory;

import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.xiuxian.common.ApiException;
import com.xiuxian.common.Checks;
import com.xiuxian.common.DayClock;
import com.xiuxian.common.ListSync;

/** 观星台：传送阵（常逛的网站，整表同步）和情报簿（手动收藏的链接）。 */
@Service
public class ObservatoryService {

    public record Portal(long id, String name, String url, String note, boolean enabled, int sort) {
    }

    public record Intel(long id, String title, String url, String note, String status, long createdAt) {
    }

    public record PortalInput(Long id, String name, String url, String note, Boolean enabled) {
    }

    public record IntelInput(String title, String url, String note, String status) {
    }

    private static final Set<String> STATUSES = Set.of("想研究", "已试用", "纯粹觉得很酷");
    private static final String DEFAULT_STATUS = "想研究";

    private static final RowMapper<Portal> PORTAL = (rs, i) -> new Portal(rs.getLong("id"), rs.getString("name"),
            rs.getString("url"), rs.getString("note"), rs.getInt("enabled") == 1, rs.getInt("sort"));
    private static final RowMapper<Intel> INTEL = (rs, i) -> new Intel(rs.getLong("id"), rs.getString("title"),
            rs.getString("url"), rs.getString("note"), rs.getString("status"), rs.getLong("created_at"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final DayClock clock;

    public ObservatoryService(JdbcTemplate jdbc, TransactionTemplate tx, DayClock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    public List<Portal> portals() {
        return jdbc.query("SELECT * FROM portal ORDER BY sort, id", PORTAL);
    }

    public List<Portal> syncPortals(List<PortalInput> input) {
        List<PortalInput> clean = input.stream().map(ObservatoryService::validate).toList();
        tx.executeWithoutResult(status -> ListSync.sync(jdbc, "portal", clean, PortalInput::id,
                new ListSync.Writer<>() {
                    @Override
                    public long insert(PortalInput p, int sort) {
                        return jdbc.queryForObject("INSERT INTO portal(name, url, note, enabled, sort) "
                                        + "VALUES (?, ?, ?, ?, ?) RETURNING id", Long.class,
                                p.name(), p.url(), p.note(), p.enabled() ? 1 : 0, sort);
                    }

                    @Override
                    public void update(long id, PortalInput p, int sort) {
                        jdbc.update("UPDATE portal SET name = ?, url = ?, note = ?, enabled = ?, sort = ? WHERE id = ?",
                                p.name(), p.url(), p.note(), p.enabled() ? 1 : 0, sort, id);
                    }
                }));
        return portals();
    }

    public List<Intel> intel(String status) {
        if (status == null || status.isBlank()) {
            return jdbc.query("SELECT * FROM intel ORDER BY created_at DESC, id DESC", INTEL);
        }
        return jdbc.query("SELECT * FROM intel WHERE status = ? ORDER BY created_at DESC, id DESC", INTEL,
                checkStatus(status));
    }

    public Intel createIntel(IntelInput in) {
        IntelInput v = validate(in);
        long id = jdbc.queryForObject("INSERT INTO intel(title, url, note, status, created_at) VALUES (?, ?, ?, ?, ?) "
                + "RETURNING id", Long.class, v.title(), v.url(), v.note(), v.status(), clock.now());
        return intelById(id);
    }

    public Intel updateIntel(long id, IntelInput in) {
        intelById(id);
        IntelInput v = validate(in);
        jdbc.update("UPDATE intel SET title = ?, url = ?, note = ?, status = ? WHERE id = ?",
                v.title(), v.url(), v.note(), v.status(), id);
        return intelById(id);
    }

    public void deleteIntel(long id) {
        intelById(id);
        jdbc.update("DELETE FROM intel WHERE id = ?", id);
    }

    private Intel intelById(long id) {
        return jdbc.query("SELECT * FROM intel WHERE id = ?", INTEL, id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("未找到此条情报"));
    }

    private static PortalInput validate(PortalInput p) {
        if (p == null) {
            throw ApiException.badRequest("传送阵不能为空");
        }
        return new PortalInput(p.id(), Checks.required(p.name(), "传送阵名", 1, 20), Checks.url(p.url(), true, 2000),
                Checks.optional(p.note(), "备注", 60), Checks.bool(p.enabled(), true));
    }

    private static IntelInput validate(IntelInput in) {
        if (in == null) {
            throw ApiException.badRequest("情报不能为空");
        }
        String status = in.status() == null || in.status().isBlank() ? DEFAULT_STATUS : checkStatus(in.status());
        return new IntelInput(Checks.required(in.title(), "标题", 1, 100), Checks.url(in.url(), false, 2000),
                Checks.optional(in.note(), "备注", 500), status);
    }

    private static String checkStatus(String status) {
        String s = status.strip();
        if (!STATUSES.contains(s)) {
            throw ApiException.badRequest("状态只能是 想研究 / 已试用 / 纯粹觉得很酷");
        }
        return s;
    }
}
