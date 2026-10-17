package com.xiuxian.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** application.yml 里 xiuxian.* 的配置。 */
@ConfigurationProperties(prefix = "xiuxian")
public record XiuxianProperties(
        @DefaultValue("data") String dataDir,
        @DefaultValue("Asia/Shanghai") String zone,
        @DefaultValue List<String> allowedHosts,
        @DefaultValue Wendao wendao) {

    public record Wendao(
            @DefaultValue(".venv/Scripts/python.exe") String python,
            @DefaultValue("wendao/refine.py") String script,
            @DefaultValue("prompts/bilibili_note.md") String promptFile,
            @DefaultValue("config/bilibili-cookies.txt") String cookiesFile,
            @DefaultValue("data/notes") String notesDir,
            @DefaultValue("data/subtitles") String subtitlesDir,
            @DefaultValue("data/wendao") String jobsDir,
            @DefaultValue("claude") String claudeCommand,
            @DefaultValue List<String> claudeArgs,
            @DefaultValue("30") int timeoutMinutes) {
    }
}
