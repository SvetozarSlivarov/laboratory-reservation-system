CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(254) COLLATE utf8mb4_0900_as_ci NOT NULL,
    password_hash VARCHAR(255),
    role ENUM('STUDENT', 'STAFF') NOT NULL,
    CONSTRAINT uk_users_email UNIQUE (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE oauth_accounts (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    provider VARCHAR(32) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    CONSTRAINT fk_oauth_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uk_oauth_provider_subject UNIQUE (provider, subject),
    CONSTRAINT uk_oauth_user_provider UNIQUE (user_id, provider)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE laboratories (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    location VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE workstations (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    laboratory_id BIGINT NOT NULL,
    code VARCHAR(64) NOT NULL,
    capabilities VARCHAR(500),
    enabled BOOLEAN NOT NULL,
    CONSTRAINT fk_workstations_lab FOREIGN KEY (laboratory_id) REFERENCES laboratories (id),
    CONSTRAINT uk_workstations_lab_code UNIQUE (laboratory_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE bookings (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    workstation_id BIGINT NOT NULL,
    starts_at DATETIME(6) NOT NULL,
    ends_at DATETIME(6) NOT NULL,
    status ENUM('CONFIRMED', 'CANCELLED') NOT NULL,
    created_at DATETIME(6) NOT NULL,
    cancelled_at DATETIME(6),
    CONSTRAINT fk_bookings_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_bookings_workstation FOREIGN KEY (workstation_id) REFERENCES workstations (id),
    CONSTRAINT ck_bookings_interval CHECK (starts_at < ends_at),
    CONSTRAINT ck_bookings_cancellation CHECK (
        (status = 'CONFIRMED' AND cancelled_at IS NULL) OR
        (status = 'CANCELLED' AND cancelled_at IS NOT NULL AND cancelled_at >= created_at)
    ),
    INDEX ix_bookings_user_start (user_id, starts_at, id),
    INDEX ix_bookings_workstation_status_interval (workstation_id, status, starts_at, ends_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
