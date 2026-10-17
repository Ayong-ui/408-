package com.xiuxian.session;

/** 计时接口的返回：服务器此刻的时间 + 进行中的修炼（没有则为 null）。前端用 now 校准本地时钟。 */
public record ActiveView(long now, ActiveSession active) {
}
