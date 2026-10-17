package com.xiuxian.session;

/** 进行中的修炼。elapsedSeconds 截至 ActiveView.now，已扣除调息时间。 */
public record ActiveSession(long id, long methodId, String methodName, String subject, String chapter, long pathId,
                            String color, String status, long startedAt, Long targetSeconds, long elapsedSeconds,
                            Long segmentStartAt, long lastActiveAt) {
}
