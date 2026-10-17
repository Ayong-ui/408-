package com.xiuxian.path;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/paths")
public class PathController {

    public record PathInput(String name, String color, Boolean core, Boolean body, Integer sort) {
    }

    private final PathService paths;

    public PathController(PathService paths) {
        this.paths = paths;
    }

    @GetMapping
    public List<PathInfo> list() {
        return paths.list();
    }

    @PostMapping
    public PathInfo create(@RequestBody PathInput in) {
        return paths.create(in.name(), in.color(), in.core(), in.body());
    }

    @PutMapping("/{id}")
    public PathInfo update(@PathVariable long id, @RequestBody PathInput in) {
        return paths.update(id, in.name(), in.color(), in.core(), in.body(), in.sort());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        paths.delete(id);
        return ResponseEntity.noContent().build();
    }
}
