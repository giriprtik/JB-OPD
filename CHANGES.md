# Changes in this revision

## Fixed: things that were broken

| Area | Problem | Fix |
|---|---|---|
| Registration | `PatientService` gave the first visit a token of `count(visits today) + 1`, while returning-patient visits used the atomic counter. Mixing the two hit the `(visit_date, token_number)` unique constraint. | Both paths go through `VisitService.openVisit`, which uses the row-locked `INSERT … ON CONFLICT` counter, inside the caller's transaction. |
| Registration | Every patient was assigned to a hard-coded username `doctor`, with complaint "Initial Check-in" and priority NORMAL. | The form captures chief complaint, priority and a doctor picked from `/api/v1/auth/doctors`; OPD validates the doctor against the auth service. |
| Registration | A duplicate phone number threw `IllegalStateException`, which became an HTTP 500. | 409 with the existing patient's name and MRN, plus a *Returning patient* search to open a new visit instead. |
| Navigation | The *Triage Panel* menu linked to `/triage`, which had no route, so it bounced to the login page. | Routed to the triage worklist. |
| Lab orders | OPD's `LabOrderRequest` record named its 4th field `authorization`, so the clinical history was sent under the wrong JSON key and dropped by the lab. | Field renamed to `clinicalHistory`; the doctor can also add a note. |
| Lab | `ResponseStatusException` (e.g. "order already completed") fell through to the generic handler and came back as a 500. | All services map it (and validation, not-found, conflicts) to problem+json with the right status. |
| Lab | `PATCH /orders/{id}/status` accepted any status, including COMPLETED with no results. | Only cancelling an open order; collection is per test and COMPLETED comes only from result submission. |
| Doctor | `GET /visits/{id}` and `POST /visits` serialized the JPA entity with a lazy `patient` proxy. | Both return the `VisitQueueItem` projection. |
| Auth | Default role `PATIENT` violated the table's CHECK constraint; `/me/doctor-id` read a `Jwt` principal that the custom filter never produced (NPE). | Role is required and validated; the broken endpoint was removed. |
| Gateway | `pom.xml` used invalid XML namespaces (`http://apache.org`). | Standard Maven POM namespace. |
| Docker | Auth and lab images declared `EXPOSE 8080`; the OPD image tagged its jar as `jbikas-auth-service`. | One consistent Dockerfile pattern per service, correct ports and artifact names, non-root user. |

## Security hardening

- **No public sign-up.** `POST /api/v1/auth/register` now needs ADMIN (before, anyone could create an ADMIN account). Admins create staff from the new *Staff* screen.
- **Doctors only see and act on their own patients.** The queue comes from the JWT subject (`/visits/doctor/queue`) rather than a `{doctorUsername}` path parameter anyone could change; start/send-to-lab/conclude return 403 for another doctor's patient.
- The internal `X-Internal-Secret` header is stripped at the gateway and compared in constant time.
- Login gives the same error for an unknown user, wrong password or disabled account.
- TRACE/DEBUG security logging and `System.out` token dumps were removed (they logged roles on every request).
- Secrets move to `.env` (`.env.example` provided) with demo-only defaults in compose.

## New capabilities

- **Demo data seeder:** one account per role on first boot (`Demo@1234`), switchable with `APP_SEED_DEMO_USERS`.
- **Returning-patient search** by MRN, phone or name.
- **Audit timeline:** every status change writes a `visit_events` row (registration, vitals, consultation, lab order, results released, sign-off), exposed at `/visits/{id}/events`.
- **Doctor worklist shows latest vitals and allergies** (one `LATERAL` join, no N+1) and orders emergencies first.
- **Lab catalogue endpoint:** the doctor's test list comes from `/api/v1/lab/tests` with prices instead of a hard-coded array.
- **Patient name on lab orders** so the lab worklist is readable (`V4__lab_order_patient_name.sql`).
- Vitals can be re-taken; low SpO₂ is flagged as well as fever.

## UI redesign

- Clean clinical light theme: one teal accent, status colours reserved for clinical meaning, IBM Plex Sans/Mono bundled locally (no CDN), lucide icons instead of emoji.
- Shared design-system primitives (`components/ui`) and design tokens in `index.css`; no more repeated 200-character class strings.
- Login page with one-click demo accounts; live clock in Nepal time.
- Registration: today's flow stats, new/returning tabs, age-only entry for patients without a known DOB, token confirmation with *Record vitals now*.
- Triage: large vital inputs with live flags (Fever, Tachycardia, Hypoxia…).
- Doctor: queue tabs with counts, allergy banner, vitals tiles, lab results with abnormal rows highlighted, test ordering with cost total, sign-off, timeline.
- Lab: worklist with *Collect sample*, result entry with live High/Low flags, *Reported* tab.
- Worklists refresh every 15–20 s (paused when the tab is hidden) without flashing spinners.
- Same-origin API calls (`/api` via nginx or the Vite proxy) instead of a hard-coded `http://localhost:8080`.
- Expired tokens are detected on load and on 401, and the user is sent to the login page with a message.

## Per-test sample collection

An order with several tests (for example CBC and urinalysis) needs a separate sample for each. The technician now clicks a test and collects its sample individually.

