package com.xiuxian.stats;

import static com.xiuxian.MutableClock.millis;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.xiuxian.TestBase;

class StatsServiceTest extends TestBase {

    @Autowired
    StatsService stats;

    @Test
    void segmentAcrossDayBoundaryIsSplitAtFourAm() {
        clock.set(LocalDateTime.of(2026, 10, 14, 12, 0));
        long math = methodId("大衍算经");
        // 03:00–05:00：日界线 4 点前一小时算 10-13，之后一小时算 10-14
        doneSession(math, millis(LocalDateTime.of(2026, 10, 14, 3, 0)), millis(LocalDateTime.of(2026, 10, 14, 5, 0)));

        Map<String, Long> byDay = new java.util.HashMap<>();
        stats.trend(3).days().forEach(d -> byDay.put(d.day(), d.seconds()));

        assertThat(byDay).containsEntry("2026-10-13", 3600L).containsEntry("2026-10-14", 3600L)
                .containsEntry("2026-10-12", 0L);
    }

    @Test
    void weekComparesWithSamePeriodOfLastWeek() {
        // 周三 20:00 看本周：上周同期是上周一 04:00 到上周三 20:00
        clock.set(LocalDateTime.of(2026, 10, 14, 20, 0));
        long math = methodId("大衍算经");
        long cs = methodId("万象阵图");
        doneSession(math, millis(LocalDateTime.of(2026, 10, 12, 10, 0)), millis(LocalDateTime.of(2026, 10, 12, 11, 0)));
        doneSession(cs, millis(LocalDateTime.of(2026, 10, 5, 10, 0)), millis(LocalDateTime.of(2026, 10, 5, 12, 0)));
        // 上周四：不在同期内
        doneSession(cs, millis(LocalDateTime.of(2026, 10, 8, 10, 0)), millis(LocalDateTime.of(2026, 10, 8, 12, 0)));

        StatsService.Distribution d = stats.distribution("week");

        assertThat(d.from()).isEqualTo("2026-10-12");
        assertThat(d.to()).isEqualTo("2026-10-14");
        assertThat(d.totalSeconds()).isEqualTo(3600);
        assertThat(d.prevTotalSeconds()).isEqualTo(7200);
        StatsService.Distribution.Item mathItem = d.items().stream().filter(i -> i.name().equals("数学")).findFirst().orElseThrow();
        assertThat(mathItem.ratio()).isEqualTo(1.0);
        assertThat(d.items()).hasSize(7);
    }

    @Test
    void todayComparesWithYesterdayUpToTheSameTimeAndClips() {
        clock.set(LocalDateTime.of(2026, 10, 14, 20, 0));
        long math = methodId("大衍算经");
        doneSession(math, millis(LocalDateTime.of(2026, 10, 14, 9, 0)), millis(LocalDateTime.of(2026, 10, 14, 10, 0)));
        // 昨天 19:00–21:00，同期截到 20:00，只算一小时
        doneSession(math, millis(LocalDateTime.of(2026, 10, 13, 19, 0)), millis(LocalDateTime.of(2026, 10, 13, 21, 0)));

        StatsService.Distribution d = stats.distribution("today");

        assertThat(d.totalSeconds()).isEqualTo(3600);
        assertThat(d.prevTotalSeconds()).isEqualTo(3600);
    }

    @Test
    void lastMonthPeriodDoesNotRunIntoThisMonth() {
        // 3 月 31 日看本月：上月同期会超过 2 月末，要截到 3 月 1 日 04:00
        clock.set(LocalDateTime.of(2027, 3, 31, 12, 0));
        long math = methodId("大衍算经");
        doneSession(math, millis(LocalDateTime.of(2027, 2, 28, 10, 0)), millis(LocalDateTime.of(2027, 2, 28, 11, 0)));
        doneSession(math, millis(LocalDateTime.of(2027, 3, 2, 10, 0)), millis(LocalDateTime.of(2027, 3, 2, 12, 0)));

        StatsService.Distribution d = stats.distribution("month");

        assertThat(d.totalSeconds()).isEqualTo(7200);
        assertThat(d.prevTotalSeconds()).isEqualTo(3600);
    }
}
