package com.xiuxian.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** 第一次启动时开辟洞府：写入初始道脉、功法、日课、作息和传送阵。之后不再执行。 */
@Component
public class Seeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Seeder.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public Seeder(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer paths = jdbc.queryForObject("SELECT COUNT(*) FROM path", Integer.class);
        if (paths != null && paths > 0) {
            return;
        }
        tx.executeWithoutResult(status -> seed());
        log.info("洞府初开：已写入初始道脉、功法、日课、作息与传送阵");
    }

    private void seed() {
        long now = System.currentTimeMillis();

        // 道脉配色已用 dataviz 校验器在深色面板 #15181e 上校验过（明度带、彩度、色弱区分、对比度）
        long math = path("数学", "#4a86d4", true, false, 1);
        long cs408 = path("408", "#d2603a", true, false, 2);
        long english = path("英语", "#22a37f", true, false, 3);
        long csapp = path("CSAPP", "#b8860b", false, false, 4);
        long course = path("课程作业", "#cf5383", false, false, 5);
        long fitness = path("健身", "#3f9a2e", false, true, 6);
        path("其他", "#737882", false, false, 7);

        method(math, "大衍算经", "高等数学", "极限篇", "张宇考研资料、网课、例题和习题", 1, now);
        method(cs408, "万象阵图", "数据结构", "线性表", "王道考研资料、网课、例题和习题", 2, now);
        method(cs408, "紫府调度诀", "操作系统", null, "网课、考研资料和相关练习", 3, now);
        method(cs408, "炼器总纲", "计算机组成原理", null, "王道等考研资料、网课和相关练习", 4, now);
        method(cs408, "千里传音术", "计算机网络", null, "408 四门之一", 5, now);
        method(cs408, "符箓入门", "C 语言", null, "在线课程、编程练习和实践", 6, now);
        method(english, "谛听术", "CET-6 听力", null, "六级重点功法", 7, now);
        method(english, "异域言灵", "CET-6 单词·阅读·翻译·作文", null, "背单词、阅读、翻译、作文", 8, now);
        method(csapp, "天工开物", "CSAPP", "底层观想", "视频、实验和底层计算机知识探索", 9, now);
        method(fitness, "金刚锻体诀", "健身", null, "每天约 1 小时", 10, now);
        method(course, "宗门杂务", "课程作业", null, "学校课程与作业，按截止日期处理", 11, now);

        routine("数学", math, "1234567", null, 1);
        routine("408", cs408, "1234567", null, 2);
        routine("英语", english, "1234567", "听力为重", 3);
        routine("锻体", fitness, "1234567", "约一小时", 4);
        routine("宗门杂务", course, "12345", null, 5);

        slot("上午", "08:00", "12:00", "主修：数学 / 408（择一深入）", 1);
        slot("下午", "14:00", "17:30", "课程 / 作业 / 次修", 2);
        slot("傍晚", "17:30", "19:00", "锻体", 3);
        slot("晚上", "19:30", "23:00", "英语短修 / 当日余课 / CSAPP 支线", 4);

        portal("GitHub", "https://github.com/", 1);
        portal("Linux.do", "https://linux.do/latest", 2);
        portal("NodeSeek", "https://www.nodeseek.com/", 3);
        portal("天涯社区", "https://www.tianya.net/index.html", 4);
        portal("V2EX", "https://www.v2ex.com/", 5);
        portal("Linux.sb", "https://linux.sb/", 6);
    }

    private long path(String name, String color, boolean core, boolean body, int sort) {
        return jdbc.queryForObject(
                "INSERT INTO path(name, color, core, body, sort) VALUES (?, ?, ?, ?, ?) RETURNING id",
                Long.class, name, color, core ? 1 : 0, body ? 1 : 0, sort);
    }

    private void method(long pathId, String name, String subject, String chapter, String description,
                        int sort, long now) {
        jdbc.update("INSERT INTO method(path_id, name, subject, chapter, description, sort, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)", pathId, name, subject, chapter, description, sort, now);
    }

    private void routine(String name, long pathId, String weekdays, String note, int sort) {
        jdbc.update("INSERT INTO routine(name, path_id, weekdays, note, sort) VALUES (?, ?, ?, ?, ?)",
                name, pathId, weekdays, note, sort);
    }

    private void slot(String name, String start, String end, String hint, int sort) {
        jdbc.update("INSERT INTO schedule_slot(name, start_time, end_time, hint, sort) VALUES (?, ?, ?, ?, ?)",
                name, start, end, hint, sort);
    }

    private void portal(String name, String url, int sort) {
        jdbc.update("INSERT INTO portal(name, url, sort) VALUES (?, ?, ?)", name, url, sort);
    }
}
