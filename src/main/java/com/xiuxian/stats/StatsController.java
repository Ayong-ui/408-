package com.xiuxian.stats;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService stats;

    public StatsController(StatsService stats) {
        this.stats = stats;
    }

    @GetMapping("/distribution")
    public StatsService.Distribution distribution(@RequestParam(defaultValue = "week") String range) {
        return stats.distribution(range);
    }

    @GetMapping("/trend")
    public StatsService.Trend trend(@RequestParam(defaultValue = "7") Integer days) {
        return stats.trend(days);
    }
}
