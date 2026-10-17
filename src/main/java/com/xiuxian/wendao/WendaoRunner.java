package com.xiuxian.wendao;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiuxian.common.DayClock;
import com.xiuxian.config.XiuxianProperties;

import jakarta.annotation.PreDestroy;

/**
 * 跑一次炼化：写任务文件，调用 wendao/refine.py，逐行读它的 JSON 事件更新任务状态。
 * 约定见 docs/WENDAO.md。进程用参数列表启动，不经过 shell；超时杀掉整个进程树。
 */
@Component
public class WendaoRunner {

    private static final Logger log = LoggerFactory.getLogger(WendaoRunner.class);
    private static final Set<String> PROGRESS_STATUSES = Set.of("SUBTITLE_READY", "REFINING");
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private record Job(long id, String key, String bvid, int page, String url, Long methodId) {
    }

    /** 脚本这一次运行的结果。 */
    private static final class Outcome {
        boolean done;
        String notePath;
        String failure;
    }

    private final JdbcTemplate jdbc;
    private final DayClock clock;
    private final XiuxianProperties props;
    private final ObjectMapper mapper;
    private final Path workDir = Path.of("").toAbsolutePath();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "wendao-watchdog");
        t.setDaemon(true);
        return t;
    });
    private volatile Process current;

    public WendaoRunner(JdbcTemplate jdbc, DayClock clock, XiuxianProperties props, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.props = props;
        this.mapper = mapper;
    }

    /** 相对路径按应用工作目录（项目根目录）解析。 */
    public Path resolve(String path) {
        Path p = Path.of(path);
        return (p.isAbsolute() ? p : workDir.resolve(p)).normalize();
    }

    /** 存库用：在工作目录下的转成相对路径（正斜杠），否则保留绝对路径。 */
    public String display(String path) {
        Path p = resolve(path);
        return p.startsWith(workDir) ? workDir.relativize(p).toString().replace('\\', '/') : p.toString();
    }

    /** 环境自检：前端据此提示还缺什么。 */
    public Map<String, Boolean> environment() {
        XiuxianProperties.Wendao w = props.wendao();
        Map<String, Boolean> m = new LinkedHashMap<>();
        m.put("python", commandExists(w.python()));
        m.put("script", Files.isRegularFile(resolve(w.script())));
        m.put("prompt", Files.isRegularFile(resolve(w.promptFile())));
        m.put("cookies", Files.isRegularFile(resolve(w.cookiesFile())));
        m.put("claude", commandExists(w.claudeCommand()));
        return m;
    }

    void run(long jobId, String previousNote) {
        Job job = load(jobId);
        if (job == null) {
            return;
        }
        Path logFile = resolve(props.dataDir()).resolve("logs").resolve("wendao").resolve(job.key() + ".log");
        try {
            Path jobFile = writeJobFile(job, previousNote);
            Files.createDirectories(logFile.getParent());
            long logOffset = Files.exists(logFile) ? Files.size(logFile) : 0;

            ProcessBuilder pb = new ProcessBuilder(command(jobFile)).directory(workDir.toFile());
            pb.environment().put("PYTHONUTF8", "1");
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            Process process = pb.start();
            current = process;

            AtomicBoolean timedOut = new AtomicBoolean(false);
            long limitSeconds = props.wendao().timeoutMinutes() * 60L + 60;
            ScheduledFuture<?> killer = watchdog.schedule(() -> {
                timedOut.set(true);
                kill(process);
            }, limitSeconds, TimeUnit.SECONDS);

            Outcome outcome = new Outcome();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    handle(jobId, line, outcome);
                }
            } finally {
                killer.cancel(false);
            }
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                kill(process);
            }
            int code = process.isAlive() ? -1 : process.exitValue();

            if (timedOut.get()) {
                fail(jobId, "炼化超时");
            } else if (code == 0 && outcome.done && outcome.notePath != null) {
                jdbc.update("UPDATE wendao_job SET status = 'DONE', note_path = ?, message = NULL, updated_at = ? "
                        + "WHERE id = ?", outcome.notePath, clock.now(), jobId);
            } else if (outcome.failure != null) {
                fail(jobId, outcome.failure);
            } else {
                fail(jobId, lastLogLine(logFile, logOffset)
                        .map(l -> "炼化失败：" + truncate(l, 300))
                        .orElse("炼化失败（退出码 " + code + "）"));
            }
        } catch (IOException e) {
            fail(jobId, "无法启动炼化脚本：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(jobId, "洞府关闭，炼化中断");
        } catch (RuntimeException e) {
            log.error("炼化任务 {} 出错", jobId, e);
            fail(jobId, "炼化失败：" + e.getMessage());
        } finally {
            current = null;
        }
    }

    @PreDestroy
    void shutdown() {
        Process p = current;
        if (p != null) {
            kill(p);
        }
        watchdog.shutdownNow();
    }

    private void handle(long jobId, String line, Outcome outcome) {
        JsonNode ev;
        try {
            ev = mapper.readTree(line);
        } catch (JsonProcessingException e) {
            return;
        }
        if (ev == null || !ev.isObject()) {
            return;
        }
        long now = clock.now();
        switch (ev.path("event").asText("")) {
            case "status" -> {
                String status = ev.path("status").asText("");
                if (!PROGRESS_STATUSES.contains(status)) {
                    return;
                }
                String subtitle = text(ev, "subtitle_path");
                jdbc.update("""
                        UPDATE wendao_job SET status = ?, title = COALESCE(?, title), video_type = COALESCE(?, video_type),
                               subtitle_path = COALESCE(?, subtitle_path), message = NULL, updated_at = ?
                        WHERE id = ?
                        """, status, truncate(text(ev, "title"), 200), truncate(text(ev, "video_type"), 20),
                        subtitle == null ? null : display(subtitle), now, jobId);
            }
            case "progress" -> jdbc.update("UPDATE wendao_job SET message = ?, updated_at = ? WHERE id = ?",
                    truncate(text(ev, "message"), 200), now, jobId);
            case "done" -> {
                String note = text(ev, "note_path");
                outcome.done = true;
                outcome.notePath = note == null ? null : display(note);
            }
            case "failed" -> {
                outcome.failure = truncate(text(ev, "message"), 500);
                String title = truncate(text(ev, "title"), 200);
                if (title != null) {
                    jdbc.update("UPDATE wendao_job SET title = ?, updated_at = ? WHERE id = ?", title, now, jobId);
                }
            }
            default -> {
                // 未知事件忽略
            }
        }
    }

    private Job load(long jobId) {
        return jdbc.query("SELECT id, job_key, bvid, page, url, method_id FROM wendao_job WHERE id = ?",
                (rs, i) -> {
                    long methodId = rs.getLong("method_id");
                    return new Job(rs.getLong("id"), rs.getString("job_key"), rs.getString("bvid"),
                            rs.getInt("page"), rs.getString("url"), rs.wasNull() ? null : methodId);
                }, jobId).stream().findFirst().orElse(null);
    }

    private Path writeJobFile(Job job, String previousNote) throws IOException {
        XiuxianProperties.Wendao w = props.wendao();
        Path subtitles = resolve(w.subtitlesDir());
        Path notes = resolve(w.notesDir());
        Path jobs = resolve(w.jobsDir());
        Files.createDirectories(subtitles);
        Files.createDirectories(notes);
        Files.createDirectories(jobs);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("job_id", job.key());
        m.put("bvid", job.bvid());
        m.put("page", job.page());
        m.put("url", job.url());
        m.put("method", job.methodId() == null ? null : methodInfo(job.methodId()));
        m.put("subtitles_dir", subtitles.toString());
        m.put("notes_dir", notes.toString());
        m.put("prompt_file", resolve(w.promptFile()).toString());
        Path cookies = resolve(w.cookiesFile());
        m.put("cookies_file", Files.isRegularFile(cookies) ? cookies.toString() : null);
        m.put("claude_command", w.claudeCommand());
        m.put("claude_args", w.claudeArgs());
        m.put("timeout_seconds", w.timeoutMinutes() * 60);
        Path previous = previousNote == null ? null : resolve(previousNote);
        m.put("previous_note_path", previous != null && Files.isRegularFile(previous) ? previous.toString() : null);

        Path file = jobs.resolve(job.key() + ".json");
        Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(m), StandardCharsets.UTF_8);
        return file;
    }

    private Map<String, Object> methodInfo(long methodId) {
        return jdbc.query("SELECT name, subject, chapter FROM method WHERE id = ?", (rs, i) -> {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", rs.getString("name"));
            info.put("subject", rs.getString("subject"));
            info.put("chapter", rs.getString("chapter"));
            return info;
        }, methodId).stream().findFirst().orElse(null);
    }

    private List<String> command(Path jobFile) {
        XiuxianProperties.Wendao w = props.wendao();
        List<String> cmd = new ArrayList<>();
        cmd.add(executable(w.python()));
        cmd.add("-X");
        cmd.add("utf8");
        cmd.add(resolve(w.script()).toString());
        cmd.add(jobFile.toString());
        return cmd;
    }

    /** 带路径的按工作目录解析；裸命令名（如 python）交给 PATH。 */
    private String executable(String command) {
        return hasSeparator(command) ? resolve(command).toString() : command;
    }

    private boolean commandExists(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        if (hasSeparator(command)) {
            Path p = resolve(command);
            return Files.isRegularFile(p) || candidates(p.toString()).stream().anyMatch(c -> Files.isRegularFile(Path.of(c)));
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return false;
        }
        for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String name : candidates(command)) {
                try {
                    if (Files.isRegularFile(Path.of(dir.strip(), name))) {
                        return true;
                    }
                } catch (RuntimeException e) {
                    // PATH 里有非法路径时跳过
                }
            }
        }
        return false;
    }

    /** Windows 上命令可能是 claude.exe / claude.cmd。 */
    private static List<String> candidates(String name) {
        List<String> names = new ArrayList<>(List.of(name));
        if (WINDOWS && !name.contains(".")) {
            String pathExt = Optional.ofNullable(System.getenv("PATHEXT")).orElse(".EXE;.CMD;.BAT");
            for (String ext : pathExt.split(";")) {
                if (!ext.isBlank()) {
                    names.add(name + ext.toLowerCase(Locale.ROOT));
                }
            }
        }
        return names;
    }

    private static boolean hasSeparator(String command) {
        return command.contains("/") || command.contains("\\");
    }

    private void fail(long jobId, String message) {
        jdbc.update("UPDATE wendao_job SET status = 'FAILED', message = ?, updated_at = ? WHERE id = ?",
                truncate(message, 500), clock.now(), jobId);
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /** 只看本次运行追加的那段日志，取最后一个非空行。 */
    private static Optional<String> lastLogLine(Path logFile, long offset) {
        try (RandomAccessFile f = new RandomAccessFile(logFile.toFile(), "r")) {
            long len = f.length();
            long start = Math.max(offset, len - 8192);
            if (len <= start) {
                return Optional.empty();
            }
            byte[] buf = new byte[(int) (len - start)];
            f.seek(start);
            f.readFully(buf);
            String[] lines = new String(buf, StandardCharsets.UTF_8).split("\\R");
            for (int i = lines.length - 1; i >= 0; i--) {
                if (!lines[i].isBlank()) {
                    return Optional.of(lines[i].strip());
                }
            }
        } catch (IOException e) {
            // 读不到日志就算了
        }
        return Optional.empty();
    }

    private static String text(JsonNode ev, String field) {
        JsonNode n = ev.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        String s = n.asText();
        return s.isBlank() ? null : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
