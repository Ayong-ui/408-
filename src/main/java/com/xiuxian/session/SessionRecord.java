package com.xiuxian.session;

/** 一条已出关的修炼记录。day 是 startedAt 所在的修炼日。 */
public record SessionRecord(long id, long methodId, String methodName, String subject, String chapter, long pathId,
                            String pathName, String color, String source, long startedAt, Long endedAt, long seconds,
                            String day, String note) {
}
