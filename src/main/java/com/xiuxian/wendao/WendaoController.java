package com.xiuxian.wendao;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 闻道阁：B 站字幕炼化成本地笔记。 */
@RestController
@RequestMapping("/api/wendao")
public class WendaoController {

    public record NewJob(String url, Long methodId) {
    }

    private final WendaoService wendao;

    public WendaoController(WendaoService wendao) {
        this.wendao = wendao;
    }

    @GetMapping("/status")
    public Map<String, Boolean> status() {
        return wendao.status();
    }

    @GetMapping("/jobs")
    public List<WendaoJob> list() {
        return wendao.list();
    }

    @PostMapping("/jobs")
    public WendaoJob create(@RequestBody NewJob in) {
        return wendao.create(in.url(), in.methodId());
    }

    @PostMapping("/jobs/{id}/retry")
    public WendaoJob retry(@PathVariable long id) {
        return wendao.retry(id);
    }

    @GetMapping("/jobs/{id}/note")
    public WendaoService.NoteView note(@PathVariable long id) {
        return wendao.note(id);
    }

    @DeleteMapping("/jobs/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        wendao.delete(id);
        return ResponseEntity.noContent().build();
    }
}
