"""闻道阁炼化脚本：B 站字幕 → Claude CLI → 本地 Markdown 学习笔记。

后端（com.xiuxian.wendao）按 docs/WENDAO.md 的约定在项目根目录调用：

    .venv/Scripts/python.exe -X utf8 wendao/refine.py <任务文件.json>

stdout 每行一个 JSON 事件，stderr 是调试日志。手动调试时还可以加：

    --subtitle-file 字幕.srt   不去 B 站拉字幕，直接用本地字幕（.srt / B 站 .json / 纯文本）
    --title 标题               配合 --subtitle-file，B 站取不到视频信息时用作标题
    --dry-run                  只取字幕、拼好发给 Claude 的输入，不调用 Claude
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import traceback
from dataclasses import dataclass, field
from pathlib import Path

# 字幕超过这个字数才分段炼化；一节网课通常只有一两万字，整段送入效果最好
SINGLE_PASS_LIMIT = 60_000
CHUNK_TARGET = 40_000

# 从 Claude Code 会话里启动修仙台时会继承这些变量，子进程带着它们会被当成嵌套会话
SESSION_ENV = {
    "CLAUDECODE", "CLAUDE_PID", "CLAUDE_CODE_CHILD_SESSION", "CLAUDE_CODE_SESSION_ID",
    "CLAUDE_CODE_SESSION_ATTENDED", "CLAUDE_CODE_MESSAGING_SOCKET", "CLAUDE_CODE_MESSAGING_TOKEN",
    "CLAUDE_CODE_ENTRYPOINT", "CLAUDE_CODE_EXECPATH",
}

JOB_ID_RE = re.compile(r"^BV[0-9A-Za-z]{10}_p\d{1,4}$")
BVID_RE = re.compile(r"^BV[0-9A-Za-z]{10}$")


class RefineError(Exception):
    """炼化失败，消息是给人看的中文原因。"""


# ---------------------------------------------------------------- 事件与日志

# 已经取到的视频标题：失败时也带上，界面上的失败任务才有名字
KNOWN: dict[str, str | None] = {"title": None}


def emit(event: str, **fields) -> None:
    payload = {"event": event, **{k: v for k, v in fields.items() if v is not None}}
    sys.stdout.write(json.dumps(payload, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def log(message: str) -> None:
    sys.stderr.write(f"[{dt.datetime.now():%H:%M:%S}] {message}\n")
    sys.stderr.flush()


# ---------------------------------------------------------------- 任务文件

@dataclass
class Job:
    job_id: str
    bvid: str
    page: int
    method: dict | None
    subtitles_dir: Path
    notes_dir: Path
    prompt_file: Path
    cookies_file: Path | None
    claude_command: str
    claude_args: list[str]
    timeout_seconds: int
    previous_note_path: Path | None

    @property
    def url(self) -> str:
        # 不用任务文件里的 url，按校验过的 BV 号和分P 自己拼
        return f"https://www.bilibili.com/video/{self.bvid}?p={self.page}"

    @property
    def time_link_base(self) -> str:
        return f"https://www.bilibili.com/video/{self.bvid}?p={self.page}&t="


def load_job(path: str) -> Job:
    try:
        data = json.loads(Path(path).read_text(encoding="utf-8-sig"))
    except (OSError, ValueError) as e:
        raise RefineError(f"读不了任务文件：{e}") from e

    def need(key):
        if data.get(key) in (None, ""):
            raise RefineError(f"任务文件缺少 {key}")
        return data[key]

    job_id, bvid = str(need("job_id")), str(need("bvid"))
    page = int(data.get("page") or 1)
    if not JOB_ID_RE.match(job_id) or not BVID_RE.match(bvid) or page < 1:
        raise RefineError(f"任务编号不合法：{job_id}")

    def opt_path(key):
        value = data.get(key)
        return Path(value) if value else None

    cookies = opt_path("cookies_file")
    return Job(
        job_id=job_id,
        bvid=bvid,
        page=page,
        method=data.get("method") or None,
        subtitles_dir=Path(need("subtitles_dir")),
        notes_dir=Path(need("notes_dir")),
        prompt_file=Path(need("prompt_file")),
        cookies_file=cookies if cookies and cookies.is_file() else None,
        claude_command=str(data.get("claude_command") or "claude"),
        claude_args=[str(a) for a in (data.get("claude_args") or ["-p"])],
        timeout_seconds=int(data.get("timeout_seconds") or 1800),
        previous_note_path=opt_path("previous_note_path"),
    )


# ---------------------------------------------------------------- 字幕

@dataclass
class Cue:
    start: float | None  # 秒；纯文本字幕没有时间
    end: float | None
    text: str


@dataclass
class Subtitle:
    lang: str
    kind: str          # 给人和 Claude 看的来源说明
    cues: list[Cue]
    raw: str           # 原始文本（srt 或 json）
    raw_ext: str


SRT_TIME_RE = re.compile(
    r"(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})")


def _seconds(h, m, s, ms) -> float:
    return int(h) * 3600 + int(m) * 60 + int(s) + int(ms.ljust(3, "0")) / 1000


def parse_srt(text: str) -> list[Cue]:
    cues = []
    for block in re.split(r"\r?\n\s*\r?\n", text.strip()):
        lines = [ln for ln in block.splitlines() if ln.strip()]
        for i, line in enumerate(lines):
            m = SRT_TIME_RE.search(line)
            if m:
                g = m.groups()
                content = " ".join(lines[i + 1:]).strip()
                if content:
                    cues.append(Cue(_seconds(*g[:4]), _seconds(*g[4:]), content))
                break
    return cues


def parse_bcc_json(data: dict) -> list[Cue]:
    """B 站原生字幕格式：{"body": [{"from": 1.2, "to": 3.4, "content": "…"}]}"""
    return [Cue(float(item["from"]), float(item["to"]), str(item["content"]))
            for item in data.get("body") or [] if str(item.get("content", "")).strip()]


def subtitle_kind(lang: str) -> str:
    if lang.startswith("ai-"):
        return "B 站 AI 自动字幕（没有标点，可能有同音错字）"
    return "UP 主上传的字幕"


def lang_rank(lang: str) -> tuple:
    """人工字幕优先于 AI 字幕；同类里中文优先，其次英文。"""
    ai = lang.startswith("ai-")
    base = lang[3:] if ai else lang
    region = 0 if base.startswith("zh") else 1 if base.startswith("en") else 2
    return (1 if ai else 0, region, lang)


def load_local_subtitle(path: Path) -> Subtitle:
    try:
        raw = path.read_text(encoding="utf-8-sig")
    except OSError as e:
        raise RefineError(f"读不了本地字幕文件：{e}") from e
    if path.suffix.lower() == ".json":
        try:
            cues = parse_bcc_json(json.loads(raw))
        except (ValueError, KeyError, TypeError) as e:
            raise RefineError(f"本地字幕不是 B 站字幕 JSON 格式：{e}") from e
        ext = "json"
    elif SRT_TIME_RE.search(raw):
        cues, ext = parse_srt(raw), "srt"
    else:
        cues = [Cue(None, None, ln.strip()) for ln in raw.splitlines() if ln.strip()]
        ext = "txt"
    return Subtitle("local", "本地导入的字幕", cues, raw, ext)


# ---------------------------------------------------------------- B 站

@dataclass
class Video:
    title: str
    uploader: str | None = None
    duration: float | None = None
    description: str = ""
    tags: list[str] = field(default_factory=list)
    chapters: list[dict] = field(default_factory=list)
    subtitle: Subtitle | None = None
    need_login: bool = False


class _YdlLogger:
    def __init__(self):
        self.warnings: list[str] = []

    def debug(self, msg):
        pass

    def info(self, msg):
        pass

    def warning(self, msg):
        self.warnings.append(msg)
        log(f"yt-dlp: {msg}")

    def error(self, msg):
        log(f"yt-dlp: {msg}")


def explain_fetch_error(message: str) -> str:
    low = message.lower()
    if "-404" in low or "404" in low or "not found" in low or "不见了" in message:
        return "视频不存在或已被删除，请检查链接"
    # BV 号格式对、但视频不存在时，yt-dlp 的 B 站解析器会抛 KeyError('bvid')
    if "keyerror" in low or "extractor error" in low:
        return "视频不存在、已删除，或 B 站暂时没返回视频信息，请检查链接后重试"
    if "-352" in low or "412" in low or "precondition" in low:
        return "被 B 站风控拦截了，请稍后再试；配置 cookies.txt 后会稳定很多"
    if "premium" in low or "大会员" in message or "login" in low:
        return "这个视频需要登录（或大会员）才能访问，请先配置 cookies.txt"
    if any(k in low for k in ("timed out", "timeout", "urlopen error", "getaddrinfo", "connection", "ssl")):
        return "连不上 B 站，请检查网络后重新炼化"
    detail = message.strip().splitlines()[-1].removeprefix("ERROR:").split("; please report")[0].strip()
    return "获取视频信息失败：" + detail[:200]


def fetch_video(job: Job, with_subtitles: bool = True) -> Video:
    import yt_dlp

    logger = _YdlLogger()
    opts = {
        "skip_download": True,
        "writesubtitles": with_subtitles,
        "quiet": True,
        "noprogress": True,
        "noplaylist": True,
        "logger": logger,
        "socket_timeout": 20,
        "retries": 3,
        "extractor_retries": 2,
    }
    cookie_copy = None
    if job.cookies_file:
        # yt-dlp 退出时会把 cookie 写回文件，用副本免得改动用户导出的原件
        fd, cookie_copy = tempfile.mkstemp(prefix="wendao-cookies-", suffix=".txt")
        os.close(fd)
        shutil.copyfile(job.cookies_file, cookie_copy)
        opts["cookiefile"] = cookie_copy

    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            try:
                info = ydl.extract_info(job.url, download=False)
            except yt_dlp.utils.DownloadError as e:
                raise RefineError(explain_fetch_error(str(e))) from e
            video = Video(
                title=str(info.get("title") or f"{job.bvid} P{job.page}"),
                uploader=info.get("uploader"),
                duration=info.get("duration"),
                description=str(info.get("description") or ""),
                tags=[str(t) for t in info.get("tags") or []],
                chapters=list(info.get("chapters") or []),
                need_login=any("only available when logged in" in w for w in logger.warnings),
            )
            KNOWN["title"] = video.title
            if with_subtitles:
                video.subtitle = _pick_subtitle(ydl, info.get("subtitles") or {})
            return video
    finally:
        if cookie_copy:
            Path(cookie_copy).unlink(missing_ok=True)


def _pick_subtitle(ydl, subtitles: dict) -> Subtitle | None:
    # B 站把弹幕也挂在字幕列表里（danmaku，xml），要排除
    langs = sorted((lang for lang, fmts in subtitles.items() if lang != "danmaku" and fmts), key=lang_rank)
    for lang in langs:
        for fmt in subtitles[lang]:
            ext = (fmt.get("ext") or "").lower()
            raw = fmt.get("data")
            if raw is None and fmt.get("url"):
                try:
                    raw = ydl.urlopen(fmt["url"]).read().decode("utf-8", "replace")
                except Exception as e:  # noqa: BLE001 —— 换下一种格式或语言再试
                    log(f"下载字幕 {lang}.{ext} 失败：{e}")
                    continue
            if not raw:
                continue
            if ext == "json":
                try:
                    cues = parse_bcc_json(json.loads(raw))
                except (ValueError, KeyError, TypeError):
                    continue
            elif ext in ("srt", "vtt"):
                cues = parse_srt(raw)
            else:
                continue
            if cues:
                log(f"选用字幕：{lang}（{ext}，{len(cues)} 条）；可选：{', '.join(langs)}")
                return Subtitle(lang, subtitle_kind(lang), cues, raw, ext)
    return None


def no_subtitle_reason(video: Video, job: Job) -> str:
    if video.need_login and not job.cookies_file:
        return ("这个视频的字幕要登录 B 站才能获取。请按说明从浏览器导出 cookies.txt，"
                "放到 config/bilibili-cookies.txt 后重新炼化")
    if video.need_login:
        return "cookies.txt 好像已经失效（B 站仍要求登录），请重新导出后再炼化"
    return "这个视频没有字幕（UP 主没传字幕，B 站也没生成 AI 字幕），无法炼化"


# ---------------------------------------------------------------- 清洗

FILLERS = {"嗯", "啊", "呃", "额", "哦", "唉", "诶", "欸", "嗯嗯", "啊啊", "那个", "就是", "然后", "对吧", "好"}
NOISE_RE = re.compile(r"^[\[(（【]?(音乐|掌声|笑声|笑|鼓掌|music|applause|laughter)[\])）】]?$", re.I)
END_PUNCT = "。！？!?；;…"
ANY_PUNCT = END_PUNCT + "，,、：:"


@dataclass
class Line:
    start: float | None
    end: float | None
    text: str


def clean_text(text: str) -> str:
    text = text.replace("　", " ").replace("\xa0", " ").replace("♪", " ")
    text = re.sub(r"<[^>]+>", "", text)          # 个别字幕带 <font> 之类的标签
    return re.sub(r"\s+", " ", text).strip()


def _is_cjk(ch: str) -> bool:
    return "一" <= ch <= "鿿"


def _join(a: str, b: str) -> str:
    if not a:
        return b
    if a[-1] in ANY_PUNCT:
        return a + b
    # 自动字幕没有标点：中文之间补逗号，读起来接近原话的停顿
    return a + ("，" if _is_cjk(a[-1]) and _is_cjk(b[0]) else " ") + b


def merge_cues(cues: list[Cue]) -> list[Line]:
    """把碎片字幕合并成句子级的行：去掉语气词、噪声和重复，保留每行的开始时间。"""
    lines: list[Line] = []
    cur: Line | None = None
    last_text = ""
    for cue in cues:
        text = clean_text(cue.text)
        if not text or NOISE_RE.match(text) or text in FILLERS:
            continue
        if text == last_text:
            continue
        # 滚动式字幕：后一条包含前一条，只留更长的那条
        if cur and last_text and text.startswith(last_text) and cur.text.endswith(last_text):
            cur.text = cur.text[: len(cur.text) - len(last_text)] + text
            cur.end, last_text = cue.end, text
            continue
        if cur and last_text.endswith(text):
            continue

        new_line = cur is None
        if cur is not None and cue.start is not None and cur.end is not None:
            gap = cue.start - cur.end
            span = (cue.end or cue.start) - (cur.start or cue.start)
            new_line = (gap > 3 or span > 25 or len(cur.text) >= 80
                        or (cur.text[-1] in END_PUNCT and len(cur.text) >= 20))
        elif cur is not None:
            new_line = cur.text[-1] in END_PUNCT or len(cur.text) >= 80

        if new_line:
            cur = Line(cue.start, cue.end, text)
            lines.append(cur)
        else:
            cur.text = _join(cur.text, text)
            cur.end = cue.end
        last_text = text
    return lines


def fmt_clock(seconds: float | None) -> str:
    if seconds is None:
        return "?"
    s = int(seconds)
    h, m, s = s // 3600, s % 3600 // 60, s % 60
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m:02d}:{s:02d}"


def render_transcript(lines: list[Line]) -> str:
    out = []
    for ln in lines:
        text = ln.text.replace("</transcript>", "</ transcript>")
        if ln.start is None:
            out.append(text)
        else:
            out.append(f"[{fmt_clock(ln.start)} t={int(ln.start)}] {text}")
    return "\n".join(out)


def cues_to_srt(cues: list[Cue]) -> str:
    def ts(sec: float) -> str:
        ms = int(round(sec * 1000))
        return f"{ms // 3600000:02d}:{ms // 60000 % 60:02d}:{ms // 1000 % 60:02d},{ms % 1000:03d}"
    return "\n".join(f"{i}\n{ts(c.start)} --> {ts(c.end)}\n{c.text}\n"
                     for i, c in enumerate((c for c in cues if c.start is not None), 1))


# ---------------------------------------------------------------- 分类

DEMO_WORDS = ["实操", "演示", "实战", "手把手", "教程", "安装", "配置", "部署", "搭建", "上手", "动手",
              "环境", "实验", "lab", "demo", "操作", "步骤", "代码实现", "编程实现", "敲代码", "调试",
              "从零", "项目", "实现一个", "命令行", "vim", "gdb", "linux"]
THEORY_WORDS = ["讲解", "原理", "概念", "定义", "定理", "证明", "推导", "知识点", "考点", "例题", "习题",
                "真题", "精讲", "基础", "强化", "冲刺", "网课", "课程", "理论", "公式", "高数", "高等数学",
                "线代", "线性代数", "概率", "数据结构", "操作系统", "计组", "组成原理", "计网", "计算机网络",
                "408", "考研", "听力", "阅读", "翻译", "写作", "作文", "六级", "四级", "cet", "词汇",
                "单词", "语法", "张宇", "王道", "汤家凤", "武忠祥", "李永乐", "极限", "导数", "积分"]
CHAPTER_RE = re.compile(r"第\s*[一二三四五六七八九十百\d]+\s*[讲章节课]")

VIDEO_TYPE_DESC = {
    "theory": "theory（讲解 / 理论 / 习题课）",
    "demo": "demo（实操 / 演示 / 编程）",
    "unknown": "unknown（未能自动判断，请按内容自行把握）",
}


def classify(video: Video) -> str:
    """按关键词粗分：标题权重 3，标签 2，简介 1。"""
    def score(words):
        title, desc = video.title.lower(), video.description.lower()[:500]
        tags = " ".join(video.tags).lower()
        return sum(3 * (w in title) + 2 * (w in tags) + (w in desc) for w in words)

    theory = score(THEORY_WORDS) + (3 if CHAPTER_RE.search(video.title) else 0)
    demo = score(DEMO_WORDS)
    if demo >= 2 and demo > theory:
        return "demo"
    if theory >= 2:
        return "theory"
    return "unknown"


# ---------------------------------------------------------------- 提示词

PART_NOTE = ("【分段模式】这个视频很长，本次只给你第 {i}/{n} 段字幕（{a}–{b}）。只整理这一段："
             "不写一级标题、一句话概要、自测题和参考答案，其余各节照常写，节标题用「###」。")
MERGE_NOTE = ("【合并模式】这个视频很长，已经按时间顺序分段整理出下面的分段笔记——<transcript> 里放的是这些"
              "分段笔记，不是字幕。请把它们合并成一份完整笔记，严格按上面的结构输出：去掉重复，理顺知识结构，"
              "补上一句话概要、自测题和参考答案，关键时间点从各段里挑选；时间点链接原样保留。")


def load_prompt(path: Path) -> str:
    try:
        template = path.read_text(encoding="utf-8-sig")
    except OSError as e:
        raise RefineError(f"读不了提示词文件 {path}：{e}") from e
    template = re.sub(r"<!--.*?-->", "", template, flags=re.S).strip()
    if "{{transcript}}" not in template:
        template += "\n\n<transcript>\n{{transcript}}\n</transcript>\n"
    return template


def render_prompt(template: str, values: dict) -> str:
    def sub(m):
        key = m.group(1)
        if key not in values:
            log(f"提示词里有未知占位符 {{{{{key}}}}}，已留空")
        return str(values.get(key, ""))
    return re.sub(r"\{\{(\w+)\}\}", sub, template)


def method_desc(method: dict | None) -> str:
    if not method:
        return "未关联"
    name = method.get("name") or ""
    extra = "，".join(x for x in (method.get("subject"), method.get("chapter") and f"当前篇章：{method['chapter']}") if x)
    return f"{name}（{extra}）" if extra else name


def chapters_desc(chapters: list[dict]) -> str:
    items = [f"{fmt_clock(c.get('start_time'))} {c.get('title', '').strip()}" for c in chapters[:30] if c.get("title")]
    return "；".join(items) if items else "无"


def one_line(text: str, limit: int) -> str:
    text = re.sub(r"\s+", " ", text).strip()
    return (text[:limit] + "…") if len(text) > limit else (text or "无")


# ---------------------------------------------------------------- Claude

def resolve_claude(command: str) -> str:
    found = shutil.which(command)
    if found:
        return found
    if Path(command).is_file():
        return command
    raise RefineError("找不到 Claude CLI：请确认已安装 Claude Code，并且在命令行里能直接运行 claude"
                      "（也可以在配置里写 claude 的完整路径）")


def kill_tree(proc: subprocess.Popen) -> None:
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"], capture_output=True)
    else:
        proc.kill()


def explain_claude_failure(code: int, stdout: str, stderr: str) -> str:
    text = f"{stdout}\n{stderr}".lower()
    if any(k in text for k in ("not logged in", "/login", "invalid api key", "unauthorized", "401",
                               "authentication", "oauth token")):
        return "Claude CLI 没登录或登录已失效：请在终端运行 claude 完成登录后，再重新炼化"
    if any(k in text for k in ("rate limit", "usage limit", "429", "quota", "credit balance")):
        return "Claude 额度用完了或请求太频繁，请稍后重新炼化"
    if "prompt is too long" in text or "context length" in text or "too many tokens" in text:
        return "字幕太长，超出了模型一次能读的长度，请调小 refine.py 里的分段阈值后重新炼化"
    if any(k in text for k in ("overloaded", "529", "503", "502", "econnreset", "fetch failed", "network")):
        return "Claude 服务暂时不可用（网络或服务繁忙），请稍后重新炼化"
    last = next((ln.strip() for ln in reversed(stderr.splitlines()) if ln.strip()), "")
    return f"Claude CLI 运行失败（退出码 {code}）" + (f"：{last[:200]}" if last else "")


def strip_fences(text: str) -> str:
    m = re.match(r"^```(?:markdown|md)?\s*\n(.*)\n```\s*$", text, re.S)
    return m.group(1).strip() if m else text


def run_claude(job: Job, prompt: str, deadline: float, label: str) -> str:
    exe = resolve_claude(job.claude_command)
    remaining = deadline - time.monotonic()
    if remaining < 30:
        raise RefineError("炼化超时：剩余时间不够再调用一次 Claude，可以稍后重新炼化")
    env = {k: v for k, v in os.environ.items() if k not in SESSION_ENV}
    workdir = tempfile.mkdtemp(prefix="wendao-")  # 空目录：即使有工具也无文件可碰
    log(f"调用 Claude（{label}）：输入 {len(prompt)} 字")
    started = time.monotonic()
    try:
        proc = subprocess.Popen([exe, *job.claude_args], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, cwd=workdir, env=env)
        try:
            out, err = proc.communicate(prompt.encode("utf-8"), timeout=remaining)
        except subprocess.TimeoutExpired:
            kill_tree(proc)
            proc.communicate()
            raise RefineError("炼化超时：Claude 在限定时间内没写完笔记，可以稍后重新炼化") from None
    finally:
        shutil.rmtree(workdir, ignore_errors=True)

    stdout, stderr = out.decode("utf-8", "replace"), err.decode("utf-8", "replace")
    log(f"Claude（{label}）用时 {time.monotonic() - started:.0f} 秒，退出码 {proc.returncode}，输出 {len(stdout)} 字")
    if stderr.strip():
        log(f"Claude stderr：{stderr.strip()[-1500:]}")
    if proc.returncode != 0:
        raise RefineError(explain_claude_failure(proc.returncode, stdout, stderr))
    text = strip_fences(stdout.strip())
    if not text:
        raise RefineError("Claude 没有返回任何内容，可以稍后重新炼化")
    return text


def split_lines(lines: list[Line], target: int) -> list[list[Line]]:
    chunks, cur, size = [], [], 0
    for ln in lines:
        if cur and size + len(ln.text) > target:
            chunks.append(cur)
            cur, size = [], 0
        cur.append(ln)
        size += len(ln.text) + 16
    if cur:
        chunks.append(cur)
    return chunks


# ---------------------------------------------------------------- 笔记

ILLEGAL_FILENAME = re.compile(r'[<>:"/\\|?*\x00-\x1f]')


def safe_filename(title: str, limit: int = 40) -> str:
    name = ILLEGAL_FILENAME.sub("", title)
    name = re.sub(r"\s+", " ", name).strip(" .")
    return name[:limit].strip(" .") or "未命名"


def front_matter(fields: dict) -> str:
    lines = ["---"]
    for key, value in fields.items():
        if value in (None, ""):
            continue
        lines.append(f"{key}: {json.dumps(value, ensure_ascii=False) if isinstance(value, str) else value}")
    lines.append("---")
    return "\n".join(lines)


def write_note(job: Job, video: Video, sub: Subtitle, video_type: str, body: str) -> Path:
    if job.previous_note_path:
        path = job.previous_note_path
    else:
        path = job.notes_dir / f"{dt.datetime.now():%Y%m%d}_{safe_filename(video.title)}_{job.job_id}.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    meta = front_matter({
        "title": video.title,
        "source": job.url,
        "bvid": job.bvid,
        "page": job.page,
        "uploader": video.uploader,
        "duration": fmt_clock(video.duration) if video.duration else None,
        "method": method_desc(job.method) if job.method else None,
        "video_type": video_type,
        "subtitle": f"{sub.kind} · {sub.lang}",
        "created": f"{dt.datetime.now():%Y-%m-%d %H:%M}",
        "generator": "闻道阁 · Claude CLI",
    })
    footer = (f"\n\n---\n\n*本笔记由闻道阁根据视频字幕整理，字幕来源：{sub.kind}。"
              f"内容可能有误，请以[原视频]({job.url})为准。*\n")
    path.write_text(f"{meta}\n\n{body.strip()}{footer}", encoding="utf-8", newline="\n")
    return path


# ---------------------------------------------------------------- 主流程

def refine(job: Job, args: argparse.Namespace, deadline: float) -> int:
    job.subtitles_dir.mkdir(parents=True, exist_ok=True)
    job.notes_dir.mkdir(parents=True, exist_ok=True)

    if args.subtitle_file:
        try:
            video = fetch_video(job, with_subtitles=False)
        except RefineError as e:
            log(f"取不到视频信息，改用本地信息：{e}")
            video = Video(title=args.title or f"{job.bvid} P{job.page}")
        if args.title:
            video.title = args.title
        sub = load_local_subtitle(Path(args.subtitle_file))
    else:
        video = fetch_video(job)
        if video.subtitle is None:
            raise RefineError(no_subtitle_reason(video, job))
        sub = video.subtitle
    if not sub.cues:
        raise RefineError("字幕是空的，无法炼化")

    # 原始字幕（B 站原生 JSON 格式）、带时间戳的 srt、清洗后交给 Claude 的文本各存一份
    timed = any(c.start is not None for c in sub.cues)
    if timed:
        raw_path = job.subtitles_dir / f"{job.job_id}.{sub.lang}.json"
        raw = sub.raw if sub.raw_ext == "json" else json.dumps(
            {"lang": sub.lang, "kind": sub.kind,
             "body": [{"from": c.start, "to": c.end, "content": c.text} for c in sub.cues]},
            ensure_ascii=False, indent=1)
        raw_path.write_text(raw, encoding="utf-8", newline="\n")
        srt_path = job.subtitles_dir / f"{job.job_id}.srt"
        srt_path.write_text(cues_to_srt(sub.cues), encoding="utf-8", newline="\n")
    else:
        raw_path = srt_path = job.subtitles_dir / f"{job.job_id}.{sub.lang}.txt"
        raw_path.write_text(sub.raw, encoding="utf-8", newline="\n")
    lines = merge_cues(sub.cues)
    transcript = render_transcript(lines)
    if not transcript.strip():
        raise RefineError("字幕清洗后没有剩下有效内容，无法炼化")
    (job.subtitles_dir / f"{job.job_id}.txt").write_text(transcript, encoding="utf-8", newline="\n")

    video_type = classify(video)
    if not video.duration and timed:
        video.duration = max(c.end or 0 for c in sub.cues)
    emit("status", status="SUBTITLE_READY", title=video.title, video_type=video_type,
         subtitle_path=str(srt_path.resolve()))
    log(f"字幕 {len(sub.cues)} 条 → 合并为 {len(lines)} 行，{len(transcript)} 字；类型 {video_type}")

    template = load_prompt(job.prompt_file)
    base = {
        "title": video.title,
        "url": job.url,
        "bvid": job.bvid,
        "page": job.page,
        "uploader": video.uploader or "未知",
        "duration": fmt_clock(video.duration) if video.duration else "未知",
        "video_type": video_type,
        "video_type_desc": VIDEO_TYPE_DESC[video_type],
        "method": method_desc(job.method),
        "subtitle_kind": sub.kind,
        "chapters": chapters_desc(video.chapters),
        "tags": "、".join(video.tags[:12]) or "无",
        "description": one_line(video.description, 300),
        "time_link_base": job.time_link_base,
        "mode_note": "",
    }

    if args.dry_run:
        prompt = render_prompt(template, {**base, "transcript": transcript})
        out = job.subtitles_dir / f"{job.job_id}.prompt.txt"
        out.write_text(prompt, encoding="utf-8", newline="\n")
        emit("progress", message=f"dry-run：已生成发给 Claude 的输入 {out}")
        return 0

    emit("status", status="REFINING")
    if len(transcript) <= SINGLE_PASS_LIMIT:
        body = run_claude(job, render_prompt(template, {**base, "transcript": transcript}), deadline, "整段")
    else:
        chunks = split_lines(lines, CHUNK_TARGET)
        parts = []
        for i, chunk in enumerate(chunks, 1):
            emit("progress", message=f"分段炼化 {i}/{len(chunks)}")
            note = PART_NOTE.format(i=i, n=len(chunks), a=fmt_clock(chunk[0].start), b=fmt_clock(chunk[-1].end))
            prompt = render_prompt(template, {**base, "mode_note": note, "transcript": render_transcript(chunk)})
            parts.append(run_claude(job, prompt, deadline, f"分段 {i}/{len(chunks)}"))
        emit("progress", message="合并分段笔记")
        merged = "\n\n".join(
            f"【第 {i} 段 · {fmt_clock(c[0].start)}–{fmt_clock(c[-1].end)}】\n{p.replace('</transcript>', '</ transcript>')}"
            for i, (c, p) in enumerate(zip(chunks, parts), 1))
        body = run_claude(job, render_prompt(template, {**base, "mode_note": MERGE_NOTE, "transcript": merged}),
                          deadline, "合并")

    path = write_note(job, video, sub, video_type, body)
    log(f"笔记已写入 {path}")
    emit("done", note_path=str(path.resolve()))
    return 0


def main(argv: list[str] | None = None) -> int:
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="闻道阁：把 B 站视频字幕炼化成本地 Markdown 笔记")
    parser.add_argument("job", help="任务文件（JSON），格式见 docs/WENDAO.md")
    parser.add_argument("--subtitle-file", help="调试/导入：直接使用本地字幕文件（.srt / .json / .txt）")
    parser.add_argument("--title", help="配合 --subtitle-file：取不到视频信息时用作标题")
    parser.add_argument("--dry-run", action="store_true", help="只取字幕、生成输入，不调用 Claude")
    args = parser.parse_args(argv)

    try:
        job = load_job(args.job)
    except RefineError as e:
        emit("failed", message=str(e))
        return 2
    deadline = time.monotonic() + job.timeout_seconds
    try:
        return refine(job, args, deadline)
    except RefineError as e:
        log(f"炼化失败：{e}")
        emit("failed", message=str(e), title=KNOWN["title"])
        return 1
    except Exception as e:  # noqa: BLE001 —— 兜底，保证一定输出 failed 事件
        log(traceback.format_exc())
        emit("failed", message=f"炼化出错：{type(e).__name__}: {e}", title=KNOWN["title"])
        return 1


if __name__ == "__main__":
    sys.exit(main())
