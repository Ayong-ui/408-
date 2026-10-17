package com.xiuxian.method;

import com.xiuxian.common.Realms.RealmInfo;

/** 功法概要。lastUsedAt 是最近一次出关的时间，从未修炼为 null。 */
public record MethodBrief(long id, String name, String subject, String chapter, String description, long pathId,
                          String pathName, String color, boolean archived, int sort, long totalSeconds,
                          RealmInfo mastery, Long lastUsedAt) {
}
