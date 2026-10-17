# 闻道阁：后端与炼化脚本的约定

闻道阁把一个 B 站视频的字幕"炼化"成本地 Markdown 学习笔记：

```text
视频链接 → 后端提取 BV 号/分P，建任务 → 单线程队列
  → 后端写任务文件，调用 wendao/refine.py
  → 脚本：取元信息 → 拉字幕（UP 主字幕优先，其次 AI 字幕，排除弹幕）→ 保存原始字幕和带时间戳字幕
  → 脚本：把提示词 + 字幕经 stdin 交给 claude -p（禁用全部工具）→ 把输出写成 Markdown
  → 后端按脚本输出的事件更新任务状态
```

分工：后端（Java，`com.xiuxian.wendao`）只管任务、队列、状态和超时；取字幕、调 Claude、写笔记都在 `wendao/refine.py` 里。脚本可以单独拿任务文件运行和调试，改提示词不用重新编译。

## 调用方式

后端在**项目根目录**（应用工作目录）下执行：

```text
<xiuxian.wendao.python> -X utf8 <xiuxian.wendao.script> <任务文件路径>
```

- 用参数列表启动进程，不经过 shell。
- 环境变量：`PYTHONUTF8=1`、`PYTHONIOENCODING=utf-8`。stdout/stderr 一律按 UTF-8 读。
- 超时：`timeout_seconds + 60` 秒后仍未结束，后端杀掉整个进程树，任务记为 FAILED「炼化超时」。

## 任务文件

后端写到 `<xiuxian.wendao.jobs-dir>/<job_id>.json`（UTF-8），所有路径都是绝对路径：

```json
{
  "job_id": "BV1xx411c7mD_p1",
  "bvid": "BV1xx411c7mD",
  "page": 1,
  "url": "https://www.bilibili.com/video/BV1xx411c7mD?p=1",
  "method": { "name": "大衍算经", "subject": "高等数学", "chapter": "极限篇" },
  "subtitles_dir": "D:/…/data/subtitles",
  "notes_dir": "D:/…/data/notes",
  "prompt_file": "D:/…/prompts/bilibili_note.md",
  "cookies_file": "D:/…/config/bilibili-cookies.txt",
  "claude_command": "claude",
  "claude_args": ["-p", "--output-format", "text", "--tools", "", "--no-session-persistence", "--strict-mcp-config"],
  "timeout_seconds": 1800,
  "previous_note_path": null
}
```

- `method`：任务没关联功法时为 null。
- `cookies_file`：文件不存在时为 null。
- `previous_note_path`：重新炼化时为旧笔记的绝对路径，脚本应覆盖它而不是新建文件；否则为 null。

## 脚本输出（stdout，JSON Lines）

每行一个 JSON 对象，后端逐行解析，非 JSON 行忽略：

| 事件 | 字段 | 后端动作 |
| --- | --- | --- |
| `{"event":"status","status":"SUBTITLE_READY", "title":…, "video_type":…, "subtitle_path":…}` | title、video_type（theory / demo / unknown）、subtitle_path 都可选 | 更新状态和这些字段 |
| `{"event":"status","status":"REFINING"}` | | 更新状态 |
| `{"event":"progress","message":"分段 2/4"}` | message | 写入任务的 message，前端显示进度 |
| `{"event":"done","note_path":…}` | note_path | 状态 DONE，记录笔记路径 |
| `{"event":"failed","message":…, "title":…}` | message：给人看的中文原因；title 可选（已取到视频信息时带上） | 状态 FAILED，记录原因和标题 |

- 退出码 0 且输出过 `done` → 成功；其余情况一律 FAILED（没有 `failed` 事件时用 stderr 最后一行作原因）。
- stderr 是调试日志，后端追加到 `<data-dir>/logs/wendao/<job_id>.log`。
- 路径可以是绝对路径，后端存库时若在工作目录下则转成相对路径。

## 文件约定

- 字幕：`<subtitles_dir>/<job_id>.<语言>.json`（原始字幕，B 站原生 `{"body":[{"from","to","content"}]}` 格式）、`<job_id>.srt`（带时间戳）、`<job_id>.txt`（清洗后实际交给 Claude 的文本）。本地导入的纯文本字幕没有时间戳，存为 `<job_id>.local.txt`，不生成 srt。
- 笔记：`<notes_dir>/<yyyyMMdd>_<标题，去掉文件名非法字符，最长 40 字>_<job_id>.md`，带 YAML front matter（标题、来源链接、BV 号、分P、UP 主、时长、关联功法、视频类型、字幕来源、生成时间），文末注明字幕来源和原视频链接。换行一律 `\n`。

## 脚本细节

### B 站字幕必须登录（2026-10 实测）

