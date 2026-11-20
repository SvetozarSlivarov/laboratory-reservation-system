package com.labreservation.bookings;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    List<Booking> findAllByUserIdOrderByStartsAtDescIdDesc(Long userId);

    @Query("select b.workstation.id from Booking b where b.id = :id and b.user.id = :userId")
    Optional<Long> findOwnedWorkstationId(long id, long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> findForUpdate(long id);

    @Query(value = """
            SELECT id FROM bookings
            WHERE workstation_id = :workstationId AND status = 'CONFIRMED'
              AND starts_at < :end AND ends_at > :start
            LIMIT 1 FOR UPDATE
            """, nativeQuery = true)
    Optional<Long> findConflictForUpdate(long workstationId, Instant start, Instant end);

    @Query("""
            select b from Booking b
            where b.workstation.id in :workstationIds and b.status = CONFIRMED
              and b.startsAt < :end and b.endsAt > :start
            """)
    List<Booking> findConflicts(List<Long> workstationIds, Instant start, Instant end);
}
