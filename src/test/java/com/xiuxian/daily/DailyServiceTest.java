package com.xiuxian.daily;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.xiuxian.TestBase;

class DailyServiceTest extends TestBase {

    @Autowired
    DailyService daily;

    private List<DailyService.RoutineInput> original;

    @AfterEach
    void restoreRoutines() {
        if (original != null) {
            daily.syncRoutines(original);
        }
    }

    @Test
    void generatesFromRoutinesByWeekdayAndOnlyOnce() {
        // 2026-10-14 是周三：宗门杂务（周一至周五）也在
        clock.set(LocalDateTime.of(2026, 10, 14, 9, 0));
        DailyService.TodayView today = daily.today();
        assertThat(today.items()).extracting(DailyItem::name)
                .containsExactly("数学", "408", "英语", "锻体", "宗门杂务");

        long deleted = today.items().get(1).id();
        daily.delete(deleted);
        daily.ensureGenerated(LocalDate.of(2026, 10, 14));

        assertThat(daily.today().items()).extracting(DailyItem::name)
                .containsExactly("数学", "英语", "锻体", "宗门杂务");
    }

    @Test
    void weekendSkipsWeekdayOnlyRoutines() {
        clock.set(LocalDateTime.of(2026, 10, 17, 9, 0)); // 周六
        assertThat(daily.today().items()).extracting(DailyItem::name).doesNotContain("宗门杂务");
    }

    @Test
    void beforeDayStartBelongsToYesterday() {
        clock.set(LocalDateTime.of(2026, 10, 15, 1, 30)); // 凌晨 1:30 还算 10-14
        assertThat(daily.today().day()).isEqualTo("2026-10-14");
    }

    @Test
    void markingDoneRecordsTime() {
        clock.set(LocalDateTime.of(2026, 10, 14, 9, 0));
        long id = daily.today().items().get(0).id();
        DailyItem done = daily.update(id, Map.of("done", true));
        assertThat(done.done()).isTrue();
        assertThat(done.doneAt()).isEqualTo(clock.millis());
        DailyItem undone = daily.update(id, Map.of("done", false));
        assertThat(undone.doneAt()).isNull();
    }

    @Test
    void syncRoutinesUpdatesInsertsDeletesAndAppendsToToday() {
        clock.set(LocalDateTime.of(2026, 10, 14, 9, 0)); // 周三
        original = daily.routines().stream()
                .map(r -> new DailyService.RoutineInput(null, r.name(), r.pathId(), r.weekdays(), r.note(), r.enabled()))
                .toList();
        daily.today();
        List<Routine> before = daily.routines();

        List<DailyService.RoutineInput> input = new ArrayList<>();
        Routine math = before.get(0);
        input.add(new DailyService.RoutineInput(math.id(), "数学", math.pathId(), "7531", "主修", true));
        input.add(new DailyService.RoutineInput(null, "CSAPP 支线", null, "3", null, null));
        input.add(new DailyService.RoutineInput(null, "周末加练", null, "67", null, true));

        List<Routine> after = daily.syncRoutines(input);

        assertThat(after).extracting(Routine::name).containsExactly("数学", "CSAPP 支线", "周末加练");
        assertThat(after.get(0).id()).isEqualTo(math.id());
        assertThat(after.get(0).weekdays()).isEqualTo("1357");
        assertThat(after).extracting(Routine::sort).containsExactly(1, 2, 3);
        // 周三：新增的 CSAPP 支线追加到今天，周末加练不追加；已有功课不受影响
        assertThat(daily.today().items()).extracting(DailyItem::name)
                .containsExactly("数学", "408", "英语", "锻体", "宗门杂务", "CSAPP 支线");
    }
}
