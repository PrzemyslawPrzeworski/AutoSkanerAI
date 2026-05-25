# Deployment Plan — AutoSkanerAI on Render

**Status:** Draft — not yet executed  
**Platform:** Render (Web Service, Docker runtime)  
**Scope:** Backend only (Spring Boot). Frontend deployment is out of scope per `infrastructure.md`.  
**Reference:** `context/foundation/infrastructure.md` (researched 2026-05-24)

---

## Context

The project is a bare Spring Boot 4.0.6 scaffold with no Docker setup, no `render.yaml`, no Actuator dependency, and an `application.properties` that only sets `spring.application.name`. The infrastructure research selected Render as the target platform.

Known gaps to close before a first successful deploy:

| Gap | Risk if skipped |
|---|---|
| JDK 21 not confirmed on local machine | `./mvnw verify` fails; Phase 1 blocked |
| Docker Desktop not installed / not running | Cannot build or test image locally |
| Render CLI not installed | CLI-based log tailing, rollback, and env var commands all fail |
| `jq` not installed | Rollback runbook's JSON parse step fails |
| No Render account | Cannot connect repo or provision service |
| GitHub repo not connected to Render | Auto-deploy on push cannot work |
| No Render API key | CLI auth fails; MCP server unreachable |
| No Supabase project (for FR-010) | DB env vars have nothing to point at when persistence is implemented |
| Java 17 in `pom.xml` vs Java 21 in CLAUDE.md / infra doc | Render Docker image mismatch; runtime surprises |
| No `server.port=${PORT:10000}` | Health check never passes; service stuck in "Deploying" |
| No `spring-boot-starter-actuator` | No `/actuator/health` endpoint; health check fails |
| No `Dockerfile` in `backend/` | Render cannot build the image |
| No `.dockerignore` in `backend/` | Every push re-downloads all Maven deps; slow builds, possible build-minute exhaustion |
| No `render.yaml` at repo root | Manual dashboard config required; dashboard/IaC drift from day one |
| `ANTHROPIC_API_KEY` not set on Render | App boots on `mock` profile; switching to `llm` profile fails silently if key absent |

---

## Phase 0 — Prerequisites

### 0.1 — JDK 21

> Goal: local compiler matches the target runtime declared in CLAUDE.md.

