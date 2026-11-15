# CLAUDE.md

## Context
**Social Flux** is a social network: users sign up, follow each other (private accounts require approval), publish posts with
text/image, reply to posts (comments are posts with a parent), like, search users and receive notifications.

The project is in the middle of a **gradual migration, one domain at a time**, from a legacy monolith (Javalin + MySQL) to
Spring Boot services (each with its own PostgreSQL database), talking through RabbitMQ events and sitting behind a Caddy gateway.
Identity, Profile and Relations (social graph) are already migrated; Posts, Likes, Images and Notifications are still in the legacy backend.

## Stack
- **Frontend:** React 18 + TypeScript (strict) + Vite · **Bootstrap 5** (+ `data-bs-*` JS) · Formik + Yup · axios · react-router 6 · ionicons
- **New services:** Spring Boot 4 · Java 21 · Spring Security · JPA + Flyway · PostgreSQL 17 · RabbitMQ (outbox/inbox) · JWT RS256
- **Legacy backend:** Javalin 6 · Java 17/21 · plain JDBC + HikariCP · MySQL 8.4
- **Infra:** Docker Compose · Caddy 2 (gateway, same origin) · Mailpit (dev SMTP) · RustFS (S3-compatible media storage, private buckets)

## Structure (monorepo)
| Folder | What it is | Port (host) |
| --- | --- | --- |
| `FrontEnd/` | SPA. Talks only to its own origin (`/auth`, `/api`, `/media`); Vite proxies to the gateway in dev | 5173 |
| `authentication_service/` | Identity & access: register, login, sliding session (HttpOnly cookies), password reset, e-mail verification, JWKS, gateway `forward_auth` | 8081 |
| `profile_service/` | User profile: profile data, search, avatar, privacy; JWT resource server; publishes `profile.*` events and projects them into the legacy `usuario` table | 8082 |
| `social_graph_service/` | Social graph: follow/unfollow, followers/following, follow requests for private accounts; JWT resource server; consumes `profile.*`, publishes `follow.*`, projects accepted relations into the legacy `relacao` table. **In compose, the gateway (`/api/relacoes*`) and the frontend; the legacy `/api/relacao` routes are now unused** | 8083 |
| `post_service/` | Posts: posts, replies (posts with a parent), likes and post media (images and GIFs in RustFS); JWT resource server; keeps local copies of `profile.*` (authors) and `follow.*` (accepted edges) to enforce private-account visibility server-side; publishes `post.*`; cursor-paginated feed with embedded counters | 8084 |
| `libs/socialflux-events/` | Shared event library (transactional outbox, relay, idempotent inbox, RabbitMQ topology) used by every Spring service; auto-configured, `socialflux.events.*` properties | — |
| `libs/socialflux-media/` | Shared media library (PNG/JPEG/WebP validation and re-encoding with EXIF orientation, GIF sanitizer, S3/RustFS storage, concurrency limit); auto-configured, `socialflux.media.*` properties | — |
| `BackEnd/` | **Legacy** Javalin backend (relations, posts, likes, post images, notifications). Runs in `AUTH_MODE=gateway`: trusts only `X-User-Id` from the gateway | 8000 (never published) |
| `infra/` | `caddy/Caddyfile` (gateway routing) and `mysql/schema.sql` (legacy schema, inferred from code) | 8080 (gateway) |
| `scripts/dev/` | Scripts that run each backend on the host using the root `.env` (`.ps1` and `.sh`) | — |
| `docs/` | Legacy analysis and migration plans (domain decisions D1–D12) and the local run guide | — |
| `docker-compose.yml` / `.env.example` | Full dev environment and its variable names | — |

Each app has a `docs/ARCHITECTURE.md` (directory tree, what each directory holds, what each file is) and a `README.md`
(routes, events, config). The frontend also has `docs/COMPONENTS.md` (reusable components). The root has `docs/ARCHITECTURE.md`
for the cross-cutting pieces. **Read the app's `docs/` and `README.md` before working in it.**

### How requests flow
```
Browser ─► Caddy (same origin)
            ├─ /                        → FrontEnd
            ├─ /auth/*, /.well-known    → authentication_service ─► PostgreSQL (db `identity`)
            ├─ /api/usuarios*, /api/img/usuarios/*, /media/avatars/*
            │                           → forward_auth (revocation) → profile_service ─► PostgreSQL (db `profile`) + RustFS (bucket `avatars`)
            ├─ /api/relacoes*           → forward_auth → social_graph_service ─► PostgreSQL (db `social_graph`)
            ├─ /api/postagens*, /media/posts/*
            │                           → forward_auth → post_service ─► PostgreSQL (db `post`) + RustFS (bucket `post-media`)
            ├─ /api/* (rest)            → forward_auth → X-User-Id → legacy BackEnd ─► MySQL
            └─ /internal/*              → 404 (service-to-service only)
auth ──PUT /internal/profiles/{uuid} (service token)──► profile        (registration, D1: auth orchestrates)
auth ──account.registered|deleted.v1──► RabbitMQ ──► profile
profile ──profile.created|updated|privacy-changed|deleted.v1──► RabbitMQ ──► `usuario` projection in MySQL, and the graph's local copy
graph ──follow.*.v1──► RabbitMQ ──► `relacao` projection in MySQL, and the posts service's local edge copy
posts ──post.created|deleted|liked|unliked.v1──► RabbitMQ ──► (future Notifications service)
```
The account and the profile share the same **UUIDv7**. The integer `id`/`legacy_id`/`X-User-Id` is **temporary**, only for
domains still in the legacy backend.

