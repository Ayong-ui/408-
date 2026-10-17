package com.xiuxian.path;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import com.xiuxian.common.ApiException;
import com.xiuxian.common.Checks;

/** 道脉的增删改查。 */
@Service
public class PathService {

    private static final RowMapper<PathInfo> MAPPER = (rs, i) -> new PathInfo(
            rs.getLong("id"), rs.getString("name"), rs.getString("color"),
            rs.getInt("core") == 1, rs.getInt("body") == 1, rs.getInt("sort"));

    private final JdbcTemplate jdbc;

    public PathService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PathInfo> list() {
        return jdbc.query("SELECT * FROM path ORDER BY sort, id", MAPPER);
    }

    public Optional<PathInfo> find(long id) {
        return jdbc.query("SELECT * FROM path WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public PathInfo get(long id) {
        return find(id).orElseThrow(() -> ApiException.notFound("未找到此道脉"));
    }

    public PathInfo create(String name, String color, Boolean core, Boolean body) {
        String n = Checks.required(name, "道脉名", 1, 10);
        String c = Checks.color(color);
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(sort), 0) FROM path", Integer.class);
        long id = jdbc.queryForObject(
                "INSERT INTO path(name, color, core, body, sort) VALUES (?, ?, ?, ?, ?) RETURNING id", Long.class,
                n, c, Checks.bool(core, false) ? 1 : 0, Checks.bool(body, false) ? 1 : 0, (max == null ? 0 : max) + 1);
        return get(id);
    }

    public PathInfo update(long id, String name, String color, Boolean core, Boolean body, Integer sort) {
        PathInfo old = get(id);
        String n = Checks.required(name, "道脉名", 1, 10);
        String c = Checks.color(color);
        jdbc.update("UPDATE path SET name = ?, color = ?, core = ?, body = ?, sort = ? WHERE id = ?",
                n, c, Checks.bool(core, old.core()) ? 1 : 0, Checks.bool(body, old.body()) ? 1 : 0,
                sort == null ? old.sort() : sort, id);
        return get(id);
    }

    public void delete(long id) {
        get(id);
        Integer methods = jdbc.queryForObject("SELECT COUNT(*) FROM method WHERE path_id = ?", Integer.class, id);
        if (methods != null && methods > 0) {
            throw ApiException.conflict("此道脉下还有功法");
        }
        jdbc.update("DELETE FROM path WHERE id = ?", id);
    }
}
