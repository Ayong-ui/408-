package com.xiuxian.common;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.xiuxian.config.XiuxianProperties;

/**
 * 修炼日的划分。学到凌晨 1 点算前一天的修炼，所以一天从"日界线"（默认凌晨 4 点）开始，而不是 0 点。
 * 所有按天统计、今日功课、日志都用这里的规则。
 */
@Component
public class DayClock {

    private final SettingsService settings;
    private final ZoneId zone;
    private final Clock clock;

    /** 没有注册 Clock bean 时用系统时钟；测试里可以注册一个可拨动的时钟。 */
    public DayClock(SettingsService settings, XiuxianProperties props, ObjectProvider<Clock> clock) {
        this.settings = settings;
        this.zone = ZoneId.of(props.zone());
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    public ZoneId zone() {
        return zone;
    }

    public long now() {
        return clock.millis();
    }

    /** 某个时间点的本地时间。 */
    public ZonedDateTime at(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(zone);
    }

    public int dayStartHour() {
        return settings.getInt("dayStartHour");
    }

    /** 某个时间点属于哪个修炼日。 */
    public LocalDate dayOf(long epochMillis) {
        return at(epochMillis).minusHours(dayStartHour()).toLocalDate();
    }

    public LocalDate today() {
        return dayOf(now());
    }

    /** 修炼日开始的时间点（含）。 */
    public long startOf(LocalDate day) {
        return day.atTime(dayStartHour(), 0).atZone(zone).toInstant().toEpochMilli();
    }

    /** 修炼日结束的时间点（不含），即下一天的开始。 */
    public long endOf(LocalDate day) {
        return startOf(day.plusDays(1));
    }

    /** 所在周的周一。 */
    public LocalDate weekStart(LocalDate day) {
        return day.with(DayOfWeek.MONDAY);
    }
}