不带 cookie 时，B 站播放器接口返回的字幕列表**一律为空**，有字幕的视频会带 `need_login_subtitle: true`。UP 主字幕和 AI 字幕都是这样。所以要用闻道阁，必须先给它一份登录后的 cookie：

1. 在电脑浏览器里登录 bilibili.com。
2. 装一个能导出 cookies.txt 的扩展：Chrome / Edge 用 "Get cookies.txt LOCALLY"，Firefox 用 "cookies.txt"。
3. 在 B 站页面上点扩展，导出 Netscape 格式的 cookies.txt，保存为项目里的 `config/bilibili-cookies.txt`。

注意：
- 不用 yt-dlp 的 `--cookies-from-browser`：Windows 上新版 Chrome / Edge 的 cookie 有应用绑定加密，经常读不出来。
- cookies.txt 等同于你的 B 站登录态，不要发给别人，也不要提交到代码仓库。
- yt-dlp 退出时会把 cookie 写回文件，所以脚本只用它的临时副本，原件不会被改动。
- cookie 过期后，refine.py 会提示"cookies.txt 好像已经失效"，重新导出一份即可。

### 取字幕

- 用 yt-dlp 库（`.venv` 里的 `yt_dlp`）取元信息和字幕，不另起进程。
- B 站把弹幕也挂在字幕列表里，语言代码为 `danmaku`，格式为 xml，脚本会排除。
- 优先级：人工字幕优先于 AI 字幕（`ai-zh` 这类以 `ai-` 开头的语言代码）；同类里中文优先，其次英文。
- 分P：脚本按任务里的 BV 号和 `page` 自己拼 `?p=N` 的链接，不使用任务文件里的 `url` 字段。

### 清洗

合并碎句并去掉以下内容：
- 语气词（嗯、啊、那个……）和噪声标记（[音乐]、[掌声]）；
- 连续重复的行，以及滚动式字幕里被后一条包含的前一条。

切行规则：
- 间隔超过 3 秒、一行超过 25 秒或 80 字时另起一行。
- 中文片段之间补全角逗号，英文之间补空格。

每行写成 `[mm:ss t=秒数] 内容`。提示词要求 Claude 直接抄 t= 的值来生成时间点链接，不让它自己换算。字幕里的 `</transcript>` 会被打断，避免提前结束字幕块。

### 调 Claude

- 用 `shutil.which` 解析 `claude_command`（本机解析到的是 npm 版 `claude.CMD`），用参数列表加 stdin 调用，显式 UTF-8。
- 工作目录是一个空的临时目录，用完删除。
- 剔除从 Claude Code 会话继承来的会话级环境变量（`CLAUDECODE`、`CLAUDE_CODE_SESSION_ID`、消息管道等），否则子进程会被当成嵌套会话。用户自己配置的 `ANTHROPIC_*` 等变量保持不变。
- 退出码为 0 时，stderr 里的提示（例如网关兼容性通知）只记入日志，不算失败。
- 超时由脚本自己按 `timeout_seconds` 控制：到点后用 `taskkill /T` 杀掉 claude 的进程树。
- 失败原因会翻译成中文：未登录、额度用完、服务繁忙、输入过长、找不到 claude，其余情况附上 stderr 最后一行。

### 分段

- 清洗后的字幕不超过 6 万字时，整段一次送入。一节网课通常只有一两万字，整段送入的效果最好。
- 超过 6 万字时，按约 4 万字切段，每段生成分段笔记，最后再调用一次 Claude 合并。
- 阈值是 refine.py 顶部的 `SINGLE_PASS_LIMIT` / `CHUNK_TARGET`。

### 速度

经当前网关（ANTHROPIC_BASE_URL 指向第三方）实测：一段 5 分钟视频的字幕，一次调用要 9–10 分钟，加 `--effort medium` 也没有变快。
- 默认 30 分钟超时，够处理一般网课。
- 很长、需要分段的课会多次调用，可能超时。这时可以调大 `timeout-minutes`，或在 `claude-args` 里加 `--model sonnet` 之类换更快的模型。

### 手动调试

```text
.venv/Scripts/python.exe -X utf8 wendao/refine.py <任务文件.json> [--subtitle-file 字幕.srt|.json|.txt] [--title 标题] [--dry-run]
```

- `--subtitle-file`：不去 B 站拉字幕，直接用本地字幕。B 站取不到视频信息时，用 `--title` 指定标题。
- `--dry-run`：只生成 `<job_id>.prompt.txt`（发给 Claude 的完整输入），不调用 Claude。
- 提示词文件开头的 `<!-- … -->` 注释不会发给 Claude，可用占位符写在注释里。

样例效果见 [sample-note.md](sample-note.md)。

## 隐私

Hook 和文件都在本地，但 `claude -p` 会把提示词和字幕发给 Anthropic 的服务处理。界面和文档不得写"文本不离开本机"。
