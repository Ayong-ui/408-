package com.xiuxian.log;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 修行日志与传音玉简。 */
@RestController
@RequestMapping("/api/logs")
public class LogController {

    public record ContentInput(String content) {
    }

    public record AppendInput(String text) {
    }

    private final LogService logs;

    public LogController(LogService logs) {
        this.logs = logs;
    }

    @GetMapping
    public List<LogService.LogEntry> list(@RequestParam(required = false) Integer limit) {
        return logs.list(limit);
    }

    @GetMapping("/{day}")
    public LogService.LogEntry get(@PathVariable String day) {
        return logs.get(day);
    }

    @PutMapping("/{day}")
    public LogService.LogEntry put(@PathVariable String day, @RequestBody ContentInput in) {
        return logs.put(day, in.content());
    }

    @PostMapping("/append")
    public LogService.LogEntry append(@RequestBody AppendInput in) {
        return logs.append(in.text());
    }
}
