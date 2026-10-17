# 修仙台接口约定

前后端以此为准。改动接口时先改这份文档。

## 通用约定

- 前缀 `/api`，请求和响应都是 JSON，字段 camelCase。
- **写操作**（POST / PUT / DELETE）必须带请求头 `X-Xiuxian: 1`，否则 403（防止恶意网页借浏览器调用接口）。
- 时间点：epoch 毫秒（number）。时长：秒（number）。
- **修炼日** `day`：字符串 `yyyy-MM-dd`。日界线由设置 `dayStartHour`（默认 4）决定：凌晨 4 点前的时间算前一天。见 `DayClock`。
- 一周从周一开始。
- 错误：4xx/5xx，响应体 `{ "error": "给人看的中文信息" }`。
- 布尔字段在 JSON 里是 `true/false`（库里是 0/1）。
- 只统计 `status = DONE` 的修炼；进行中的修炼只在 `active` 里出现。
- 一次修炼的时长 = 它所有分段（segment）的时长之和；按天统计时，把分段裁剪到当天的 `[startOf(day), endOf(day))` 区间内再求和（跨日的修炼会被拆到两天）。

## 公共结构

### RealmInfo（境界）

```json
{
  "kind": "CULTIVATION",        // CULTIVATION 修为 | BODY 体修 | MASTERY 功法熟练度
  "totalSeconds": 162000,
  "index": 7,                   // 阶梯序号，越大越高
  "major": "练气",              // 大境界；变化即"渡劫"
  "name": "练气七层",
  "floorSeconds": 162000,       // 当前境界门槛
  "nextName": "练气八层",       // 已到顶为 null
  "nextSeconds": 216000,        // 已到顶为 null
  "progress": 0.0               // 当前境界内进度 0..1
}
```

由 `Realms.of(kind, totalSeconds)` 计算：
- **修为**：所有 `body = false` 道脉下 DONE 修炼的总时长。
- **体修**：所有 `body = true` 道脉下 DONE 修炼的总时长。
- **熟练度**：单个功法 DONE 修炼的总时长。

### Path（道脉）

```json
{ "id": 1, "name": "数学", "color": "#4a86d4", "core": true, "body": false, "sort": 1 }
```

### MethodBrief（功法）

```json
{
  "id": 1, "name": "大衍算经", "subject": "高等数学", "chapter": "极限篇",
  "description": "张宇考研资料…", "pathId": 1, "pathName": "数学", "color": "#4a86d4",
  "archived": false, "sort": 1,
  "totalSeconds": 29820,
  "mastery": RealmInfo,          // kind = MASTERY
  "lastUsedAt": 1792051200000    // 最近一次 DONE 修炼的结束时间，从未修炼为 null
}
```

### ActiveSession（进行中的修炼）

```json
{
  "id": 12, "methodId": 1, "methodName": "大衍算经", "subject": "高等数学", "chapter": "极限篇",
  "pathId": 1, "color": "#4a86d4",
  "status": "RUNNING",            // RUNNING | PAUSED
  "startedAt": 1792050000000,
  "targetSeconds": null,
  "elapsedSeconds": 3133,         // 截至响应里 now 的有效时长（已扣除调息时间）
  "segmentStartAt": 1792051000000,// 当前这一段的开始时间；PAUSED 时为 null
  "lastActiveAt": 1792051000000   // 最近一次 入定/续修/touch 的时间
}
```

前端实时显示：`elapsed = elapsedSeconds + (RUNNING ? (Date.now() + clockOffset - now) / 1000 : 0)`，其中 `clockOffset = now - 收到响应时的 Date.now()`。

### SessionRecord（修炼记录）

```json
{
  "id": 12, "methodId": 1, "methodName": "大衍算经", "subject": "高等数学", "chapter": "极限篇",
  "pathId": 1, "pathName": "数学", "color": "#4a86d4",
  "source": "TIMER",              // TIMER 入定 | MANUAL 补记
  "startedAt": 1792050000000, "endedAt": 1792053133000,
  "seconds": 3133,
  "day": "2026-10-15",            // startedAt 所在修炼日
  "note": null
}
```

### Settlement（出关结算）

`finish` 和 `manual` 返回：

