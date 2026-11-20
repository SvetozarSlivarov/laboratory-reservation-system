package com.labreservation.catalog;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface WorkstationRepository extends JpaRepository<Workstation, Long> {
    List<Workstation> findAllByLaboratoryId(Long laboratoryId);

    @Query("select w.laboratory.id from Workstation w where w.id = :id")
    Optional<Long> findLaboratoryId(long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Workstation w where w.id = :id")
    Optional<Workstation> findForUpdate(long id);

    @Query("""
            select w from Workstation w
            where w.enabled = true and w.laboratory.enabled = true
              and (:laboratoryId is null or w.laboratory.id = :laboratoryId)
            order by w.id
            """)
    List<Workstation> findEnabled(Long laboratoryId);
}
