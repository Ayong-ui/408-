package com.xiuxian.method;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 藏经阁。 */
@RestController
@RequestMapping("/api/methods")
public class MethodController {

    private final MethodService methods;

    public MethodController(MethodService methods) {
        this.methods = methods;
    }

    @GetMapping
    public List<MethodBrief> list(@RequestParam(defaultValue = "false") String archived) {
        return methods.list(archived);
    }

    @GetMapping("/{id}")
    public MethodDetail detail(@PathVariable long id) {
        return methods.detail(id);
    }

    @PostMapping
    public MethodBrief create(@RequestBody MethodService.MethodInput in) {
        return methods.create(in);
    }

    @PutMapping("/{id}")
    public MethodBrief update(@PathVariable long id, @RequestBody MethodService.MethodInput in) {
        return methods.update(id, in);
    }

    @PostMapping("/{id}/archive")
    public MethodBrief archive(@PathVariable long id) {
        return methods.setArchived(id, true);
    }

    @PostMapping("/{id}/unarchive")
    public MethodBrief unarchive(@PathVariable long id) {
        return methods.setArchived(id, false);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        methods.delete(id);
        return ResponseEntity.noContent().build();
    }
}
