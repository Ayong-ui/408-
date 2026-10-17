package com.xiuxian.daily;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 今日功课与日课模板。 */
@RestController
public class DailyController {

    public record NewItem(String name, Long pathId, String note) {
    }

    private final DailyService daily;

    public DailyController(DailyService daily) {
        this.daily = daily;
    }

    @GetMapping("/api/today")
    public DailyService.TodayView today() {
        return daily.today();
    }

    @PostMapping("/api/daily-items")
    public DailyItem add(@RequestBody NewItem in) {
        return daily.add(in.name(), in.pathId(), in.note());
    }

    @PutMapping("/api/daily-items/{id}")
    public DailyItem update(@PathVariable long id, @RequestBody Map<String, Object> patch) {
        return daily.update(id, patch);
    }

    @DeleteMapping("/api/daily-items/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        daily.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/routines")
    public List<Routine> routines() {
        return daily.routines();
    }

    @PutMapping("/api/routines")
    public List<Routine> syncRoutines(@RequestBody List<DailyService.RoutineInput> input) {
        return daily.syncRoutines(input);
    }
}
