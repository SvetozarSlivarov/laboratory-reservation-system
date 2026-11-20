# Laboratory Reservation System

The project contains a Spring Boot backend, a React authentication screen, and a MySQL 8.4 service for local development.

## Prerequisites

- JDK 25 with `JAVA_HOME` pointing to it
- Node.js 22.12 or later on a supported release line and npm
- Docker with the Compose plugin, or an existing MySQL 8.4 instance

## Start the database

From the repository root in PowerShell, copy the example and replace the two placeholder passwords in `.env` with distinct local values.

```powershell
Copy-Item .env.example .env
docker compose up -d --wait mysql
```

If port 3306 is already in use, change `DB_PORT` in `.env` before starting Compose. With an existing MySQL instance, create the database and a dedicated user with privileges on that schema, then set the matching values in `.env`.

## Start the backend

From the repository root in a new PowerShell terminal, load the local database settings and run the Maven Wrapper.

```powershell
Get-Content .env | ForEach-Object {
    if ($_ -match '^((?:DB_|GOOGLE_)[A-Z_]+|SESSION_COOKIE_SECURE)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
}
Set-Location backend
.\mvnw.cmd spring-boot:run
```

Flyway applies the schema migrations at startup and Hibernate validates the schema. Visit [backend health](http://localhost:8080/actuator/health) and look for `{"status":"UP"}`.

## Start the frontend

From the repository root in a second terminal:

```powershell
Set-Location frontend
npm ci
npm run dev
```

Open the [Vite development server](http://localhost:5173). The health link uses Vite's proxy to reach the backend. The proxy also forwards `/api` to port 8080.

## Authentication

Session cookies are Secure and HttpOnly with SameSite=Lax. Serve the frontend and API on the same HTTPS origin outside development. For the plain HTTP Vite server on localhost, set `SESSION_COOKIE_SECURE=false` in your local `.env`. The browser stores the session cookie and the UI keeps CSRF tokens in memory.

1. Fetch `GET /api/auth/csrf`, then send its token in the returned header for registration, login and logout.
2. Register using JSON `email` and `password` fields. Passwords must be 12–128 characters and are hashed with Spring Security PBKDF2. Registration assigns `USER`. Operators provision `ADMIN` out of band.
3. Sign in with form-encoded `email` and `password` at `POST /api/auth/login`. Spring Security rotates the session ID after authentication. Read the current account at `GET /api/auth/me`. Fetch a fresh CSRF token after sign-in before the next POST.
4. Sign out with `POST /api/auth/logout`. Spring Security invalidates the session and clears the session cookie.

To enable Google sign-in locally, configure a Google OAuth web client with the redirect URI `http://localhost:5173/login/oauth2/code/google`. Set `GOOGLE_LOGIN_ENABLED=true`, `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` in the backend environment. Use the Vite URL consistently when starting login and receiving the callback. Google uses Spring Security OpenID Connect and establishes the same application session as local login.

A verified Google email can create a new account when the email is unused. To link an existing account, sign in to it first and choose **Link Google account**. The verified Google email must match the signed-in account. Once linked, the Google subject identifies the same application account on future sign-ins. An anonymous attempt with an existing email gets a conflict response.

## Build

Run `.\mvnw.cmd verify` from `backend/` and `npm run build` from `frontend/`. On Linux and macOS, use `sh ./mvnw` in place of `.\mvnw.cmd` and export the database variables into the backend shell.

## Database structure

Flyway runs V1 to initialize migration history, V2 to create the domain tables, and V3 to migrate the original STUDENT and STAFF roles to USER and ADMIN. Hibernate validates the mappings at startup. Each table uses an auto-incrementing BIGINT primary key and InnoDB foreign keys.

| Table | Design |
| --- | --- |
| `users` | Stores one row per person. The entity trims and lowercases email and the database enforces case-insensitive uniqueness. The password hash is optional for OAuth-only users. The role is a named enum. |
| `oauth_accounts` | References a user. Provider and subject identify the external account, with case-sensitive comparison to preserve opaque subjects. A user can link one identity per provider. |
| `laboratories` | Stores a name, location, and enabled flag. The flag allows staff to withdraw a lab from availability while preserving its references. |
| `workstations` | References a laboratory. Its code is case-sensitive and unique within that lab. Capabilities are optional text and an enabled flag supports withdrawal. |
| `bookings` | References one user and one workstation. Each pair can have several bookings over time. Records retain the booking interval, status and creation time, plus a cancellation time when cancelled. Checks enforce a positive interval and a cancellation time consistent with status and creation time. |

Relationships are mapped from child to parent with lazy loading. Foreign keys restrict deletion of referenced records to preserve history. Booking timestamps round-trip as Java `Instant` values using UTC JDBC configuration and MySQL `DATETIME(6)` for microsecond precision. A user/start index supports booking history, and a workstation/status/interval index supports availability and conflict lookups. The overlap rule belongs to the future booking service's transaction and locking protocol.

Run `.\mvnw.cmd verify` with the database settings loaded and a disposable MySQL 8.4 schema. The persistence integration tests verify Flyway migration history, Hibernate mappings, UTC round-trips, and database constraints. Maven writes JaCoCo coverage to `backend/target/site/jacoco/`.
