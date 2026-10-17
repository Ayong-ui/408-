package com.xiuxian.session;

import com.xiuxian.common.Realms.RealmInfo;

/** 出关结算。discarded = true 时（不足一分钟的误触）其余字段都为 null。 */
public record Settlement(boolean discarded, SessionRecord session, MethodPart method, RealmPart realm,
                         Long todaySeconds, SuggestItem suggestItem) {

    public record MethodPart(long id, String name, String chapter, RealmInfo before, RealmInfo after) {
    }

    public record RealmPart(String kind, RealmInfo before, RealmInfo after, boolean breakthrough, boolean major) {
    }

    public record SuggestItem(long id, String name) {
    }

    static Settlement discardedOne() {
        return new Settlement(true, null, null, null, null, null);
    }
}
