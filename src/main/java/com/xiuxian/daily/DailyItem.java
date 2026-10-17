package com.xiuxian.daily;

/** 今日功课的一项。 */
public record DailyItem(long id, String day, String name, Long pathId, String pathName, String color, String note,
                        boolean done, Long doneAt, Long routineId, int sort) {
}
