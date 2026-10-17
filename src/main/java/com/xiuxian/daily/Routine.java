package com.xiuxian.daily;

/** 日课模板。weekdays 由 1–7 组成，1 = 周一。 */
public record Routine(long id, String name, Long pathId, String weekdays, String note, int sort, boolean enabled) {
}
