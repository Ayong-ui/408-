package com.xiuxian.home;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.xiuxian.common.DayClock;
import com.xiuxian.common.Ledger;
import com.xiuxian.common.Realms;
import com.xiuxian.common.Realms.RealmInfo;
import com.xiuxian.common.SettingsService;
import com.xiuxian.daily.DailyItem;
import com.xiuxian.daily.DailyService;
import com.xiuxian.log.LogService;
import com.xiuxian.method.MethodBrief;
import com.xiuxian.method.MethodService;
import com.xiuxian.path.PathInfo;
import com.xiuxian.path.PathService;
import com.xiuxian.schedule.ScheduleService;
import com.xiuxian.session.ActiveSession;
import com.xiuxian.session.SessionService;

/** 洞府首页：一次拿齐首页要的数据，手机网络慢时少跑几趟。 */
@Service
public class HomeService {

    /** 三脉之一：今天碰过（修炼过、正在修炼或勾了功课）就亮。 */
    public record Meridian(long pathId, String name, String color, boolean lit) {
    }

    public record HomeView(long now, String day, int weekday, Map<String, Object> settings, RealmInfo cultivation,
                           RealmInfo body, long todaySeconds, int monthVisitDays, List<DailyItem> items,
                           List<Meridian> meridians, ActiveSession active, ScheduleService.Slot slot,
                           List<ScheduleService.Countdown> countdowns, LogService.LogEntry log,
                           List<MethodBrief> methods, Long lastMethodId) {
    }

    private final JdbcTemplate jdbc;
    private final DayClock clock;
    private final Ledger ledger;
    private final SettingsService settings;
    private final DailyService daily;
    private final SessionService sessions;
    private final MethodService methods;
    private final PathService paths;
    private final ScheduleService schedule;
    private final LogService logs;

    public HomeService(JdbcTemplate jdbc, DayClock clock, Ledger ledger, SettingsService settings, DailyService daily,
                       SessionService sessions, MethodService methods, PathService paths, ScheduleService schedule,
                       LogService logs) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.ledger = ledger;
        this.settings = settings;
        this.daily = daily;
        this.sessions = sessions;
        this.methods = methods;
        this.paths = paths;
        this.schedule = schedule;
        this.logs = logs;
    }

    public HomeView home() {
        long now = clock.now();
        LocalDate today = clock.dayOf(now);
        daily.ensureGenerated(today);
        List<DailyItem> items = daily.items(today);

        Map<Long, Long> todayByPath = ledger.secondsByPath(clock.startOf(today), clock.endOf(today));
        long todaySeconds = todayByPath.values().stream().mapToLong(Long::longValue).sum();
        ActiveSession active = sessions.active(now).orElse(null);

        List<Meridian> meridians = paths.list().stream()
                .filter(PathInfo::core)
                .map(p -> new Meridian(p.id(), p.name(), p.color(),
                        todayByPath.getOrDefault(p.id(), 0L) > 0
                                || (active != null && active.pathId() == p.id())
                                || items.stream().anyMatch(it -> it.done() && Objects.equals(it.pathId(), p.id()))))
                .toList();

        // 最近用过的功法排在前面，方便一键入定
        List<MethodBrief> recent = new ArrayList<>(methods.list("false"));
        recent.sort(Comparator.comparing(MethodBrief::lastUsedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparingInt(MethodBrief::sort)
                .thenComparingLong(MethodBrief::id));

        return new HomeView(now, today.toString(), today.getDayOfWeek().getValue(), settings.all(),
                Realms.of(Realms.Kind.CULTIVATION, ledger.allTimeByBody(false)),
                Realms.of(Realms.Kind.BODY, ledger.allTimeByBody(true)),
                todaySeconds, monthVisitDays(today), items, meridians, active,
                schedule.current(clock.at(now).toLocalTime()).orElse(null),
                schedule.upcoming(),
                logs.find(today).orElse(null),
                recent,
                sessions.lastMethodId(active));
    }

    /** 本月有动静的修炼日数：有修炼、有完成的功课或写了日志。 */
    int monthVisitDays(LocalDate today) {
        LocalDate first = today.withDayOfMonth(1);
        Set<LocalDate> days = new HashSet<>();
        jdbc.query("SELECT day FROM daily_log WHERE day BETWEEN ? AND ?",
                rs -> {
                    days.add(LocalDate.parse(rs.getString(1)));
                }, first.toString(), today.toString());
        jdbc.query("SELECT DISTINCT day FROM daily_item WHERE done = 1 AND day BETWEEN ? AND ?",
                rs -> {
                    days.add(LocalDate.parse(rs.getString(1)));
                }, first.toString(), today.toString());

        long from = clock.startOf(first);
        long to = clock.endOf(today);
        for (Ledger.Seg s : ledger.doneSegments(from, to)) {
            long start = Math.max(s.start(), from);
            long end = Math.min(s.end(), to);
            if (end <= start) {
                continue;
            }
            // 跨日的分段，覆盖到的修炼日都算
            for (LocalDate d = clock.dayOf(start); !d.isAfter(clock.dayOf(end - 1)); d = d.plusDays(1)) {
                days.add(d);
            }
        }
        return (int) days.stream().filter(d -> !d.isBefore(first) && !d.isAfter(today)).count();
    }
}
