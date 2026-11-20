package com.labreservation.catalog;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static com.labreservation.catalog.CatalogDtos.*;

@Service
@Transactional(readOnly = true)
public class CatalogService {

    private final LaboratoryRepository laboratories;
    private final WorkstationRepository workstations;

    public CatalogService(LaboratoryRepository laboratories, WorkstationRepository workstations) {
        this.laboratories = laboratories;
        this.workstations = workstations;
    }

    public List<LaboratoryView> listLaboratories() {
        boolean admin = isAdmin();
        return laboratories.findAllByOrderByIdAsc().stream()
                .filter(lab -> admin || lab.isEnabled()).map(LaboratoryView::from).toList();
    }

    public LaboratoryView laboratory(long id) {
        return LaboratoryView.from(visibleLaboratory(id, isAdmin()));
    }

    public List<WorkstationView> listWorkstations(long laboratoryId) {
        boolean admin = isAdmin();
        visibleLaboratory(laboratoryId, admin);
        return workstations.findAllByLaboratoryId(laboratoryId).stream()
                .filter(workstation -> admin || workstation.isEnabled())
                .map(WorkstationView::from).toList();
    }

    @Transactional
    public LaboratoryView createLaboratory(NewLaboratory request) {
        requireAdmin();
        return LaboratoryView.from(laboratories.save(
                new Laboratory(request.name().strip(), request.location().strip(), request.enabled())));
    }

    @Transactional
    public LaboratoryView updateLaboratory(long id, LaboratoryPatch request) {
        requireAdmin();
        var laboratory = laboratories.findForUpdate(id).orElseThrow(CatalogService::laboratoryNotFound);
        laboratory.update(request.name(), request.location(), request.enabled());
        return LaboratoryView.from(laboratory);
    }

    @Transactional
    public WorkstationView createWorkstation(long laboratoryId, NewWorkstation request) {
        requireAdmin();
        var laboratory = laboratories.findForUpdate(laboratoryId).orElseThrow(CatalogService::laboratoryNotFound);
        return saveWorkstation(new Workstation(laboratory, request.code().strip(),
                capabilities(request.capabilities()), request.enabled()));
    }

    @Transactional
    public WorkstationView updateWorkstation(long id, WorkstationPatch request) {
        requireAdmin();
        long laboratoryId = workstations.findLaboratoryId(id).orElseThrow(CatalogService::workstationNotFound);
        laboratories.findForUpdate(laboratoryId).orElseThrow(CatalogService::laboratoryNotFound);
        var workstation = workstations.findForUpdate(id).orElseThrow(CatalogService::workstationNotFound);
        workstation.update(request.code(), request.capabilities() == null ? null : request.capabilities().strip(),
                request.enabled());
        return saveWorkstation(workstation);
    }

    private WorkstationView saveWorkstation(Workstation workstation) {
        try {
            return WorkstationView.from(workstations.saveAndFlush(workstation));
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Workstation code already exists in this laboratory");
        }
    }

    private Laboratory visibleLaboratory(long id, boolean admin) {
        return laboratories.findById(id).filter(lab -> admin || lab.isEnabled())
                .orElseThrow(CatalogService::laboratoryNotFound);
    }

    private static String capabilities(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static void requireAdmin() {
        if (!isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator access is required");
        }
    }

    private static boolean isAdmin() {
        var actor = SecurityContextHolder.getContext().getAuthentication();
        if (actor == null || !actor.isAuthenticated() || actor instanceof AnonymousAuthenticationToken) {
            throw new AuthenticationCredentialsNotFoundException("Sign in to view the catalog");
        }
        return actor.getAuthorities().stream().anyMatch(role -> role.getAuthority().equals("ROLE_ADMIN"));
    }

    private static ResponseStatusException laboratoryNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Laboratory not found");
    }

    private static ResponseStatusException workstationNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Workstation not found");
    }
}
