package com.xiuxian.wendao;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import com.xiuxian.common.ApiException;
import com.xiuxian.common.DayClock;

import jakarta.annotation.PreDestroy;

/** 闻道阁：任务的增删查与单线程队列。同一时刻只炼化一个玉简。 */
@Service
public class WendaoService {

    public record NoteView(String markdown, String path) {
    }

    /** 还在队列里或正在处理的状态。 */
    private static final Set<String> BUSY = Set.of("WAITING", "SUBTITLE_READY", "REFINING");

    private static final String SELECT = """
            SELECT j.*, m.name AS method_name
            FROM wendao_job j LEFT JOIN method m ON m.id = j.method_id
            """;

    private final JdbcTemplate jdbc;
    private final DayClock clock;
    private final BiliLinks links;
    private final WendaoRunner runner;
    private final RowMapper<WendaoJob> mapper;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wendao-worker");
        t.setDaemon(true);
        return t;
    });

    public WendaoService(JdbcTemplate jdbc, DayClock clock, BiliLinks links, WendaoRunner runner) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.links = links;
        this.runner = runner;
        this.mapper = (rs, i) -> {
            long methodId = rs.getLong("method_id");
            Long method = rs.wasNull() ? null : methodId;
            String notePath = rs.getString("note_path");
            boolean hasNote = notePath != null && Files.isRegularFile(runner.resolve(notePath));
            return new WendaoJob(rs.getLong("id"), rs.getString("job_key"), rs.getString("bvid"), rs.getInt("page"),
                    rs.getString("url"), rs.getString("title"), method, rs.getString("method_name"),
                    rs.getString("video_type"), rs.getString("status"), rs.getString("message"), notePath, hasNote,
                    rs.getLong("created_at"), rs.getLong("updated_at"));
        };
    }

    /** 上次关闭时没炼完的任务不会自动续上。 */
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        jdbc.update("UPDATE wendao_job SET status = 'FAILED', message = '洞府重启，炼化中断', updated_at = ? "
                + "WHERE status IN ('WAITING', 'SUBTITLE_READY', 'REFINING')", clock.now());
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

    public Map<String, Boolean> status() {
        return runner.environment();
    }

    public List<WendaoJob> list() {
        return jdbc.query(SELECT + " ORDER BY j.created_at DESC, j.id DESC", mapper);
    }

    public WendaoJob get(long id) {
        return jdbc.query(SELECT + " WHERE j.id = ?", mapper, id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("未找到此玉简"));
    }

    public WendaoJob create(String url, Long methodId) {
        BiliLinks.Video video = links.parse(url);
        if (methodId != null) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM method WHERE id = ?", Integer.class, methodId);
            if (n == null || n == 0) {
                throw ApiException.badRequest("未找到此功法");
            }
        }
        synchronized (this) {
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM wendao_job WHERE job_key = ?", Integer.class,
                    video.jobKey());
            if (exists != null && exists > 0) {
                throw ApiException.conflict("此玉简已在闻道阁中");
            }
            long now = clock.now();
            long id = jdbc.queryForObject("""
                    INSERT INTO wendao_job(job_key, bvid, page, url, method_id, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 'WAITING', ?, ?) RETURNING id
                    """, Long.class, video.jobKey(), video.bvid(), video.page(), video.url(), methodId, now, now);
            enqueue(id, null);
            return get(id);
        }
    }

    /** 重新炼化：覆盖旧笔记。 */
    public WendaoJob retry(long id) {
        synchronized (this) {
            WendaoJob job = get(id);
            if (BUSY.contains(job.status())) {
                throw ApiException.conflict("此玉简正在炼化中");
            }
            jdbc.update("UPDATE wendao_job SET status = 'WAITING', message = NULL, updated_at = ? WHERE id = ?",
                    clock.now(), id);
            enqueue(id, job.notePath());
            return get(id);
        }
    }

    public NoteView note(long id) {
        WendaoJob job = get(id);
        if (job.notePath() == null) {
            throw ApiException.notFound("此玉简尚未炼成笔记");
        }
        Path file = runner.resolve(job.notePath());
        if (!Files.isRegularFile(file)) {
            throw ApiException.notFound("笔记文件已不在原处：" + job.notePath());
        }
        try {
            return new NoteView(Files.readString(file, StandardCharsets.UTF_8), job.notePath());
        } catch (IOException e) {
            throw ApiException.notFound("读不了笔记文件：" + e.getMessage());
        }
    }

    /** 只删任务记录，本地字幕和笔记文件都留着。 */
    public void delete(long id) {
        synchronized (this) {
            WendaoJob job = get(id);
            if (BUSY.contains(job.status())) {
                throw ApiException.conflict("此玉简正在炼化中，稍后再删");
            }
            jdbc.update("DELETE FROM wendao_job WHERE id = ?", id);
        }
    }

    private void enqueue(long id, String previousNote) {
        worker.submit(() -> runner.run(id, previousNote));
    }
}