```json
{
  "discarded": false,             // 不足 60 秒的入定视为误触，直接删除，此时为 true，其余字段可为 null
  "session": SessionRecord,
  "method": {
    "id": 1, "name": "大衍算经", "chapter": "极限篇",
    "before": RealmInfo, "after": RealmInfo      // MASTERY，before 为本次之前
  },
  "realm": {
    "kind": "CULTIVATION",        // 该功法属 body 道脉时为 BODY
    "before": RealmInfo, "after": RealmInfo,
    "breakthrough": true,         // after.index > before.index
    "major": false                // CULTIVATION 下 after.major != before.major（含 凡人→练气）
  },
  "todaySeconds": 11100,          // 今天（修炼日）所有 DONE 修炼的总时长
  "suggestItem": { "id": 3, "name": "数学" }   // 今天同道脉下第一个未完成的功课，没有则 null
}
```

### DailyItem（今日功课）

```json
{ "id": 1, "day": "2026-10-15", "name": "数学", "pathId": 1, "pathName": "数学", "color": "#4a86d4",
  "note": null, "done": true, "doneAt": 1792051200000, "routineId": 1, "sort": 1 }
```

## 洞府（首页）

### `GET /api/home`

一次拿齐首页数据（手机网络慢，少跑几趟）。**副作用**：今天的功课还没生成过时，按日课模板生成（见"今日功课"）。

```json
{
  "now": 1792051200000,
  "day": "2026-10-15",
  "weekday": 4,                         // 1=周一 … 7=周日
  "settings": { ...同 GET /api/settings },
  "cultivation": RealmInfo,             // 修为
  "body": RealmInfo,                    // 体修
  "todaySeconds": 11100,                // 今天 DONE 修炼总时长（不含进行中）
  "monthVisitDays": 18,                 // 本月有任何动静的修炼日数：有修炼、有完成的功课或写了日志
  "items": [DailyItem],
  "meridians": [                        // core 道脉，按 sort
    { "pathId": 1, "name": "数学", "color": "#4a86d4", "lit": true }
  ],                                    // lit：今天该道脉有修炼（含进行中）或有完成的功课
  "active": ActiveSession | null,
  "slot": { "id": 2, "name": "下午", "startTime": "14:00", "endTime": "17:30", "hint": "课程 / 作业 / 次修" } | null,
  "countdowns": [ { "id": 1, "name": "六级", "day": "2026-12-12", "daysLeft": 58 } ],  // enabled 且未过期，按日期
  "log": { "day": "2026-10-15", "content": "…", "updatedAt": 1792051200000 } | null,
  "methods": [MethodBrief],             // 未封存功法，lastUsedAt 降序（null 在后），再按 sort
  "lastMethodId": 1 | null              // 最近一次修炼（含进行中）的功法
}
```

`slot`：当前本地时刻（不是修炼日）落在哪个作息时段，`startTime <= HH:mm < endTime`。

## 设置

- `GET /api/settings` → `{ daoName, dayStartHour, incenseMinutes, effectLevel, copyEnabled, plainMode, soundEnabled, idleAskMinutes }`
- `PUT /api/settings`，body 为部分键值 → 完整设置。非法值 400。

## 道脉

- `GET /api/paths` → `[Path]`，按 sort, id。
- `POST /api/paths` `{ name, color, core, body }` → Path（sort 放到最后）。
- `PUT /api/paths/{id}` `{ name, color, core, body, sort }` → Path。
- `DELETE /api/paths/{id}` → 204；有功法引用时 409 `"此道脉下还有功法"`。

校验：name 1–10 字；color 为 `#rrggbb`。

## 功法（藏经阁）

- `GET /api/methods?archived=false|true|all`（默认 false）→ `[MethodBrief]`，按 sort, id。
- `GET /api/methods/{id}` → MethodDetail = MethodBrief 加：
  ```json
  {
    "chapters": [ { "chapter": "极限篇", "seconds": 18000, "sessions": 6 } ],   // 按篇章快照分组，null 篇章记为 "未分篇"；按 seconds 降序
    "recent": [SessionRecord],                                                  // 最近 20 条 DONE
    "daily": [ { "day": "2026-09-16", "seconds": 0 } ]                          // 最近 30 个修炼日，旧→新，含 0
  }
  ```
- `POST /api/methods` `{ name, subject, chapter, pathId, description }` → MethodBrief。
- `PUT /api/methods/{id}` `{ name, subject, chapter, pathId, description, sort }` → MethodBrief（只改篇章时也走这里，其余字段原样传回）。
- `POST /api/methods/{id}/archive`、`POST /api/methods/{id}/unarchive` → MethodBrief。正在修炼的功法不能封存（409）。
- `DELETE /api/methods/{id}` → 204。**散功**：连同全部修炼记录一起删除。正在修炼的功法不能删（409）。

校验：name 1–20 字；subject、chapter ≤ 30 字；description ≤ 200 字；pathId 必须存在。

