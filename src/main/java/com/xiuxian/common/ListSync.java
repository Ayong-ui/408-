package com.xiuxian.common;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 整表同步：前端把整个列表（日课模板、作息、传送阵、劫数）一次性提交上来。
 * 带 id 且库里存在的更新，其余新增，库里有而列表里没有的删除；sort 按列表顺序重排为 1..n。
 * 调用方负责开事务。
 */
public final class ListSync {

    private ListSync() {
    }

    public interface Writer<T> {
        long insert(T item, int sort);

        void update(long id, T item, int sort);
    }

    /** ids 按列表顺序；inserted 是本次新增的 id。 */
    public record Result(List<Long> ids, Set<Long> inserted) {
    }

    public static <T> Result sync(JdbcTemplate jdbc, String table, List<T> items, Function<T, Long> idOf,
                                  Writer<T> writer) {
        Set<Long> existing = new HashSet<>(jdbc.queryForList("SELECT id FROM " + table, Long.class));
        Set<Long> keep = items.stream()
                .map(idOf)
                .filter(Objects::nonNull)
                .filter(existing::contains)
                .collect(Collectors.toSet());
        for (Long id : existing) {
            if (!keep.contains(id)) {
                jdbc.update("DELETE FROM " + table + " WHERE id = ?", id);
            }
        }

        List<Long> ids = new ArrayList<>();
        Set<Long> inserted = new LinkedHashSet<>();
        Set<Long> used = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            T item = items.get(i);
            Long id = idOf.apply(item);
            int sort = i + 1;
            // 同一个 id 在列表里出现两次时，第二次当新增
            if (id != null && keep.contains(id) && used.add(id)) {
                writer.update(id, item, sort);
                ids.add(id);
            } else {
                long newId = writer.insert(item, sort);
                ids.add(newId);
                inserted.add(newId);
            }
        }
        return new Result(ids, inserted);
    }
}
