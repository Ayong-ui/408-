package com.xiuxian.stats;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

import com.xiuxian.common.ApiException;
import com.xiuxian.common.DayClock;
import com.xiuxian.common.Ledger;
import com.xiuxian.path.PathInfo;
import com.xiuxian.path.PathService;

/** 灵力分布（各道脉时长占比，含上一周期同期）与修炼趋势（每日总时长）。 */
@Service
public class StatsService {

    public record Distribution(String range, String from, String to, long totalSeconds, long prevTotalSeconds,
                               List<Item> items) {
        public record Item(long pathId, String name, String color, long seconds, double ratio, long prevSeconds) {
        }
    }

    public record Trend(List<Day> days) {
        public record Day(String day, long seconds, Map<String, Long> byPath) {
        }
    }

    /** 统计区间 [from, to) 和上一周期同期 [prevFrom, prevTo)。 */
    record Window(LocalDate firstDay, long from, long to, long prevFrom, long prevTo) {
    }

    private final DayClock clock;
    private final Ledger ledger;
    private final PathService paths;

    public StatsService(DayClock clock, Ledger ledger, PathService paths) {
        this.clock = clock;
        this.ledger = ledger;
        this.paths = paths;
    }

    public Distribution distribution(String range) {
        String r = range == null || range.isBlank() ? "week" : range;
        long now = clock.now();
        LocalDate today = clock.dayOf(now);
        Window w = window(r, today, now);

        Map<Long, Long> current = ledger.secondsByPath(w.from(), w.to());
        Map<Long, Long> prev = ledger.secondsByPath(w.prevFrom(), w.prevTo());
        long total = current.values().stream().mapToLong(Long::longValue).sum();
        long prevTotal = prev.values().stream().mapToLong(Long::longValue).sum();

        List<Distribution.Item> items = new ArrayList<>();
        for (PathInfo p : paths.list()) {
            long secs = current.getOrDefault(p.id(), 0L);
            double ratio = total == 0 ? 0 : Math.round(secs * 10000.0 / total) / 10000.0;
            items.add(new Distribution.Item(p.id(), p.name(), p.color(), secs, ratio, prev.getOrDefault(p.id(), 0L)));
        }
        return new Distribution(r, w.firstDay().toString(), today.toString(), total, prevTotal, items);
    }

    /**
     * 区间终点是此刻；上一周期从对应的起点开始、取同样长的一段，这样周三看本周时比的是上周一到周三。
     * 本月的上一周期不超过上月末。
     */
    Window window(String range, LocalDate today, long now) {
        return switch (range) {
            case "today" -> {
                long from = clock.startOf(today);
                long prevFrom = clock.startOf(today.minusDays(1));
                yield new Window(today, from, now, prevFrom, prevFrom + (now - from));
            }
            case "week" -> {
                LocalDate monday = clock.weekStart(today);
                long from = clock.startOf(monday);
                long prevFrom = clock.startOf(monday.minusWeeks(1));
                yield new Window(monday, from, now, prevFrom, prevFrom + (now - from));
            }
            case "month" -> {
                LocalDate first = today.withDayOfMonth(1);
                long from = clock.startOf(first);
                long prevFrom = clock.startOf(first.minusMonths(1));
                long prevTo = Math.min(prevFrom + (now - from), from);
                yield new Window(first, from, now, prevFrom, prevTo);
            }
            default -> throw ApiException.badRequest("range 只能是 today / week / month");
        };
    }

    /** 最近 days 个修炼日的每日总时长，旧→新，最后一个是今天。 */
    public Trend trend(Integer days) {
        int n = days == null ? 7 : days;
        if (n < 1 || n > 90) {
            throw ApiException.badRequest("days 需在 1–90 之间");
        }
        LocalDate today = clock.today();
        LocalDate first = today.minusDays(n - 1L);
        List<Ledger.Seg> segs = ledger.doneSegments(clock.startOf(first), clock.endOf(today));

        List<Trend.Day> out = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
            long from = clock.startOf(d);
            long to = clock.endOf(d);
            Map<Long, Long> millis = new HashMap<>();
            for (Ledger.Seg s : segs) {
                long ms = Ledger.overlapMillis(s.start(), s.end(), from, to);
                if (ms > 0) {
                    millis.merge(s.pathId(), ms, Long::sum);
                }
            }
            Map<String, Long> byPath = new LinkedHashMap<>();
            long total = 0;
            for (Map.Entry<Long, Long> e : new TreeMap<>(millis).entrySet()) {
                long secs = e.getValue() / 1000;
                byPath.put(String.valueOf(e.getKey()), secs);
                total += secs;
            }
            out.add(new Trend.Day(d.toString(), total, byPath));
        }
        return new Trend(out);
    }
}
