package com.xiuxian.schedule;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 作息框架与劫数。 */
@RestController
public class ScheduleController {

    private final ScheduleService schedule;

    public ScheduleController(ScheduleService schedule) {
        this.schedule = schedule;
    }

    @GetMapping("/api/slots")
    public List<ScheduleService.Slot> slots() {
        return schedule.slots();
    }

    @PutMapping("/api/slots")
    public List<ScheduleService.Slot> syncSlots(@RequestBody List<ScheduleService.SlotInput> input) {
        return schedule.syncSlots(input);
    }

    @GetMapping("/api/countdowns")
    public List<ScheduleService.Countdown> countdowns() {
        return schedule.countdowns();
    }

    @PutMapping("/api/countdowns")
    public List<ScheduleService.Countdown> syncCountdowns(@RequestBody List<ScheduleService.CountdownInput> input) {
        return schedule.syncCountdowns(input);
    }
}
