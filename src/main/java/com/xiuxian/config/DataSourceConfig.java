package com.xiuxian.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/** 单人本地使用：SQLite 一个文件，复制即备份。 */
@Configuration
public class DataSourceConfig {

    @Bean
    public DataSource dataSource(XiuxianProperties props) throws IOException {
        Path dir = Path.of(props.dataDir()).toAbsolutePath();
        Files.createDirectories(dir);

        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.setBusyTimeout(5000);
        // 事务一开始就拿写锁：WAL 下"先读后写"的延迟事务碰上别人刚提交会直接 SQLITE_BUSY，不会等待
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);

        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + dir.resolve("xiuxian.db"));
        return ds;
    }
}
