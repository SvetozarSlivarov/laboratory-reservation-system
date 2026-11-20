package com.labreservation;

import com.labreservation.accounts.OAuthAccount;
import com.labreservation.accounts.OAuthAccountRepository;
import com.labreservation.accounts.User;
import com.labreservation.accounts.UserRepository;
import com.labreservation.accounts.UserRole;
import com.labreservation.bookings.Booking;
import com.labreservation.bookings.BookingRepository;
import com.labreservation.bookings.BookingStatus;
import com.labreservation.catalog.Laboratory;
import com.labreservation.catalog.LaboratoryRepository;
import com.labreservation.catalog.Workstation;
import com.labreservation.catalog.WorkstationRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PersistenceIT {

    private static final Instant START = Instant.parse("2040-06-01T09:00:00.123456Z");
    private static final Instant END = START.plusSeconds(3600);
    private static final Instant CREATED = START.minusSeconds(7200);

    @Autowired private UserRepository users;
    @Autowired private OAuthAccountRepository accounts;
    @Autowired private LaboratoryRepository laboratories;
    @Autowired private WorkstationRepository workstations;
    @Autowired private BookingRepository bookings;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void flywayAppliesMigrationsOnMySql84() {
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).startsWith("8.4.");
        assertThat(jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank",
                String.class)).contains("1", "2", "3");
    }

    @Test
    void roundTripsRelationshipsAndUtcInstants() {
        var first = users.save(new User("  First@example.test ", null, UserRole.USER));
        var second = users.save(new User("second@example.test", "example-hash", UserRole.ADMIN));
        var account = accounts.save(new OAuthAccount(first, "google", "Subject"));
        var lab = laboratories.save(new Laboratory("Lab", "Building A", true));
        var station = workstations.save(new Workstation(lab, "PC-01", "Java", true));
        var earlier = bookings.save(new Booking(first, station, START, END,
                BookingStatus.CANCELLED, CREATED, CREATED.plusSeconds(1)));
        var later = bookings.save(new Booking(first, station, END, END.plusSeconds(3600),
                BookingStatus.CONFIRMED, CREATED, null));
        bookings.saveAndFlush(new Booking(second, station, END.plusSeconds(3600), END.plusSeconds(7200),
                BookingStatus.CONFIRMED, CREATED, null));
        entityManager.clear();

        assertThat(users.findByEmail("first@example.test")).get().satisfies(user -> {
            assertThat(user.getId()).isEqualTo(first.getId());
            assertThat(user.getRole()).isEqualTo(UserRole.USER);
            assertThat(user.getPasswordHash()).isNull();
        });
        assertThat(accounts.findByProviderAndSubject("google", "Subject")).get().satisfies(identity -> {
            assertThat(identity.getId()).isEqualTo(account.getId());
            assertThat(identity.getUser().getId()).isEqualTo(first.getId());
        });
        assertThat(accounts.findByProviderAndSubject("google", "subject")).isEmpty();
        assertThat(workstations.findAllByLaboratoryId(lab.getId())).extracting(Workstation::getId)
                .containsExactly(station.getId());
        var history = bookings.findAllByUserIdOrderByStartsAtDescIdDesc(first.getId());
        assertThat(history).extracting(Booking::getId).containsExactly(later.getId(), earlier.getId());
        assertThat(history.get(1)).satisfies(booking -> {
            assertThat(booking.getWorkstation().getLaboratory().getId()).isEqualTo(lab.getId());
            assertThat(booking.getStartsAt()).isEqualTo(START);
            assertThat(booking.getEndsAt()).isEqualTo(END);
            assertThat(booking.getCreatedAt()).isEqualTo(CREATED);
            assertThat(booking.getCancelledAt()).isEqualTo(CREATED.plusSeconds(1));
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        });
    }

    @Test
    void rejectsDuplicateNormalizedEmail() {
        users.save(new User("unique@example.test", null, UserRole.USER));
        assertThatThrownBy(() -> users.saveAndFlush(new User(" UNIQUE@example.test ", null, UserRole.USER)))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_users_email");
    }

    @Test
    void rejectsDuplicateProviderIdentityAndSecondIdentityForSameUser() {
        var first = users.save(new User("first@example.test", null, UserRole.USER));
        var second = users.save(new User("second@example.test", null, UserRole.USER));
        accounts.saveAndFlush(new OAuthAccount(first, "google", "Subject"));

        String insert = "INSERT INTO oauth_accounts (user_id, provider, subject) VALUES (?, 'google', ?)";
        assertThatThrownBy(() -> jdbc.update(insert, second.getId(), "Subject"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_oauth_provider_subject");
        assertThatThrownBy(() -> jdbc.update(insert, first.getId(), "AnotherSubject"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_oauth_user_provider");
    }

    @Test
    void scopesWorkstationCodesToTheirLaboratory() {
        var first = laboratories.save(new Laboratory("Lab A", "Building A", true));
        var second = laboratories.save(new Laboratory("Lab B", "Building A", true));
        workstations.save(new Workstation(first, "PC-01", null, true));
        workstations.save(new Workstation(second, "PC-01", null, true));
        assertThatThrownBy(() -> workstations.saveAndFlush(new Workstation(first, "PC-01", null, true)))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_workstations_lab_code");
    }

    @Test
    void protectsReferencesAndRejectsInvalidBookingState() {
        var user = users.save(new User("booker@example.test", null, UserRole.USER));
        var lab = laboratories.save(new Laboratory("Lab", "Building A", false));
        var station = workstations.save(new Workstation(lab, "PC-01", null, false));
        bookings.saveAndFlush(new Booking(user, station, START, END, BookingStatus.CONFIRMED, CREATED, null));

        assertThatThrownBy(() -> jdbc.update("DELETE FROM workstations WHERE id = ?", station.getId()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_bookings_workstation");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id = ?", user.getId()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_bookings_user");

        String insert = """
                INSERT INTO bookings (user_id, workstation_id, starts_at, ends_at, status, created_at)
                VALUES (?, ?, '2040-06-01 09:00:00', ?, ?, '2040-06-01 07:00:00')
                """;
        assertThatThrownBy(() -> jdbc.update(insert, user.getId(), station.getId(), "2040-06-01 09:00:00", "CONFIRMED"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_bookings_interval");
        assertThatThrownBy(() -> jdbc.update(insert, user.getId(), station.getId(), "2040-06-01 10:00:00", "CANCELLED"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_bookings_cancellation");
    }
}