- [ ] 0.1.1 Check installed JDK: `java -version` — expect `openjdk 21.x.x`
- [ ] 0.1.2 If JDK 17 or older is active, install JDK 21:
  - **Recommended (Windows, SDKMAN via WSL):** `sdk install java 21-tem`
  - **Recommended (Windows native, Scoop):** `scoop install temurin21`
  - **Recommended (Windows native, direct):** download Eclipse Temurin 21 installer from [adoptium.net](https://adoptium.net/temurin/releases/?version=21) and run it
- [ ] 0.1.3 Set `JAVA_HOME` to the JDK 21 path and verify: `$JAVA_HOME/bin/java -version`
- [ ] 0.1.4 If IntelliJ is used, update **File → Project Structure → SDK** to point at JDK 21

**Edge case — multiple JDKs installed:**  
The Maven wrapper (`./mvnw`) respects `JAVA_HOME`. If the system `java` is still 17 but `JAVA_HOME` points to JDK 21, the wrapper will compile correctly. Verify with `./mvnw -version` — it should print the JVM it will use.

---

### 0.2 — Docker Desktop (Windows)

> Goal: local Docker daemon available for building and testing the backend image.

- [ ] 0.2.1 Check if Docker is already installed: `docker --version` — expect `Docker version 25.x` or newer
- [ ] 0.2.2 If not installed:
  1. Enable WSL2 (required by Docker Desktop's default backend on Windows 11):
     ```powershell
     wsl --install
     # Reboot if prompted
     ```
  2. Download and install Docker Desktop from [docker.com/products/docker-desktop](https://www.docker.com/products/docker-desktop/)
  3. During setup, choose **Use WSL 2 instead of Hyper-V** (default on Windows 11)
- [ ] 0.2.3 Start Docker Desktop, wait for the whale icon in the system tray to stop animating (daemon ready)
- [ ] 0.2.4 Smoke-test: `docker run --rm hello-world` — expect the "Hello from Docker!" message
- [ ] 0.2.5 Verify BuildKit is enabled (required for multi-stage builds and layer caching):
  ```bash
  docker buildx version
  # Should print "github.com/docker/buildx ..."
  ```
  BuildKit ships enabled by default in Docker Desktop 4.x+. If `buildx` is missing, update Docker Desktop.

**Edge case — Docker Desktop licence for enterprise:**  
Docker Desktop requires a paid subscription for organisations with >250 employees or >$10M revenue. For a solo dev project this is free. Verify licence terms at docker.com/pricing if working within a corporate account.

**Edge case — WSL2 kernel update:**  
On some Windows 10/11 builds, `wsl --install` requires a separate kernel update step. If Docker Desktop shows "WSL2 kernel not found", run `wsl --update` in an elevated PowerShell, then restart Docker Desktop.

---

### 0.3 — Render CLI (Windows)

> Goal: `render` command available in the shell for log tailing, env var management, rollback, and Phase 8 MCP setup.

- [ ] 0.3.1 **Install via Scoop (recommended for Windows):**
  ```powershell
  scoop install render
  ```
  If Scoop is not installed first: `irm get.scoop.sh | iex` in PowerShell (requires execution policy: `Set-ExecutionPolicy RemoteSigned -Scope CurrentUser`).

- [ ] 0.3.2 **Alternative — direct binary download:**
  1. Go to the Render CLI releases page: `https://github.com/render-com/render-cli/releases`
  2. Download the latest `render_windows_amd64.zip`
  3. Unzip and move `render.exe` to a directory on `PATH` (e.g. `C:\Users\<you>\bin\`)

- [ ] 0.3.3 Verify install: `render --version` — expect a version string like `render/1.x.x`

- [ ] 0.3.4 **Authenticate — browser OAuth (recommended):**
  ```bash
  render login
  # Opens browser → log in with your Render account → approval grants CLI access
  ```

- [ ] 0.3.5 **Alternative — API key auth (for CI or headless environments):**
  ```bash
  # Set in shell profile (e.g. ~/.bashrc or Windows Environment Variables):
  export RENDER_API_KEY=<your-render-api-key>
  # The CLI reads this variable automatically; no login step needed
  ```

- [ ] 0.3.6 Verify auth: `render services list` — expect an empty list (no services yet) or a table if other services exist

**Edge case — Scoop PATH not set in Git Bash:**  
Scoop installs to `~/scoop/shims/`. Git Bash on Windows may not include this path. If `render: command not found`, add `~/scoop/shims` to `PATH` in `~/.bashrc`:
```bash
export PATH="$HOME/scoop/shims:$PATH"
```

**Edge case — `render login` opens browser but token never arrives:**  
This can happen when the callback port (default: `localhost:10000`) is blocked by a corporate firewall or another process. Use the API key auth method (step 0.3.5) as a fallback.

**Edge case — Windows Defender / antivirus flags the binary:**  
Some AV scanners flag unsigned Go binaries. If blocked, verify the binary's SHA-256 hash against the checksum published on the GitHub releases page before adding an exception.

---

### 0.4 — `jq` (JSON processor)

> Goal: required by the Phase 7 rollback command (`render deploys list ... | jq '.[].id'`).

- [ ] 0.4.1 Check: `jq --version` — expect `jq-1.7.x`
- [ ] 0.4.2 If missing, install:
  - **Scoop:** `scoop install jq`
  - **Chocolatey:** `choco install jq`
  - **Direct download:** grab the Windows binary from [github.com/jqlang/jq/releases](https://github.com/jqlang/jq/releases) (`jq-windows-amd64.exe`), rename to `jq.exe`, place on `PATH`

---

### 0.5 — Render account and GitHub access

> Goal: Render can pull commits from the GitHub repo and trigger deploys.

- [ ] 0.5.1 Create a Render account at [dashboard.render.com](https://dashboard.render.com) (free; use the same GitHub login to simplify OAuth)
- [ ] 0.5.2 Connect GitHub: Render dashboard → **Account Settings → Git Providers → Connect GitHub**
  - Grant access to `PrzemyslawPrzeworski/AutoSkanerAI` specifically (do not grant access to all repos unless preferred)
- [ ] 0.5.3 Confirm the repo appears in Render's repo picker (tested during Phase 5 when creating the service)

**Edge case — repo is private:**  
Private repos work fine with the GitHub OAuth grant. If the repo is public but later made private, the existing Render connection continues to work without reconfiguration.

**Edge case — GitHub org vs personal account:**  
The repo lives under a personal account (`PrzemyslawPrzeworski`). If it is ever transferred to an org, re-authorise the Render GitHub app under the org's settings.

---

### 0.6 — Render API key

> Goal: API key required for CLI operations (steps 0.3.5, Phase 7 rollback) and Phase 8 MCP server.

- [ ] 0.6.1 Generate key: Render dashboard → **Account Settings → API Keys → Create API Key**
- [ ] 0.6.2 Copy the key immediately — it is shown **only once**
- [ ] 0.6.3 Store it in a password manager (e.g. Bitwarden, 1Password) — do **not** commit to git
- [ ] 0.6.4 Add to local shell profile for CLI use:
  ```bash
  export RENDER_API_KEY=sk-rnd-...
  ```
  Or pass inline to individual commands:
  ```bash
  RENDER_API_KEY=sk-rnd-... render services list
  ```

**Edge case — key rotation:**  
Rotating the Render API key requires updating it everywhere it is set: local shell profile, any CI secrets, and the `Authorization` header in the Claude Code MCP server config (Phase 8). Render does not auto-rotate keys; rotation is manual.

---

### 0.7 — Starter plan upgrade (pre-demo)

> Goal: eliminate the free-tier 15-minute idle spin-down before any stakeholder demo.

- [ ] 0.7.1 After the first successful deploy (Phase 5), upgrade the service to the **Starter** plan ($7/mo):
  Render dashboard → Service → **Settings → Instance Type → Starter**
  Or set in `render.yaml`: `plan: starter` (already present in Phase 4)
- [ ] 0.7.2 Confirm the service shows "Starter" in the dashboard header — free tier shows "Free"
- [ ] 0.7.3 Verify pricing at [render.com/pricing](https://render.com/pricing) — Render updated plan names in April 2026; confirm "Starter" maps to the always-on $7/mo tier

**Edge case — Render pricing model in transition:**  
Render updated its pricing structure in April 2026. Any tutorial or community answer predating 2026-04-23 may reference old plan names. Always confirm current tier names and pricing on the official pricing page before provisioning.

---

### 0.8 — Supabase project setup

> This phase is for **FR-010 (persistence)** which is post-MVP. Complete it before implementing FR-010, not before the initial deploy.

> Goal: Supabase project created in the correct region with connection details recorded.

- [ ] 0.8.1 Create a Supabase account at [supabase.com](https://supabase.com) (free tier available)
- [ ] 0.8.2 Create a new project:
  - **Organisation:** personal (or create an org)
  - **Project name:** `autoskaner-ai`
  - **Database password:** generate a strong password and store in a password manager immediately — Supabase does not show it again
  - **Region:** **Frankfurt (eu-central-1)** — closest to Render's Frankfurt region; minimises latency for the public-internet DB path (see `infrastructure.md` — Render private network does not reach Supabase)
- [ ] 0.8.3 Wait for project provisioning (~2 minutes)
- [ ] 0.8.4 Retrieve the JDBC connection string:
  Supabase dashboard → **Project Settings → Database → Connection string → JDBC**
  It will look like:
  ```
  jdbc:postgresql://db.<project-ref>.supabase.co:5432/postgres?sslmode=require
  ```
  - **User:** `postgres`
  - **Password:** the password set in step 0.8.2
  - **`sslmode=require`** is mandatory — Supabase rejects unencrypted external connections

- [ ] 0.8.5 **Connection mode decision — direct vs Supavisor:**

  | Mode | Port | Use when |
  |---|---|---|
  | **Direct connection** | `5432` | Spring Boot + HikariCP (long-lived pool) ← **use this** |
  | Supavisor (pooler) | `6543` | Serverless / short-lived connections (Lambda, edge functions) |

  Spring Boot with HikariCP maintains a persistent connection pool. Use the **direct connection** string (port `5432`), not the Supavisor pooler (port `6543`). The pooler is optimised for stateless clients and adds overhead for HikariCP.

- [ ] 0.8.6 Record the full JDBC URL and credentials in your password manager. Do **not** commit them to git.
- [ ] 0.8.7 Configure HikariCP max pool size to stay within Supabase free tier limits:
  - Supabase free tier: **60 direct connections** max
  - Set in `application-prod.properties` (when FR-010 is implemented):
    ```properties
    spring.datasource.hikari.maximum-pool-size=5
    spring.datasource.hikari.minimum-idle=2
    ```
  - With one Render Starter instance, 5 connections is safe. Scale up only when upgrading the Render plan adds more instances.

- [ ] 0.8.8 Set `DATABASE_URL` as a Render env var (not in `render.yaml` plaintext) when FR-010 is implemented:
  ```bash
  render env set DATABASE_URL="jdbc:postgresql://db.<ref>.supabase.co:5432/postgres?sslmode=require&user=postgres&password=<pw>" \
    --service autoskaner-ai-backend
  ```

**Edge case — Supabase free tier pausing:**  
Supabase pauses projects that have had no activity for **7 days** on the free tier. A paused project refuses all connections; the Spring Boot app will fail to start (datasource init error). Options: upgrade to Pro ($25/mo) for always-on, or use Supabase's dashboard to unpause before demos.

**Edge case — SSL certificate validation in Spring Boot:**  
`sslmode=require` encrypts the connection but does not validate the server certificate. This is sufficient for most cases. If strict cert validation is needed (`sslmode=verify-full`), download the Supabase root CA from the dashboard and configure it in the datasource. For MVP, `sslmode=require` is acceptable.

**Edge case — connection string contains special characters in password:**  
If the database password contains `@`, `#`, `/`, or `?`, it must be URL-encoded in the JDBC URL. Generate a password using only alphanumeric characters and `-_` to avoid encoding issues.

- [ ] 0.8.9 **Configure Supabase Auth redirect URLs** — required before implementing login (FR-008/FR-009):
  - Supabase dashboard → **Authentication → URL Configuration**
  - **Site URL:** `https://autoskaner-ai.pages.dev`
  - **Redirect URLs allowlist** — add both:
    - `https://autoskaner-ai.pages.dev`
    - `http://localhost:4200`
  - This tells Supabase where to redirect users after email confirmation or OAuth login. Must be set before wiring Spring Security + JWT/OAuth2, otherwise auth redirects will fail.

**Edge case — custom domain added later:**  
If a custom domain (e.g. `autoskaner.pl`) replaces `pages.dev`, update both the Site URL and the allowlist entry in Supabase Auth → URL Configuration. Auth redirects are tied to exact URLs — wildcards are not supported in the Site URL field.

---

### 0.9 — API keys inventory

> Goal: all secrets identified and stored before Phase 5 sets them on Render.

| Key | Where to obtain | Where to set | Notes |
|---|---|---|---|
| `ANTHROPIC_API_KEY` | [console.anthropic.com](https://console.anthropic.com) → API Keys | Render env var (Phase 5) | Required to switch `SPRING_PROFILES_ACTIVE` from `mock` to `llm` |
| `OPENAI_API_KEY` | [platform.openai.com](https://platform.openai.com) → API Keys | Render env var (Phase 5) | Optional; only if LLM provider is switched to OpenAI |
| `RENDER_API_KEY` | Render dashboard (Phase 0.6) | Local shell + Claude Code MCP header (Phase 8) | Never commit to git |
| `DATABASE_URL` | Supabase dashboard (Phase 0.8) | Render env var (when FR-010 is implemented) | Include password and `?sslmode=require` |

- [ ] 0.9.1 Confirm `ANTHROPIC_API_KEY` is available (generate at console.anthropic.com if not)
- [ ] 0.9.2 Confirm `RENDER_API_KEY` is generated and stored (Phase 0.6)
- [ ] 0.9.3 Confirm `DATABASE_URL` connection string is saved in password manager (Phase 0.8)
- [ ] 0.9.4 Verify none of the above appear in any file tracked by git: `git grep -r "sk-ant\|SUPABASE\|rnd_\|postgresql://" -- ':!*.md'` — expect zero results

---

## Phase 1 — Java version alignment

> Goal: ensure pom.xml, Dockerfile, and Render all agree on Java 21.

- [ ] 1.1 Open `backend/pom.xml`; change `<java.version>17</java.version>` to `<java.version>21</java.version>`
- [ ] 1.2 Verify that `eclipse-temurin:21-jdk-alpine` and `eclipse-temurin:21-jre-alpine` images are referenced in the Dockerfile written in Phase 2 (cross-check)
- [ ] 1.3 Run `./mvnw verify -q` locally to confirm the project compiles under Java 21

**Edge case — local JDK is still 17:**  
If the local dev machine runs JDK 17, `./mvnw verify` will fail with "release version 21 not supported". Fix: install JDK 21 (e.g. `sdk install java 21-tem` via SDKMAN) and set `JAVA_HOME` before running the Maven wrapper.

---

## Phase 2 — Add Actuator dependency

> Goal: expose `/actuator/health` so Render's health-check probe can pass.

- [ ] 2.1 Add to `backend/pom.xml` `<dependencies>`:

  ```xml
  <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
  </dependency>
  ```

- [ ] 2.2 Add to `backend/src/main/resources/application.properties`:

  ```properties
  server.port=${PORT:10000}
  management.endpoints.web.exposure.include=health
  management.endpoint.health.show-details=never
  ```

- [ ] 2.3 Run `./mvnw spring-boot:run` locally, then `curl http://localhost:10000/actuator/health` — expect `{"status":"UP"}`

**Edge case — `PORT` already occupied on local machine:**  
If port 10000 is taken locally, export `PORT=8080` before running `spring-boot:run` for dev convenience; the property default (`${PORT:10000}`) only kicks in when `PORT` is unset.

**Edge case — Spring Boot 4 Actuator path changes:**  
Spring Boot 4 keeps the `/actuator` base path. No path change needed vs Spring Boot 3.x, but if a custom `management.server.port` is ever added, the health check path in `render.yaml` must be updated to match.

---

## Phase 3 — Write `backend/Dockerfile`

> Goal: reproducible, layer-cached, slim Docker image.

- [ ] 3.1 Create `backend/Dockerfile`:

  ```dockerfile
  # syntax=docker/dockerfile:1
  FROM eclipse-temurin:21-jdk-alpine AS build
  WORKDIR /app
  COPY .mvn/ .mvn/
  COPY mvnw pom.xml ./
  RUN ./mvnw dependency:go-offline -q
  COPY src/ src/
  RUN ./mvnw package -DskipTests -q

  FROM eclipse-temurin:21-jre-alpine
  WORKDIR /app
  COPY --from=build /app/target/*.jar app.jar
  ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
  ENTRYPOINT ["java", "-jar", "app.jar"]
  ```

- [ ] 3.2 Create `backend/.dockerignore`:

  ```
  target/
  .mvn/wrapper/maven-wrapper.jar
  .git
  .gitignore
  *.md
  .idea/
  .vscode/
  ```

- [ ] 3.3 Build locally: `docker build -t autoskaner-ai-backend ./backend` — expect a successful build and image size under 300 MB
- [ ] 3.4 Run locally: `docker run -p 10000:10000 autoskaner-ai-backend` then `curl http://localhost:10000/actuator/health` — expect `{"status":"UP"}`

**Edge case — `mvnw` not executable inside the build container:**  
The `COPY mvnw` step copies the file but Alpine strips execute bits on some hosts. Add `RUN chmod +x mvnw` immediately after the COPY line if the build fails with `permission denied`.

**Edge case — multi-arch (Apple M1 / M2 local builds):**  
If building on ARM locally but Render runs AMD64, add `--platform linux/amd64` to the local `docker build` command for an accurate test. Render always pulls/builds for its host arch; the Docker image itself will be correct.

**Edge case — `target/*.jar` glob matches multiple files:**  
Spring Boot Maven plugin produces one `*-SNAPSHOT.jar` and one `*.jar.original`. The glob `target/*.jar` should match only the fat jar because the `.original` is renamed. If a future build configuration changes this, pin the filename explicitly: `COPY --from=build /app/target/autoskaner-ai-*.jar app.jar`.

---

## Phase 4 — Write `render.yaml`

> Goal: all Render configuration lives in IaC; dashboard is read-only.

- [ ] 4.1 Create `render.yaml` at the **repo root**:

  ```yaml
  services:
    - type: web
      name: autoskaner-ai-backend
      runtime: docker
      rootDir: backend
      plan: starter
      healthCheckPath: /actuator/health
      autoDeploy: true
      envVars:
        - key: SPRING_PROFILES_ACTIVE
          value: mock
        - key: ANTHROPIC_API_KEY
          sync: false
        - key: OPENAI_API_KEY
          sync: false
  ```

  `sync: false` means Render prompts for the value on first connect; the key is never committed to git.

- [ ] 4.2 Confirm `rootDir: backend` matches the location of `backend/Dockerfile` (Render resolves `rootDir` relative to repo root)

**Edge case — `render.yaml` not detected by Render:**  
Render detects `render.yaml` only when it is at the **exact repo root** (not in a subdirectory). If GitHub repo root is the monorepo root `AutoSkanerAI/`, this is satisfied. If the repo were set up with `backend/` as the root, the file would need to move and `rootDir` would become `.`.

**Edge case — `autoDeploy: true` triggers on every branch push:**  
This means preview services spin up for every open branch. Preview services on the free tier sleep after 15 minutes. To avoid unexpected spend on long-lived branches, either set `autoDeploy: false` and trigger manually, or add branch-filter rules in the Render dashboard (dashboard-only setting; not expressible in `render.yaml` v1).

---

## Phase 5 — Connect repo on Render and set secrets

> Goal: first successful deploy from a git push.

- [ ] 5.1 Go to [dashboard.render.com](https://dashboard.render.com) → **New Web Service** → connect `PrzemyslawPrzeworski/AutoSkanerAI`
- [ ] 5.2 Render detects `render.yaml` — confirm the preview matches Phase 4 settings
- [ ] 5.3 Set `ANTHROPIC_API_KEY` via the Render dashboard (Environment → Secret Files or Environment Variables) or CLI:

  ```bash
  render env set ANTHROPIC_API_KEY=<your-key> --service autoskaner-ai-backend
  ```

- [ ] 5.4 Trigger first deploy (push a no-op commit or click "Deploy latest commit")
- [ ] 5.5 Watch logs: `render logs autoskaner-ai-backend --tail`
- [ ] 5.6 Confirm the service URL responds: `curl https://autoskaner-ai-backend.onrender.com/actuator/health`

**Edge case — service URL format:**  
Render generates the URL as `https://<service-name>.onrender.com`. If the name contains underscores (`autoskaner_ai`), Render converts them to hyphens in the URL. Use only hyphens in the `name:` field of `render.yaml` to avoid confusion.

**Edge case — health check passes but app returns 500 on real requests:**  
Actuator `/health` bypasses auth and application logic. A passing health check only means the JVM started, not that the app logic works. After deploy, smoke-test a real endpoint (e.g., `POST /api/analyses` or whatever FR-001 wires up) before calling the deploy done.

**Edge case — Supabase connection (when DB is added, FR-010):**  
Render's private network does not reach Supabase. All DB traffic crosses the public internet. Set `DATABASE_URL` as an env var in Render (not in `render.yaml` plaintext). Choose the Render region geographically closest to the Supabase instance (Frankfurt for EU) to minimise latency.

---

## Phase 6 — RAM and JVM tuning

> Goal: prevent OOM on the 512 MB Starter plan.

- [ ] 6.1 Confirm `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75` is set in the `Dockerfile` (done in Phase 3)
- [ ] 6.2 After first deploy, check memory via Render dashboard → Metrics. Idle should be 200–350 MB. If spike approaches 512 MB under test load, upgrade to Standard ($25/mo) before any demo.
- [ ] 6.3 Document the upgrade path in a runbook comment in this file if/when triggered

**Edge case — devtools on classpath in prod:**  
`pom.xml` currently includes `spring-boot-devtools` with `<scope>runtime</scope>`. Devtools detects it is running in a Docker container and disables itself, but it still adds ~10–15 MB to the fat jar. Consider removing it from the production image by adding a Maven profile, or simply accept the size.

---

## Phase 7 — Rollback runbook

> Goal: documented 2-command rollback so a bad deploy can be undone in under 5 minutes.

- [ ] 7.1 Record the rollback command sequence here for fast reference:

  ```bash
  # 1. List recent deploys (grab the DEPLOY_ID of the last good deploy)
  render deploys list srv-d89ni3i8qa3s73e6fub0 --output json | jq '[.[] | {id: .id, status: .status, createdAt: .createdAt}]'

  # 2. Roll back to that deploy
  render deploys rollback srv-d89ni3i8qa3s73e6fub0 <DEPLOY_ID>
  ```

  Expected time-to-revert: 2–4 minutes (Docker pull + Spring Boot startup).

- [ ] 7.2 Note: **DB migrations do not roll back automatically**. If a deploy included a Liquibase/Flyway migration, write and apply a compensating migration manually before rolling back the service.

---

## Phase 8 — Add Render MCP server to Claude Code (optional, AI-native ops)

> Goal: enable agent-driven deploys, log tailing, and env var management from within Claude Code.

- [ ] 8.1 Obtain a Render API key from dashboard.render.com → Account Settings → API Keys
- [ ] 8.2 Add the MCP server:

  ```bash
  claude mcp add --transport http render https://mcp.render.com/mcp \
    --header "Authorization: Bearer <RENDER_API_KEY>"
  ```

- [ ] 8.3 Verify connection: ask Claude Code "list my Render services" — expect `autoskaner-ai-backend` in the result
- [ ] 8.4 Note the known MCP scope limits: log streaming and complex metric aggregation still require the `render` CLI. Treat MCP as a bonus, not the primary ops path.

**Edge case — MCP server availability:**  
The Render MCP server (`mcp.render.com`) is listed as GA as of 2026-05-24, but the docs MCP (`mcp.inkeep.com`) is experimental and may be discontinued. Only rely on `mcp.render.com` for ops; do not depend on the docs MCP for automated workflows.

---

## Verification checklist (end-to-end)

After all phases are complete:

**Prerequisites (Phase 0)**
- [ ] `java -version` prints `openjdk 21.x.x`
- [ ] `docker --version` and `docker run --rm hello-world` both succeed
- [ ] `render --version` and `render services list` both succeed
- [ ] `jq --version` succeeds
- [ ] Render account connected to GitHub; `PrzemyslawPrzeworski/AutoSkanerAI` visible in repo picker
- [ ] `ANTHROPIC_API_KEY`, `RENDER_API_KEY`, and Supabase connection string stored in password manager
- [ ] `git grep -r "sk-ant\|rnd_\|postgresql://" -- ':!*.md'` returns zero results

**Deploy (Phases 1–5)**
- [ ] `./mvnw verify -q` passes locally under JDK 21
- [ ] `docker build -t autoskaner-ai-backend ./backend` succeeds with image under 300 MB
- [ ] `docker run -p 10000:10000 autoskaner-ai-backend` + `curl http://localhost:10000/actuator/health` → `{"status":"UP"}`
- [ ] `curl https://autoskaner-ai-backend.onrender.com/actuator/health` returns `{"status":"UP"}`
- [ ] `render logs autoskaner-ai-backend --tail` shows clean startup with no OOM or port-bind errors
- [ ] A push to `main` triggers an automatic redeploy (confirm in Render dashboard → Deploys)

**Operations (Phases 6–8)**
- [ ] Render dashboard Metrics show idle memory below 400 MB (well under 512 MB Starter limit)
- [ ] Rollback command executes successfully on a test deploy (smoke-test Phase 7)
- [ ] Render dashboard shows 0 config drift from `render.yaml` (no manual overrides made)
- [ ] (Optional) `claude mcp list` shows `render` server; "list my Render services" returns `autoskaner-ai-backend`

---

## Out of scope (this plan)

- Frontend deployment (Render static site or Cloudflare Pages — separate decision)
- GitHub Actions CI pipeline
- Auth (Spring Security + JWT/OAuth2 — planned per PRD, not yet implemented)
- CEPiK / Supabase wiring (post-MVP)
