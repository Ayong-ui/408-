package com.xiuxian.observatory;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 观星台：传送阵与情报簿。 */
@RestController
public class ObservatoryController {

    private final ObservatoryService observatory;

    public ObservatoryController(ObservatoryService observatory) {
        this.observatory = observatory;
    }

    @GetMapping("/api/portals")
    public List<ObservatoryService.Portal> portals() {
        return observatory.portals();
    }

    @PutMapping("/api/portals")
    public List<ObservatoryService.Portal> syncPortals(@RequestBody List<ObservatoryService.PortalInput> input) {
        return observatory.syncPortals(input);
    }

    @GetMapping("/api/intel")
    public List<ObservatoryService.Intel> intel(@RequestParam(required = false) String status) {
        return observatory.intel(status);
    }

    @PostMapping("/api/intel")
    public ObservatoryService.Intel create(@RequestBody ObservatoryService.IntelInput in) {
        return observatory.createIntel(in);
    }

    @PutMapping("/api/intel/{id}")
    public ObservatoryService.Intel update(@PathVariable long id, @RequestBody ObservatoryService.IntelInput in) {
        return observatory.updateIntel(id, in);
    }

    @DeleteMapping("/api/intel/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        observatory.deleteIntel(id);
        return ResponseEntity.noContent().build();
    }
}