## Commands
- **Infra only (apps on host):** `docker compose --profile host up -d` (Postgres, MySQL, RabbitMQ, RustFS, Mailpit + gateway on :8080; needs `RUSTFS_ACCESS_KEY`/`RUSTFS_SECRET_KEY` in `.env`) · status: `docker compose ps`
- **Everything in containers:** `docker compose --profile apps up -d --build` → http://localhost:8080
- **Stop:** `docker compose down` (never with `-v` without asking)
- **Backends on host** (from the repo root, read `.env`): `scripts/dev/run-auth.ps1`, `run-profile.ps1`, `run-graph.ps1`, `run-legacy.ps1` (or the `.sh` versions)
- **Spring services:** from the root, `mvn -pl <service> -am test` (also builds the `libs/*` libraries; integration tests use embedded PostgreSQL, no Docker needed) · inside a service folder, `mvn test` / `mvn spring-boot:run` after `mvn -pl libs/socialflux-events,libs/socialflux-media install` from the root · the root `pom.xml` is only an aggregator
- **Legacy:** `mvn compile exec:java` (no tests)
- **Frontend** (`FrontEnd/`): `npm ci` · `npm run dev` · `npm run typecheck` · `npm run lint` · `npm run build` (no test runner)
- Dev mailbox: http://localhost:8025 · RabbitMQ panel: http://localhost:15672
- Full guide and troubleshooting: `docs/rodando-localmente.md`

## Databases — always via Docker
- PostgreSQL, MySQL and RabbitMQ **always run through Docker** (`docker-compose.yml`). Never install or depend on local installations.
- **One PostgreSQL instance, one database per service** (`identity`, `profile`, `social_graph`; the last two are created by `profile_db_init` / `graph_db_init`). A service never reads another service's database.
- Schema changes in Spring services go through **new Flyway migrations** (`src/main/resources/db/migration/V<n>__*.sql`). **Never edit an existing migration.**
- `infra/mysql/schema.sql` only runs when the MySQL volume is created.
- Data lives in named volumes. **Never run `docker compose down -v`, `docker volume rm` or drop/recreate a database without asking** — it erases data.
- If a service can't reach a database, first check the containers (`docker compose ps`) and that the values match `.env`.

## Language — IMPORTANT
- **Comments, UI text, log/error messages, API response messages (`mensagem`), docs and commit messages: pt-BR.**
- **Identifiers follow each app's existing style:**
  - `FrontEnd/` and `BackEnd/`: names in Portuguese (e.g. `listarPostagens`, `FormCriarPostagem`, `usePerfil`, `RelacaoService`).
  - `authentication_service/` and `profile_service/`: class/method/variable names in English (e.g. `AccountService`, `ProfileRules`), with Portuguese comments and messages.
- JSON field names of the public API keep the contract the frontend already uses (Portuguese/snake_case: `foto_perfil`, `data_nascimento`, `{ mensagem, sucesso, codigo }`).
- Language keywords, library APIs and established technical terms stay in English. This file is in English.

## Code conventions
- **Frontend:** strict TypeScript; **no `any`** (use `unknown` + narrowing). API calls only in `src/services/` through the shared axios instance (`src/lib/api.ts`); data/state logic in hooks (`src/hooks/`); API models in `src/types/api.ts`. Use Bootstrap classes and the tokens in `src/styles/theme.css`; register new icons in `src/lib/icons.ts`.
- Components should stay under ~300 lines; split into smaller components or extract hooks when they grow. Prefer reusing the components listed in `FrontEnd/docs/COMPONENTS.md`.
- **Spring services:** layers `web` (controllers, DTOs, errors) → `service` (rules) → `domain` (entities, repositories); config in typed `@ConfigurationProperties` records fed by env vars; errors via `ApiException` + `GlobalExceptionHandler` with the body `{ mensagem, sucesso: false, codigo }`; inject a `Clock` instead of calling `Instant.now()`.
- Small, single-purpose functions; early returns over deep nesting.
- **Validate all input on the backend**, even if the frontend already validates it.

