package com.xiuxian.method;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.xiuxian.session.SessionRecord;

/** 功法详情：概要字段平铺，再加篇章分布、最近记录和近 30 个修炼日的时长。 */
public record MethodDetail(@JsonUnwrapped MethodBrief brief, List<ChapterStat> chapters, List<SessionRecord> recent,
                           List<DayStat> daily) {

    public record ChapterStat(String chapter, long seconds, int sessions) {
    }

    public record DayStat(String day, long seconds) {
    }
}
