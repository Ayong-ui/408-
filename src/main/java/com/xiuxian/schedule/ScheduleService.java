package com.xiuxian.schedule;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.xiuxian.common.ApiException;
import com.xiuxian.common.Checks;
import com.xiuxian.common.DayClock;
import com.xiuxian.common.ListSync;

/** 作息框架与劫数（考试倒计时）。两者都是整表同步的小列表。 */
@Service
public class ScheduleService {

    public record Slot(long id, String name, String startTime, String endTime, String hint, int sort) {
    }

    public record Countdown(long id, String name, String day, boolean enabled, int sort, long daysLeft) {
    }

    public record SlotInput(Long id, String name, String startTime, String endTime, String hint) {
    }

    public record CountdownInput(Long id, String name, String day, Boolean enabled) {
    }

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final RowMapper<Slot> SLOT = (rs, i) -> new Slot(rs.getLong("id"), rs.getString("name"),
            rs.getString("start_time"), rs.getString("end_time"), rs.getString("hint"), rs.getInt("sort"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final DayClock clock;

    public ScheduleService(JdbcTemplate jdbc, TransactionTemplate tx, DayClock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    public List<Slot> slots() {
        return jdbc.query("SELECT * FROM schedule_slot ORDER BY sort, id", SLOT);
    }

    /** 此刻落在哪个时段：startTime <= HH:mm < endTime。 */
    public Optional<Slot> current(LocalTime time) {
        String t = time.format(HHMM);
        return slots().stream()
                .filter(s -> s.startTime().compareTo(t) <= 0 && t.compareTo(s.endTime()) < 0)
                .findFirst();
    }

    public List<Slot> syncSlots(List<SlotInput> input) {
        List<SlotInput> clean = input.stream().map(ScheduleService::validate).toList();
        tx.executeWithoutResult(status -> ListSync.sync(jdbc, "schedule_slot", clean, SlotInput::id,
                new ListSync.Writer<>() {
                    @Override
                    public long insert(SlotInput s, int sort) {
                        return jdbc.queryForObject("INSERT INTO schedule_slot(name, start_time, end_time, hint, sort) "
                                        + "VALUES (?, ?, ?, ?, ?) RETURNING id", Long.class,
                                s.name(), s.startTime(), s.endTime(), s.hint(), sort);
                    }

                    @Override
                    public void update(long id, SlotInput s, int sort) {
                        jdbc.update("UPDATE schedule_slot SET name = ?, start_time = ?, end_time = ?, hint = ?, sort = ? "
                                + "WHERE id = ?", s.name(), s.startTime(), s.endTime(), s.hint(), sort, id);
                    }
                }));
        return slots();
    }

    public List<Countdown> countdowns() {
        LocalDate today = clock.at(clock.now()).toLocalDate();
        return jdbc.query("SELECT * FROM countdown ORDER BY sort, id", (rs, i) -> {
            String day = rs.getString("day");
            return new Countdown(rs.getLong("id"), rs.getString("name"), day, rs.getInt("enabled") == 1,
                    rs.getInt("sort"), ChronoUnit.DAYS.between(today, LocalDate.parse(day)));
        });
    }

    /** 首页用：启用且未过期，按日期先后。 */
    public List<Countdown> upcoming() {
        return countdowns().stream()
                .filter(c -> c.enabled() && c.daysLeft() >= 0)
                .sorted(Comparator.comparing(Countdown::day))
                .toList();
    }

    public List<Countdown> syncCountdowns(List<CountdownInput> input) {
        List<CountdownInput> clean = input.stream().map(ScheduleService::validate).toList();
        tx.executeWithoutResult(status -> ListSync.sync(jdbc, "countdown", clean, CountdownInput::id,
                new ListSync.Writer<>() {
                    @Override
                    public long insert(CountdownInput c, int sort) {
                        return jdbc.queryForObject("INSERT INTO countdown(name, day, enabled, sort) VALUES (?, ?, ?, ?) "
                                + "RETURNING id", Long.class, c.name(), c.day(), c.enabled() ? 1 : 0, sort);
                    }

                    @Override
                    public void update(long id, CountdownInput c, int sort) {
                        jdbc.update("UPDATE countdown SET name = ?, day = ?, enabled = ?, sort = ? WHERE id = ?",
                                c.name(), c.day(), c.enabled() ? 1 : 0, sort, id);
                    }
                }));
        return countdowns();
    }

    private static SlotInput validate(SlotInput s) {
        if (s == null) {
            throw ApiException.badRequest("作息时段不能为空");
        }
        String start = Checks.hhmm(s.startTime(), "开始时间");
        String end = Checks.hhmm(s.endTime(), "结束时间");
        if (start.compareTo(end) >= 0) {
            throw ApiException.badRequest("开始时间需早于结束时间");
        }
        return new SlotInput(s.id(), Checks.required(s.name(), "时段名", 1, 10), start, end,
                Checks.optional(s.hint(), "提示", 40));
    }

    private static CountdownInput validate(CountdownInput c) {
        if (c == null) {
            throw ApiException.badRequest("劫数不能为空");
        }
        return new CountdownInput(c.id(), Checks.required(c.name(), "劫数名", 1, 10),
                Checks.day(c.day()).toString(), Checks.bool(c.enabled(), true));
    }
}
