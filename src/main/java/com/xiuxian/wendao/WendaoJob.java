package com.xiuxian.wendao;

/** 闻道阁任务。hasNote：笔记文件确实在磁盘上。 */
public record WendaoJob(long id, String jobKey, String bvid, int page, String url, String title, Long methodId,
                        String methodName, String videoType, String status, String message, String notePath,
                        boolean hasNote, long createdAt, long updatedAt) {
}
