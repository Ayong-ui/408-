package com.xiuxian;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** 测试用的可拨动时钟。 */
public class MutableClock extends Clock {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private volatile Instant instant;

    public MutableClock(Instant instant) {
        this.instant = instant;
    }

    public void set(LocalDateTime local) {
        this.instant = local.atZone(ZONE).toInstant();
    }

    public void advance(Duration d) {
        this.instant = instant.plus(d);
    }

    public static long millis(LocalDateTime local) {
        return local.atZone(ZONE).toInstant().toEpochMilli();
    }

    @Override
    public ZoneId getZone() {
        return ZONE;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