## 修炼（静室）

同一时刻最多一个进行中（RUNNING / PAUSED）的修炼。

- `GET /api/sessions/active` → `{ "now": …, "active": ActiveSession | null }`
- `POST /api/sessions/start` `{ methodId, targetMinutes? }` → `{ now, active }`
  - 已有进行中的修炼 → 409 `"已在入定中"`；功法已封存 → 409；功法不存在 → 404。
  - 新建 session（source=TIMER，status=RUNNING，chapter=功法当前篇章快照，last_active_at=now）和一个开放分段。
- `POST /api/sessions/{id}/pause` → `{ now, active }`。关闭开放分段，status=PAUSED，last_active_at=now。非 RUNNING → 409。
- `POST /api/sessions/{id}/resume` → `{ now, active }`。新开分段，status=RUNNING，last_active_at=now。非 PAUSED → 409。
- `POST /api/sessions/{id}/touch` → `{ now, active }`。只更新 last_active_at（回应"道友还在否"）。
- `POST /api/sessions/{id}/finish` `{ endAt? }` → Settlement。
  - `endAt` 可选，用于"忘关了，按某时出关"：必须 `startedAt < endAt <= now`，否则 400。给了 endAt 时，开始时间 ≥ endAt 的分段删除，结束时间晚于 endAt（或仍开放）的分段截到 endAt。
  - 不给 endAt：关闭开放分段于 now。
  - status=DONE，ended_at=实际结束时间。有效时长 < 60 秒 → 删除整条 session，返回 `{ "discarded": true }`。
- `POST /api/sessions/manual` `{ methodId, startAt, minutes, chapter?, note? }` → Settlement。**补记**：source=MANUAL，status=DONE，一个分段 `[startAt, startAt + minutes*60000)`。校验：minutes 1–720；结束时间不晚于 now + 1 分钟；chapter 缺省用功法当前篇章。
- `GET /api/sessions?from=yyyy-MM-dd&to=yyyy-MM-dd&methodId=&limit=` → `[SessionRecord]`，只含 DONE，按 startedAt 降序。from/to 是修炼日（含两端），默认最近 30 天；limit 默认 200，最大 1000。
- `DELETE /api/sessions/{id}` → 204。删除一条记录（含进行中的，即"此次不算"）。

## 今日功课与日课模板

**生成规则**：某修炼日第一次被访问（`GET /api/home` 或 `GET /api/today`）且 `day_meta` 里没有该日时，为每个 enabled 且 `weekdays` 包含该日星期的 routine 生成一条 daily_item（name、path_id、note、sort 抄自 routine），然后写入 day_meta。用户删掉的功课不会再生成。

- `GET /api/today` → `{ "day": "2026-10-15", "items": [DailyItem] }`，按 sort, id。
- `POST /api/daily-items` `{ name, pathId?, note? }` → DailyItem。加到今天，sort 放最后。
- `PUT /api/daily-items/{id}` `{ name?, pathId?, note?, done? }` → DailyItem。只改传了的字段；done 变 true 时记 done_at=now，变 false 时清空。
- `DELETE /api/daily-items/{id}` → 204。
- `GET /api/routines` → `[Routine]`，Routine = `{ id, name, pathId, weekdays: "12345", note, sort, enabled }`。
- `PUT /api/routines`，body 为完整数组 → `[Routine]`。**整表同步**：带 id 的更新，不带 id 的新增，库里有而数组里没有的删除；sort 按数组顺序重排为 1..n。新增的模板如果今天已生成过功课、且包含今天的星期，同时追加到今天。

校验：name 1–20 字；weekdays 只能由 1–7 组成且不为空；note ≤ 40 字。

## 修行日志

- `GET /api/logs?limit=30` → `[ { day, content, updatedAt } ]`，按 day 降序。
- `GET /api/logs/{day}` → `{ day, content, updatedAt }`；没有时 content 为 `""`、updatedAt 为 null。
- `PUT /api/logs/{day}` `{ content }` → 同上。content 去掉首尾空白后为空则删除该日日志。≤ 5000 字。
- `POST /api/logs/append` `{ text }` → 今天的日志。**传音玉简**：在今天日志末尾追加一行 `[HH:mm] text`（已有内容时先换行；text 里的换行折成空格，保证一条传音一行）。text 1–500 字。

## 统计

