-- 修仙台表结构。每次启动都会执行，所以每条语句都必须可重复执行（IF NOT EXISTS）。
-- 时间点一律存 epoch 毫秒；"修炼日"存 yyyy-MM-dd（按日界线划分，默认凌晨 4 点换日）。

CREATE TABLE IF NOT EXISTS setting (
    name  TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

-- 道脉：统计分类（数学 / 408 / 英语 …）
CREATE TABLE IF NOT EXISTS path (
    id    INTEGER PRIMARY KEY AUTOINCREMENT,
    name  TEXT    NOT NULL,
    color TEXT    NOT NULL,
    core  INTEGER NOT NULL DEFAULT 0,  -- 1 = 三脉之一（每天尽量接触）
    body  INTEGER NOT NULL DEFAULT 0,  -- 1 = 体修：时长计入体修境界，不计入修为
    sort  INTEGER NOT NULL DEFAULT 0
);

-- 功法（按科目建，篇章只是一个标签）
CREATE TABLE IF NOT EXISTS method (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    path_id     INTEGER NOT NULL REFERENCES path(id),
    name        TEXT    NOT NULL,      -- 功法名，如 大衍算经
    subject     TEXT,                  -- 白话科目名，如 高等数学
    chapter     TEXT,                  -- 当前篇章，如 极限篇
    description TEXT,
    archived    INTEGER NOT NULL DEFAULT 0,
    sort        INTEGER NOT NULL DEFAULT 0,
    created_at  INTEGER NOT NULL
);

-- 修炼：一次入定，或一条补记
CREATE TABLE IF NOT EXISTS session (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    method_id      INTEGER NOT NULL REFERENCES method(id) ON DELETE CASCADE,
    chapter        TEXT,                 -- 当时的篇章快照
    source         TEXT    NOT NULL,     -- TIMER | MANUAL
    status         TEXT    NOT NULL,     -- RUNNING | PAUSED | DONE
    target_seconds INTEGER,
    started_at     INTEGER NOT NULL,
    ended_at       INTEGER,
    last_active_at INTEGER NOT NULL,     -- 最近一次入定/续修/"还在"的时间，用于忘关提醒
    note           TEXT
);
CREATE INDEX IF NOT EXISTS ix_session_method ON session(method_id);
CREATE INDEX IF NOT EXISTS ix_session_status ON session(status);

-- 修炼分段：每次入定/续修开一段，调息/出关收一段。暂停时长和跨日拆分都靠它。
CREATE TABLE IF NOT EXISTS segment (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER NOT NULL REFERENCES session(id) ON DELETE CASCADE,
    start_at   INTEGER NOT NULL,
    end_at     INTEGER                   -- NULL = 正在进行
);
CREATE INDEX IF NOT EXISTS ix_segment_session ON segment(session_id);
CREATE INDEX IF NOT EXISTS ix_segment_start ON segment(start_at);

-- 日课模板：每个修炼日第一次被访问时，据此生成当天的今日功课
CREATE TABLE IF NOT EXISTS routine (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    name     TEXT    NOT NULL,
    path_id  INTEGER REFERENCES path(id) ON DELETE SET NULL,
    weekdays TEXT    NOT NULL DEFAULT '1234567',  -- 1=周一 … 7=周日
    note     TEXT,
    sort     INTEGER NOT NULL DEFAULT 0,
    enabled  INTEGER NOT NULL DEFAULT 1
);

-- 记录哪些修炼日已经生成过功课（用户删掉的功课不会被重新生成）
CREATE TABLE IF NOT EXISTS day_meta (
    day       TEXT PRIMARY KEY,
    generated INTEGER NOT NULL DEFAULT 1
);

-- 今日功课
CREATE TABLE IF NOT EXISTS daily_item (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    day        TEXT    NOT NULL,
    routine_id INTEGER,
    name       TEXT    NOT NULL,
    path_id    INTEGER REFERENCES path(id) ON DELETE SET NULL,
    note       TEXT,
    done       INTEGER NOT NULL DEFAULT 0,
    done_at    INTEGER,
    sort       INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS ix_daily_item_day ON daily_item(day);

-- 修行日志：每个修炼日一篇
CREATE TABLE IF NOT EXISTS daily_log (
    day        TEXT PRIMARY KEY,
    content    TEXT    NOT NULL,
    updated_at INTEGER NOT NULL
);

-- 作息框架
CREATE TABLE IF NOT EXISTS schedule_slot (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT    NOT NULL,
    start_time TEXT    NOT NULL,   -- HH:mm
    end_time   TEXT    NOT NULL,   -- HH:mm
    hint       TEXT,
    sort       INTEGER NOT NULL DEFAULT 0
);

-- 观星台：传送阵（常逛的网站）
CREATE TABLE IF NOT EXISTS portal (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    name    TEXT    NOT NULL,
    url     TEXT    NOT NULL,
    note    TEXT,
    enabled INTEGER NOT NULL DEFAULT 1,
    sort    INTEGER NOT NULL DEFAULT 0
);

-- 观星台：情报簿
CREATE TABLE IF NOT EXISTS intel (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    title      TEXT    NOT NULL,
    url        TEXT,
    note       TEXT,
    status     TEXT    NOT NULL DEFAULT '想研究',
    created_at INTEGER NOT NULL
);

-- 劫数：考试倒计时
CREATE TABLE IF NOT EXISTS countdown (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    name    TEXT    NOT NULL,
    day     TEXT    NOT NULL,   -- yyyy-MM-dd
    enabled INTEGER NOT NULL DEFAULT 1,
    sort    INTEGER NOT NULL DEFAULT 0
);

-- 闻道阁：B 站字幕炼化任务
CREATE TABLE IF NOT EXISTS wendao_job (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    job_key       TEXT    NOT NULL UNIQUE,   -- BVxxxx_p1
    bvid          TEXT    NOT NULL,
    page          INTEGER NOT NULL DEFAULT 1,
    url           TEXT    NOT NULL,
    title         TEXT,
    method_id     INTEGER REFERENCES method(id) ON DELETE SET NULL,
    video_type    TEXT,                       -- theory | demo | unknown
    status        TEXT    NOT NULL,           -- WAITING | SUBTITLE_READY | REFINING | DONE | FAILED
    message       TEXT,
    subtitle_path TEXT,
    note_path     TEXT,
    created_at    INTEGER NOT NULL,
    updated_at    INTEGER NOT NULL
);
