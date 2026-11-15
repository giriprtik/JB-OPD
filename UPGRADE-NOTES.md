# Hardening upgrade: what changed and how to roll it out

Eight changes, one commit each, in this order.

| # | Change | Services | New migrations |
|---|--------|----------|----------------|
| 1 | 15-min access token in memory + rotating HttpOnly refresh cookie with reuse detection | auth, UI | auth `V2__refresh_tokens` |
| 2 | Server-side lab status sync (replaces per-screen polling), live queue updates over SSE | OPD, lab, UI, nginx | — |
| 3 | SYSTEM service token, Resilience4j circuit breakers + bulkheads, real HTTP timeouts, doctor cache | OPD, gateway | — |
| 4 | Send-to-lab through a transactional outbox; idempotent lab order creation | OPD, lab, UI | OPD `V3__outbox`, lab `V6__lab_order_request_id` |
| 5 | Per-visit access policy, break-glass grants, clinical read audit; lab data read only via OPD | OPD, lab, UI | OPD `V4__access_control` |
| 6 | AES-256-GCM field encryption for clinical free text; backfill/rotation job | OPD, lab | lab `V7__encrypted_columns` |
| 7 | Lab-report PDF cache (by order id) + render bulkhead | OPD | — |
| 8 | Token counter in one `INSERT … RETURNING`; no HTTP calls inside DB transactions; Hikari fail-fast | OPD, lab | — |

## New environment variables

| Variable | Service | Default | Notes |
|----------|---------|---------|-------|
| `APP_JWT_EXPIRATION_MS` | auth | `900000` (15 min) | was 8 h |
| `APP_REFRESH_IDLE_TTL` | auth | `PT2H` | sliding idle timeout |
| `APP_REFRESH_SESSION_TTL` | auth | `PT12H` | hard cap per login (one shift) |
| `APP_REFRESH_COOKIE_SECURE` | auth | `true` | browsers accept Secure cookies on `http://localhost`; `false` only for plain HTTP from another host |
| `OPD_CRYPTO_KEYS` / `LAB_CRYPTO_KEYS` | OPD / lab | demo key in compose | `k1:<openssl rand -base64 32>`; **set real keys and keep an offline copy** |
| `*_CRYPTO_ACTIVE_KEY` | OPD / lab | `k1` | key id used for new writes |
| `*_CRYPTO_ALLOW_PLAINTEXT` | OPD / lab | `false` | `true` only while migrating existing data |
| `*_CRYPTO_BACKFILL` | OPD / lab | `false` | `true` runs the encrypt/re-key job at startup |
| `APP_SCHEDULING_ENABLED` | OPD | `true` | integration tests set `false` |
| `APP_LAB_SYNC_DELAY_MS` | OPD | `15000` | lab status sync interval |
| `DB_POOL_SIZE` | OPD, lab | `20` | Hikari max pool size |

## API changes (callers outside this repo, scripts)

- `POST /api/v1/auth/login` now also sets the `jb_rt` cookie. New `POST /api/v1/auth/refresh` (cookie + header `X-JB-Refresh: 1`) and `POST /api/v1/auth/logout`.
- `POST /api/v1/opd/visits/{id}/send-to-lab` returns as soon as the order is queued; `labOrderId` on the visit stays `null` until the lab accepts it.
- `GET /api/v1/opd/visits/...` responses no longer include `diagnosis`. Use `GET /api/v1/opd/visits/{id}/clinical` (assigned doctor or break-glass only).
- New: `POST /api/v1/opd/visits/{id}/break-glass {"reason": "..."}`, `GET /api/v1/opd/stream` (SSE).
- Lab `GET /api/v1/lab/orders?ids=` and `GET /api/v1/lab/results/order/{id}` now accept LABTECH and SYSTEM only. Doctors read lab data through OPD.
- Lab `POST /api/v1/lab/orders` accepts an optional `requestId` (idempotency key); a repeat returns 200 with the same order.
- Role changes: ADMIN no longer reads clinical data or records vitals; LABTECH can no longer read OPD visits.

## Rolling out on a database that already has data

Field encryption refuses plaintext by default, so an existing volume needs one migration pass:

1. Set real `OPD_CRYPTO_KEYS` / `LAB_CRYPTO_KEYS` in `.env` and store a copy of them offline.
2. Start once with `OPD_CRYPTO_ALLOW_PLAINTEXT=true OPD_CRYPTO_BACKFILL=true` (and the same for `LAB_`). Watch the logs for `Encryption backfill: … rewrote N value(s)`.
3. Restart with both back to `false`. A second backfill run should report 0 everywhere.

For a demo database, `docker compose down -v` (wipes the data) is simpler.

Key rotation later: add `k2:<new key>` to the keys, set the active key to `k2`, run the backfill once, then drop `k1` only after it reports 0.

## Known limitations

- One shared HS256 secret signs every token, so any service holding it can mint any role (including SYSTEM). Next step: RS256 with the auth service as the only signer (JWKS).
- The SSE hub and the doctor cache are in-memory: fine for one OPD instance. With several, use Postgres `LISTEN/NOTIFY` for SSE fan-out and ShedLock for the scheduled jobs (the outbox relay is already safe with `SKIP LOCKED`).
- No UI yet for break-glass; the endpoint exists.
- Patient name and phone stay plaintext (they're searched with `LIKE`); protect them with disk/volume encryption and encrypted backups.