- `GET /api/stats/distribution?range=today|week|month` →
  ```json
  {
    "range": "week", "from": "2026-10-12", "to": "2026-10-15",
    "totalSeconds": 84600, "prevTotalSeconds": 72000,
    "items": [ { "pathId": 1, "name": "数学", "color": "#4a86d4", "seconds": 30000, "ratio": 0.3546, "prevSeconds": 25000 } ]
  }
  ```
  - 范围：today = 今天；week = 本周一至今天；month = 本月 1 日至今天（修炼日）。区间终点是 **now**，不是今天结束。
  - prev 为**上一周期的同期**：today → 昨天从日界线开始、同样长的一段；week → 上周一开始、同样长的一段；month → 上月 1 日开始、同样长的一段（不超过上月末）。
  - items 包含全部道脉（含 0），按道脉 sort；ratio = seconds / totalSeconds（total 为 0 时为 0）。
- `GET /api/stats/trend?days=7|30`（接受 1–90，默认 7）→ `{ "days": [ { "day": "2026-10-09", "seconds": 18000, "byPath": { "1": 7200, "2": 3600 } } ] }`，旧→新，最后一个是今天，没有修炼的天 seconds=0、byPath 为 `{}`。

## 作息

- `GET /api/slots` → `[Slot]`，Slot = `{ id, name, startTime: "HH:mm", endTime: "HH:mm", hint, sort }`，按 sort。
- `PUT /api/slots`，完整数组 → `[Slot]`。整表同步，规则同 routines。校验：name 1–10 字；时间为 HH:mm 且 start < end；hint ≤ 40 字。

## 观星台

- `GET /api/portals` → `[Portal]`，Portal = `{ id, name, url, note, enabled, sort }`，按 sort。
- `PUT /api/portals`，完整数组 → `[Portal]`。整表同步。校验：name 1–20 字；url 必须以 `http://` 或 `https://` 开头。
- `GET /api/intel?status=` → `[Intel]`，Intel = `{ id, title, url, note, status, createdAt }`，按 createdAt 降序。
- `POST /api/intel` `{ title, url?, note?, status? }` → Intel。status 默认 `想研究`。
- `PUT /api/intel/{id}` `{ title, url, note, status }` → Intel。
- `DELETE /api/intel/{id}` → 204。

校验：title 1–100 字；url 为空或以 http(s):// 开头；status 只能是 `想研究` / `已试用` / `纯粹觉得很酷`。

## 劫数（考试倒计时）

- `GET /api/countdowns` → `[ { id, name, day, enabled, sort, daysLeft } ]`，按 sort。daysLeft = day − 今天（按自然日，可为负）。
- `PUT /api/countdowns`，完整数组 → 同上。整表同步。校验：name 1–10 字；day 为合法日期。

## 闻道阁

任务状态：`WAITING` 等待字幕 → `SUBTITLE_READY` 字幕已获取 → `REFINING` 正在炼化玉简 → `DONE` 笔记已完成 / `FAILED` 炼化失败。

Job：

```json
{
  "id": 3, "jobKey": "BV1xx411c7mD_p1", "bvid": "BV1xx411c7mD", "page": 1,
  "url": "https://www.bilibili.com/video/BV1xx411c7mD?p=1",
  "title": "…", "methodId": 1, "methodName": "大衍算经",
  "videoType": "theory", "status": "DONE", "message": null,
  "notePath": "data/notes/….md", "hasNote": true,
  "createdAt": …, "updatedAt": …
}
```

- `GET /api/wendao/status` → `{ "python": true, "script": true, "prompt": true, "cookies": false, "claude": true }`：环境自检，前端据此提示缺什么。
- `GET /api/wendao/jobs` → `[Job]`，按 createdAt 降序。
- `POST /api/wendao/jobs` `{ url, methodId? }` → Job。
  - url 可以是 B 站视频链接、`b23.tv` 短链或直接一个 BV 号；只从中提取 BV 号和 `p` 参数（默认 1），再自己拼规范链接，**绝不把用户输入原样传给命令行**。
  - jobKey = `{BV}_p{p}`。已存在 → 409 `"此玉简已在闻道阁中"`（要重新炼化请用 retry）。
  - 新任务 WAITING，进入单线程队列。
- `POST /api/wendao/jobs/{id}/retry` → Job。重新排队，覆盖旧笔记。排队或处理中（WAITING / SUBTITLE_READY / REFINING）时 409，避免重复入队。
- `GET /api/wendao/jobs/{id}/note` → `{ "markdown": "…", "path": "data/notes/….md" }`；没有笔记 404。
- `DELETE /api/wendao/jobs/{id}` → 204。只删任务记录，**不删本地字幕和笔记文件**。排队或处理中 409。

后端与 `wendao/refine.py` 之间的约定见 [WENDAO.md](WENDAO.md)。
