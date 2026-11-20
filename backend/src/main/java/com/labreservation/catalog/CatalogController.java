package com.labreservation.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.labreservation.catalog.CatalogDtos.*;

@RestController
@RequestMapping("/api")
public class CatalogController {

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/laboratories")
    List<LaboratoryView> laboratories() {
        return catalog.listLaboratories();
    }

    @GetMapping("/laboratories/{id}")
    LaboratoryView laboratory(@Positive @PathVariable long id) {
        return catalog.laboratory(id);
    }

    @GetMapping("/laboratories/{id}/workstations")
    List<WorkstationView> workstations(@Positive @PathVariable long id) {
        return catalog.listWorkstations(id);
    }

    @PostMapping("/laboratories")
    @ResponseStatus(HttpStatus.CREATED)
    LaboratoryView createLaboratory(@Valid @RequestBody NewLaboratory request) {
        return catalog.createLaboratory(request);
    }

    @PatchMapping("/laboratories/{id}")
    LaboratoryView updateLaboratory(@Positive @PathVariable long id, @Valid @RequestBody LaboratoryPatch request) {
        return catalog.updateLaboratory(id, request);
    }

    @PostMapping("/laboratories/{id}/workstations")
    @ResponseStatus(HttpStatus.CREATED)
    WorkstationView createWorkstation(@Positive @PathVariable long id, @Valid @RequestBody NewWorkstation request) {
        return catalog.createWorkstation(id, request);
    }

    @PatchMapping("/workstations/{id}")
    WorkstationView updateWorkstation(@Positive @PathVariable long id, @Valid @RequestBody WorkstationPatch request) {
        return catalog.updateWorkstation(id, request);
    }
}
