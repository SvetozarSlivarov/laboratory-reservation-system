package com.labreservation.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkstationRepository extends JpaRepository<Workstation, Long> {
    List<Workstation> findAllByLaboratoryId(Long laboratoryId);
}