- **New table** `lab_sample_collections` (`V5__lab_sample_collections.sql`): one row per collected test, with who collected it and when. It has a unique key on `(order_id, test_code)` and a foreign key to `lab_order_tests`, so a sample can't be recorded twice or for a test that wasn't ordered. Orders already past collection are backfilled as fully collected.
- **New endpoint** `POST /api/v1/lab/orders/{id}/tests/{testCode}/collect`. The order stays `PLACED` while any sample is pending and becomes `SAMPLE_COLLECTED` when the last one is collected.
- `PATCH …/status` no longer accepts `SAMPLE_COLLECTED`; it is for cancelling only.
- **Results need every sample.** Result submission returns 409 and names the pending tests until all are collected.
- Lab order responses include `samples[]` (test code, collected, collected by, collected at), batch-loaded in one query for the whole worklist.
- **UI:** on a multi-test order, each test is a selectable chip. *Collect sample* stays disabled until a test is picked and then reads *Collect CBC sample*. Collected tests turn green with a tick (hover shows who and when), the badge reads *1 of 2 collected*, and *Enter results* stays locked until every sample is in. Single-test orders skip the picking step.

## Database migrations

Existing migrations are untouched (Flyway checksums stay valid). Added:
- `jb-opd-service/V2__visit_doctor_username.sql`: `visits.assigned_doctor_username` + index
- `jb-lab-service/V4__lab_order_patient_name.sql`: `lab_orders.patient_name`
- `jb-lab-service/V5__lab_sample_collections.sql`: per-test sample collection (with backfill)

Visits created before this change have no `assigned_doctor_username`, so they won't appear in any doctor's queue. For the demo, start clean with `docker compose down -v`.

## Housekeeping

- Removed committed build output (`target/`), `.DS_Store` files, the unused `init-scripts/`, the empty `pd-postgres-db` file and Vite boilerplate assets.
- `.gitignore` covers `target/`, `node_modules/`, `dist/` and `.env`.

## Automated tests and CI

The project had no tests. Added:

- **OPD service:** `VisitServiceTest` (token issuing, one open visit per patient, allowed status moves, send-to-lab ownership/status/concurrency), `TriageServiceTest` (fever / low-SpO2 alerts at the exact thresholds, first vitals move REGISTERED → TRIAGED), `PatientServiceTest` (MRN format, field normalisation, duplicate phone 409), and `OpdWorkflowIT`: registration → triage → doctor queue → consultation → lab on real PostgreSQL, with the role rules, 401s for forged/expired tokens, and 40 concurrent token requests that must all get distinct numbers.
- **Lab service:** `LabOrderServiceTest` (per-test collection, PLACED until the last sample, duplicates, manual status rules), `LabResultEntryServiceTest` (results blocked until every sample is in, reference range text, inclusive bounds, non-numeric values, manual flag), and `LabWorkflowIT` (the whole lab journey over HTTP, role rules, and the V5 unique + composite foreign-key constraints).
- **Auth service:** `AuthServiceTest`, `JwtTokenProviderTest` (tampered, foreign-key, expired and too-short-secret tokens) and `AuthApiIT` (seeded logins, admin-only registration, doctor picker, the internal `X-Internal-Secret` lookup, unknown-account tokens).
- **Gateway:** `GatewayRoutingTest` starts the real gateway in front of stub backends to check routing and that an outside `X-Internal-Secret` header never reaches the auth service.
- **Every service:** `MigrationFilesTest` fails the build if a Flyway migration is empty, zero-filled (NUL bytes), has no SQL statement, or breaks the V1..Vn sequence. This is the exact failure that hid the OPD tables earlier.
- **UI:** Vitest + Testing Library (42 tests): API client, session restore, `RequireRole`, the login form, the lab worklist's pick-a-test-then-collect flow, and the vital-sign thresholds (kept in step with the OPD service).
- **CI:** `.github/workflows/ci.yml` (backend matrix, frontend, then a Docker Compose smoke test using `scripts/smoke-test.sh`).
- **Build:** Testcontainers + `maven-failsafe-plugin` in the three service POMs, `spring-boot-starter-test` in the gateway, Vitest dev dependencies and `test` / `coverage` / `typecheck` scripts in the UI.

Integration tests use `spring.flyway.baseline-on-migrate=false`, so a missing or broken V1 fails the tests instead of being silently baselined.

**Verification status:** the 42 UI tests pass, and lint, type-check and build are clean. The Java tests were written against the real classes and parse cleanly, but **they have not been compiled or run** because this environment cannot reach Maven Central. Run `mvn verify` in each service once (or push and let CI do it) and fix anything it reports before relying on them.

**Next step for CD (not done):** decide where it deploys (a VM with Docker Compose, a container platform, Kubernetes…). The usual next job publishes the five images to GitHub Container Registry on pushes to `main`, then deploys them with environment secrets for `APP_JWT_SECRET` and `INTERNAL_SERVICES_SECRET`.

## How this revision was verified

- **UI:** `tsc -b`, `oxlint` and `vite build` all pass. The full nurse → doctor → lab → doctor flow was driven end to end in headless Chromium against a mock API that mirrors the services' JSON contracts, with no console errors.
- **Database:** every Flyway migration was applied in order to a real PostgreSQL instance, and the new worklist SQL (including the `LATERAL` latest-vitals join and the token upsert) was run against seeded rows.
- **Java:** the build environment had no Maven Central access, so **the Spring services were not compiled or run here.** The changes were reviewed by hand and parse cleanly with `javac`. Run `docker compose up --build` and walk the demo script once before presenting.
