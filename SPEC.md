# Laboratory Reservation System

This is a provisional specification based on the project brief. Review the complete university requirements attachment and map each requirement to an acceptance criterion before treating this document as the coursework contract.

## Scope and roles

Students sign up or sign in, browse the catalog, find an available slot, book it, and view or cancel their own bookings. Staff sign in and manage laboratories and workstations. Public visitors can access sign-in and registration. Students have the USER role. Staff have the ADMIN role and are provisioned by an operator. Registration and first-time Google sign-in assign USER. The React UI needs authentication, catalog and availability, my bookings, and staff catalog screens.

## Data model

| Entity | Fields and constraints | Relationships |
| --- | --- | --- |
| User | ID, unique normalized email, optional password hash, role | One user can have many bookings and at most one OAuth account per provider |
| OAuthAccount | ID, provider, subject unique within its provider | Belongs to one user |
| Laboratory | ID, name, location, enabled | One laboratory can have many workstations |
| Workstation | ID, code unique within its laboratory, optional plain-text capabilities, enabled | Belongs to one laboratory and can have many bookings |
| Booking | ID, start, end, status (CONFIRMED or CANCELLED), created time, optional cancellation time | Belongs to one user and one workstation |

Foreign keys preserve booking history. Disable catalog entries to withdraw them from availability. A disabled laboratory disables availability for all its workstations. Existing bookings remain visible and future bookings can still be cancelled by their owners.

## Booking rules

- Proposed defaults are 60-minute slots starting on the hour in Europe/Sofia, with a rolling seven-day search and booking horizon. Store instants in UTC, exchange ISO 8601 timestamps with offsets, and use an injected clock. Generate slots from valid local hour boundaries, skipping nonexistent times and distinguishing repeated times by offset.
- Confirm only a future slot that satisfies the same rules used by availability and belongs to an enabled workstation in an enabled laboratory. Adjacent intervals may touch. An overlap exists when the existing start precedes the requested end and the existing end follows the requested start.
- Create a booking in one transaction. Acquire pessimistic locks on the laboratory and workstation in that order, then use a current read for conflict checking before insertion. Catalog enablement changes use the same lock order for affected rows. Cancellation uses the same order before updating the booking. This favors simple concurrency guarantees over parallel writes within a laboratory.
- Only the owner can cancel a future booking. Repeating a successful cancellation returns the cancelled record. Availability is advisory until booking commits. A conflict returns HTTP 409.

## REST API

Use JSON request and response DTOs, with Bean Validation and a consistent problem response. Local login uses Spring Security's form-encoded request. Return 400 for invalid input, 401 for missing authentication, 403 for role violations, and 404 for an unknown resource or a booking belonging to another user. Creation returns 201.

| Method and path | Access and behavior |
| --- | --- |
| GET /api/auth/csrf | Obtain the CSRF token for browser requests |
| POST /api/auth/register | Public registration using email and password |
| POST /api/auth/login | Local session login using form-encoded email and password |
| GET /oauth2/authorization/google | Begin Google sign-in using Spring Security's callback |
| GET /api/auth/me | Return the current user's ID, email and role |
| POST /api/auth/logout | End the current session |
| GET /api/laboratories | Students see enabled entries, staff see all |
| GET /api/laboratories/{id} | Students see enabled laboratories, staff can also see disabled laboratories |
| GET /api/laboratories/{id}/workstations | Students see enabled entries in enabled labs, staff see all entries in the lab |
| POST /api/laboratories | Staff create a laboratory |
| PATCH /api/laboratories/{id} | Staff edit name, location or enabled status |
| POST /api/laboratories/{id}/workstations | Staff create a workstation |
| PATCH /api/workstations/{id} | Staff edit code, capabilities or enabled status |
| GET /api/availability?from=…&to=…&laboratoryId=… | Students search free slots within a bounded window, optionally by laboratory. Each result includes the workstation and slot interval. |
| POST /api/bookings | Students submit workstationId and startsAt. The server derives the owner and end. An optional endsAt must match the derived end. |
| GET /api/bookings/me | Students list their own current and past bookings, including cancellations |
| GET /api/bookings/{id} | Students retrieve their own booking |
| POST /api/bookings/{id}/cancel | Students cancel their own future booking |
| POST /api/recommendations | Students request a slot recommendation using the availability filters and an optional plain-text preference |

Use same-origin cookie sessions and CSRF protection for browser writes. Hash local passwords with Spring Security. Identify Google accounts by provider subject and require a verified email for first login. To link Google to an existing account, first sign in to that account and then complete Google sign-in with a matching verified email. Reject anonymous email collisions. Keep credentials outside source control.

Catalog creation requires an explicit enabled flag. PATCH leaves omitted and null fields unchanged. Send an empty capabilities string to clear workstation capabilities.

## Gemini workstation recommendation

