package com.xiuxian;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 每个测试类一个临时数据库（含初始数据），时钟可拨动；每个用例前清空修炼和功课。不开真实端口。 */
@SpringBootTest
@Import(TestBase.ClockConfig.class)
public abstract class TestBase {

    @TestConfiguration
    static class ClockConfig {
        @Bean
        MutableClock clock() {
            return new MutableClock(Instant.parse("2026-10-14T12:00:00Z"));
        }
    }

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        try {
            String dir = Files.createTempDirectory("xiuxian-test").toString().replace('\\', '/');
            registry.add("xiuxian.data-dir", () -> dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM session");
        jdbc.update("DELETE FROM daily_item");
        jdbc.update("DELETE FROM day_meta");
        jdbc.update("DELETE FROM daily_log");
    }

    /** 直接写一条已出关的修炼，分段为 [start, end)。 */
    protected long doneSession(long methodId, long start, long end) {
        long id = jdbc.queryForObject("""
                INSERT INTO session(method_id, source, status, started_at, ended_at, last_active_at)
                VALUES (?, 'MANUAL', 'DONE', ?, ?, ?) RETURNING id
                """, Long.class, methodId, start, end, end);
        jdbc.update("INSERT INTO segment(session_id, start_at, end_at) VALUES (?, ?, ?)", id, start, end);
        return id;
    }

    protected long methodId(String name) {
        return jdbc.queryForObject("SELECT id FROM method WHERE name = ?", Long.class, name);
    }
}
