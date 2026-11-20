# Repository instructions

Read [SPEC.md](SPEC.md) before changing behavior. The current specification is provisional until the university requirements attachment has been reviewed. Preserve acceptance criterion IDs and update the relevant criteria when requirements change. Keep code, comments and documentation in English.

## Architecture

- Use Java 25, Spring Boot 3.5.16, Maven, MySQL 8.4, Spring Data JPA, Hibernate and Flyway in `backend/`. Keep one React and TypeScript frontend in `frontend/`.
- Organize Java packages by feature. Follow controller or MCP adapter → concrete service → repository. Use constructor injection and keep classes small and cohesive. Extract interfaces when callers genuinely need multiple implementations.
- Keep authorization, ownership checks, business validation and transactions in services. Obtain the actor from trusted authentication context. Use DTOs at transport boundaries and records where suitable. Keep JPA entities internal to the application.
- Apply schema changes through new Flyway migrations and validate the schema with Hibernate. Preserve the booking lock order across all write paths and use UTC instants for persistence.
- Keep Google, Gemini, database and MCP credentials in external configuration. Keep Gemini prompts and outputs bounded and use the fallback defined in SPEC.md. MCP tools reuse application services.

## Commands

Run backend commands from `backend/` with Java 25 selected and MySQL configured as needed. See [README.md](README.md) for local database setup. The examples use PowerShell.

| Task | Command |
| --- | --- |
| Start backend | `.\mvnw.cmd spring-boot:run` |
| Run backend unit tests | `.\mvnw.cmd test` |
| Run unit and integration tests and generate coverage | `.\mvnw.cmd verify` |
| Install frontend dependencies from `frontend/` | `npm ci` |
| Start frontend from `frontend/` | `npm run dev` |
| Type-check and build frontend from `frontend/` | `npm run build` |

## Testing and delivery

- Use JUnit 5 and Mockito for service rules. Use Spring integration tests and a disposable MySQL 8.4 database for migrations, authorization and concurrent booking tests. Use independent transactions for concurrency cases and a controlled clock for time rules.
- Stub Gemini and Google in automated tests. Verify real provider flows in the configured demo. Exercise MCP protocol discovery, invocation and access checks with a client.
- Configure Maven Surefire for unit tests and Failsafe for integration tests. Generate JaCoCo HTML coverage under `backend/target/site/jacoco/` during `verify`. Apply a numerical threshold if the coursework contract specifies one.
- Keep changes within the requested scope. Add focused regression tests for changed acceptance behavior. Report commands run, their results and any verification still pending.