The recommendation helps a student choose among available slots using preferences such as a workstation with particular software. Query availability first and order candidates by start instant and workstation ID. Send at most 20 candidates with request-local IDs, slot times, laboratory names and workstation capabilities, plus an optional preference capped at 250 characters. Treat capabilities and preference text as untrusted data. The UI asks users to provide equipment and timing preferences only. Exclude account identifiers, email, booking history and credentials from the prompt. Use one server-side call to a configurable Gemini model supporting structured JSON output.

Require a JSON object containing a candidate ID from the submitted set and a plain-text explanation of up to 240 characters. Reject malformed JSON, missing or unknown IDs, and unexpected fields. Return workstation and time data from a fresh availability read. Use the same sort order to choose the earliest available slot if Gemini is disabled, times out after three seconds total, fails, returns invalid output, or selects a slot that became unavailable. The response source is GEMINI, FALLBACK or NO_AVAILABILITY. The student can prefill the booking form from the result and confirms the booking separately.

## MCP

Expose an opt-in MCP endpoint within the Spring Boot process using stateless Streamable HTTP. Authenticate each request using the existing application session cookie and require a session CSRF token for POST requests. Accept only USER sessions and reject ADMIN sessions. Require TLS outside loopback and reject browser origins unless explicitly configured. Publish explicit input and output schemas. Each tool calls the same application service method as its REST counterpart.

| Tool | Input | Result |
| --- | --- | --- |
| `list_laboratories` | Empty object | Enabled laboratory summaries |
| `list_available_workstations` | From, to, optional laboratory ID | Bookable workstation and slot summaries |
| `my_bookings` | Empty object | Current user's bookings including cancellation history |
| `create_booking` | Workstation ID, start, optional end | Confirmed booking subject to availability and conflict checks |
| `cancel_booking` | Booking ID | Cancelled booking owned by the current user |

## Acceptance criteria

| ID | Evidence required |
| --- | --- |
| AC-1 | Maven builds and runs the backend on Java 25 with Spring Boot 3.5.16. React and TypeScript build successfully. Flyway initializes a clean MySQL 8.4 database, and Hibernate validates its schema. |
| AC-2 | A student registers and signs in with a hashed local password or signs in through Google. Logout ends the session. An anonymous email collision is rejected. A signed-in user can link a Google identity with a matching verified email. |
| AC-3 | Service tests demonstrate that students cannot manage the catalog, staff can create and edit both catalog entities, and public callers cannot use authenticated features. |
| AC-4 | The UI shows enabled laboratories and workstations to students and lets them find available slots. The service validates time boundaries, disabled entries and booking horizon. |
| AC-5 | A student books multiple slots over time, and multiple students can book the same workstation at different times. Adjacent bookings succeed. A MySQL integration test using separate concurrent transactions confirms that only one overlapping request commits. |
| AC-6 | Students can view and cancel their own future bookings. Another user's booking cannot be read or cancelled. Successful cancellation retains history and releases its slot. |
| AC-7 | A valid Gemini selection returns an available candidate. Tests cover empty availability, invalid output, timeout, provider failure, disabled configuration and a slot taken during inference. Tests assert deterministic fallback and verify that model calls create no bookings. A configured demo exercises a live Gemini call. |
| AC-8 | An MCP client initializes, discovers and invokes all five tools. Tests cover invalid arguments, USER session enforcement, and shared service behavior, including ownership and booking conflicts. |
| AC-9 | JUnit 5 and Mockito cover business rules. Spring integration tests exercise security, Flyway and MySQL persistence. Maven verify generates a JaCoCo report. |
| AC-10 | An English setup guide documents configuration, staff provisioning, run and test commands, and a demo walkthrough covering both roles, Gemini and MCP. |

## Architecture and folders

One Spring Boot process owns sessions, REST, MCP and persistence. REST controllers and MCP adapters call application services, which enforce authorization and business rules and use Spring Data repositories. Services own transaction boundaries. The Gemini adapter belongs to the recommendations feature and receives only candidate data. One React frontend consumes REST.

- `backend/src/main/java/` contains feature packages for accounts, catalog, bookings and recommendations, with MCP and security adapters.
- `backend/src/main/resources/db/migration/` contains Flyway migrations. Backend tests mirror feature packages under `backend/src/test/java/`.
- `frontend/src/` contains feature screens and a shared typed API client.

Spring Boot 3.5.16 is a stable release with Java 25 compatibility documented by Spring. [Spring Boot 3.5 system requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html)

## Risks and open decisions

- The university attachment is pending. Exact rubric obligations, coverage thresholds, required CRUD behavior, integration demonstrations and submission artifacts require review before sign-off.
- Slot length, horizon, operating hours, staff booking privileges and open student registration are policy choices. The defaults above assume any student account can book, staff manage inventory, and slots run throughout the day.
- Google and Gemini demos need valid external credentials. Validate callbacks, Gemini structured output and client compatibility with the MCP transport early in implementation.
- Booking and catalog changes share a locking protocol. Test on MySQL 8.4 because the concurrency guarantee depends on its transactional behavior.
