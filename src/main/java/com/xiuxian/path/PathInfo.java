package com.xiuxian.path;

/** 道脉：统计分类。core = 三脉之一，body = 体修。 */
public record PathInfo(long id, String name, String color, boolean core, boolean body, int sort) {
}