## Legacy backend — frozen
- **No new features in `BackEnd/`**: only bug/security fixes and the minimum needed to keep it working during the migration.
- A new or migrated domain becomes its own Spring service (same patterns as `profile_service`: own database, Flyway, outbox via `libs/socialflux-events`, JWT resource server, README + `docs/ARCHITECTURE.md`), plus a route in the `Caddyfile` and a service in `docker-compose.yml`.
- The legacy backend must never be reachable without the gateway (`X-User-Id` would be forgeable): do not publish its port.

## Service contracts
- **Identity always comes from the token** (`sub` / `X-User-Id` set by the gateway); an id in the path or body is only checked, never trusted.
- **Events:** versioned type as routing key (`<domain>.<event>.v<n>`) on the topic exchange `socialflux.events`; written to the **outbox in the same transaction** as the state change; consumers are **idempotent** (inbox/`processed_message`) and ignore unknown types and older `aggregateVersion`s. A breaking change means a new version (`.v2`), never changing `.v1`.
- **Events never carry personal data** (e-mail, password, phone, sex, birth date).
- `/internal/*` routes require a **service token** with explicit scope and are never routed by the gateway.
- Public error body: `{ "mensagem": "...", "sucesso": false, "codigo": "..." }` with a stable `codigo`.

## Workflow rules
1. **Before working in an app, read its `README.md` and the markdown files in its `docs/`.** Do not start changing anything before reading them.
2. **Keep the docs in sync, in the same commit as the change:**
   - New/moved/removed file or directory → update that app's `docs/ARCHITECTURE.md` (short description).
   - New/changed reusable component in the frontend → update `FrontEnd/docs/COMPONENTS.md` (path, purpose, main props).
   - New/changed route, event, env var or table → update the app's `README.md` (and `.env.example` / root `README.md` when it is cross-cutting).
   - Gateway, compose, scripts or cross-cutting change → update the root `docs/ARCHITECTURE.md`.
3. **Commit after each small, completed task** — one logical change per commit.
4. **Never commit or push to `main`.** Work on a branch (`feat/...`, `fix/...`, `docs/...`) and open a PR.
5. **Ask before:** adding dependencies, changing a database schema (new migration or `schema.sql`), changing `docker-compose.yml` or the `Caddyfile`, changing authentication/session/token logic, or changing an event/API contract.
6. Frontends must be **responsive and mobile-first**, using Bootstrap breakpoints (`d-lg-*`, `col-md-*`…); the authenticated layout uses a sidebar on desktop and header/footer bars on mobile.
7. Always run the tests (`mvn test`) after a new implementation in a Spring service.

## Security
- Never commit or expose secrets (JWT keys, DB/RabbitMQ passwords). Use the root `.env` (gitignored) and keep `.env.example` with variable names/placeholders only.
- **Known issue:** `BackEnd/src/main/resources/application.properties` (contains an old `jwt.secret` and DB credentials) and `BackEnd/logs/app.log` are tracked by Git. Do not edit, print or commit changes to them; treat their secrets as leaked.
- Every state-changing route requires authentication and an authorization check on the backend, never only hidden UI.
- Users may only read/change their own data (check ownership on every route that takes an id); private-account content must be enforced on the backend.
- Passwords: Argon2id in `authentication_service` (legacy BCrypt hashes are upgraded on login). Never log credentials, tokens or personal data.
- Uploaded files: server-generated names, validate magic bytes and size, re-encode images, resolve paths against a base directory (see `libs/socialflux-media` `ImageProcessor` / `GifSanitizer` and `profile_service` `BucketAvatarStorage`).

## Commits — Conventional Commits, message in Portuguese
Format: `type(scope): descrição curta no imperativo` — scope = app or domain (`auth`, `perfil`, `frontend`, `legado`, `infra`, `postagens`…).

| Type | Use for |
|---|---|
| `feat` | new feature |
| `fix` | bug fix |
| `docs` | documentation only (incl. `ARCHITECTURE.md` / `COMPONENTS.md` / README-only changes) |
| `style` | formatting, no logic change |
| `refactor` | restructuring without behavior change |
| `perf` | performance improvement |
| `test` | add/fix tests |
| `build` | build config, dependencies, Docker setup |
| `ci` | CI/CD pipelines |
| `chore` | maintenance not touching production code or tests |
| `revert` | revert a previous commit |

Examples: `feat(perfil): adiciona filtro de busca por nome` · `fix(auth): corrige renovação da sessão expirada`

## Definition of done
- No `any`, no secrets, no leftover `console.log` / `System.out.println` / debug logs.
- Spring service tests passing.
- `docs/ARCHITECTURE.md`, `FrontEnd/docs/COMPONENTS.md` and README updated where relevant.
- Committed on a branch with a proper Conventional Commit message.
