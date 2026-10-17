package com.xiuxian.session;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 静室：入定、调息、续修、出关、补记、修炼记录。 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    public record StartInput(Long methodId, Integer targetMinutes) {
    }

    public record FinishInput(Long endAt) {
    }

    public record ManualInput(Long methodId, Long startAt, Integer minutes, String chapter, String note) {
    }

    private final SessionService sessions;

    public SessionController(SessionService sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/active")
    public ActiveView active() {
        return sessions.view();
    }

    @PostMapping("/start")
    public ActiveView start(@RequestBody StartInput in) {
        return sessions.start(in.methodId(), in.targetMinutes());
    }

    @PostMapping("/{id}/pause")
    public ActiveView pause(@PathVariable long id) {
        return sessions.pause(id);
    }

    @PostMapping("/{id}/resume")
    public ActiveView resume(@PathVariable long id) {
        return sessions.resume(id);
    }

    @PostMapping("/{id}/touch")
    public ActiveView touch(@PathVariable long id) {
        return sessions.touch(id);
    }

    @PostMapping("/{id}/finish")
    public Settlement finish(@PathVariable long id, @RequestBody(required = false) FinishInput in) {
        return sessions.finish(id, in == null ? null : in.endAt());
    }

    @PostMapping("/manual")
    public Settlement manual(@RequestBody ManualInput in) {
        return sessions.manual(in.methodId(), in.startAt(), in.minutes(), in.chapter(), in.note());
    }

    @GetMapping
    public List<SessionRecord> list(@RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to,
                                    @RequestParam(required = false) Long methodId,
                                    @RequestParam(required = false) Integer limit) {
        return sessions.list(from, to, methodId, limit);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        sessions.delete(id);
        return ResponseEntity.noContent().build();
    }
}
