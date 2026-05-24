---
project: AutoSkanerAI
researched_at: 2026-05-24
recommended_platform: Render
runner_up: Fly.io
context_type: mvp
tech_stack:
  language: Java 21
  framework: Spring Boot 4.0.6
  runtime: JVM (Docker container)
  database: Supabase (external)
---

## Recommendation

**Deploy on Render.**

Render is the only candidate platform with a GA MCP server and published `llms.txt` — directly relevant for an AI-tooling course project where agent-driven operations are part of the learning goal. It also offers the simplest Git-push deploy story for a first-time deployer, matching the project's own notes from tech-stack selection. At $7/month (Starter, always-on) the cost is comparable to all alternatives, and the free tier cold-start risk is eliminated with that single upgrade. The `PORT` binding gotcha and the tight 512MB Starter RAM are known and bounded risks; both have clear mitigations.

## Platform Comparison

| Platform | CLI-first | Managed/Serverless | Agent-readable docs | Stable deploy API | MCP / Integration | Total |
|---|---|---|---|---|---|---|
| **Render** | Pass | Pass | Pass | Pass | Pass | **5/5** |
| **Fly.io** | Pass | Pass | Partial | Pass | Fail | 3.5/5 |
| **Railway** | Pass | Pass | Partial | Pass | Fail | 3.5/5 |
| Cloudflare Workers | — | — | — | — | — | **Dropped** (no JVM) |
| Vercel | — | — | — | — | — | **Dropped** (no JVM) |
| Netlify | — | — | — | — | — | **Dropped** (no JVM) |

### Shortlisted Platforms

#### 1. Render (Recommended)

Only platform with GA MCP server (`mcp.render.com`) and live `llms.txt` (confirmed 2026-05-24). Simplest Git-push deploy, `render.yaml` for IaC, Docker build with layer caching, `render` CLI with `--json` output, managed Postgres/Redis co-located if needed later. Free tier sleeps (cold-start risk) — solved by Starter at $7/mo. The project's own tech-stack notes flagged Render as preferred for its Git integration.

#### 2. Fly.io

Best-in-class CLI (`flyctl`), always-on containers at ~$6–7/mo (1GB RAM), structured JSON output on all key commands, no cold-start risk with `auto_stop = false`, GitHub MDX docs. Loses on agent integration (no MCP server, no llms.txt). Rollback requires redeploying a prior image digest — workable but less ergonomic than a single command. Good runner-up if MCP is not a priority.

#### 3. Railway

Clean DX, Nixpacks auto-detects Maven, `railway rollback` command exists, co-located Postgres/Redis (not needed here — using Supabase). $5 base + $2–6 compute ≈ $7–11/mo. No MCP server, no llms.txt. The `PORT` injection gotcha applies (`server.port=${PORT:8080}` must be set). Falls behind Render on every agent-friendliness criterion.

## Anti-Bias Cross-Check: Render

### Devil's Advocate — Weaknesses

1. **Free tier is a demo trap.** 15-minute idle spin-down + JVM startup = 30–90 second cold-start. Any stakeholder demo after idle time will look broken. Mitigation: upgrade to Starter ($7/mo) before any demo.
2. **Rollback is not a single CLI command.** Requires `render deploys list`, extract deploy ID, then `render deploys rollback [SERVICE_ID] [DEPLOY_ID]`. More friction than `fly deploy --image <digest>`.
3. **512MB Starter is tight for Spring Boot under load.** Spring Boot 4 with Tomcat, Security, Jackson, and Actuator idles at 200–350MB. Under concurrent requests there's minimal headroom. Standard plan ($25/mo) is comfortable; Starter needs explicit `-XX:MaxRAMPercentage=75` tuning.
4. **MCP server scope is limited.** Supports spin up, metrics, env vars, basic log queries. Complex log filtering or metric aggregation still requires the CLI or REST API. The docs MCP (`mcp.inkeep.com`) is experimental and may be discontinued without notice.
5. **New pricing model (April 2026) is still rolling out.** Tutorials and community answers written before 2026-04-23 reference old plan names (Starter/Standard/Pro) that may map differently to current tiers. Verify current pricing at render.com/pricing before committing.

### Pre-Mortem — How This Could Fail

The team deployed AutoSkanerAI to Render's free tier to avoid spending money during development. For the first two weeks everything worked — they were actively building, so the app never slept. During the final stakeholder demo, the app had been idle for three hours. The first request hung for 60 seconds while the JVM cold-started inside a 0.1-CPU container. The demo was derailed. They upgraded to Starter ($7/mo) and the sleep problem went away — but under the load of simulated concurrent car listing analyses, the 512MB heap started showing GC pressure. Response times crept up. They bumped to Standard ($25/mo), tripling the expected cost against the "basically free" assumption they'd started with. Meanwhile, setting up rollback took a full session because they expected a single command and instead had to learn the deploy-ID workflow. The MCP server, which they'd chosen Render partly for, turned out not to support log streaming in the way their agent needed — they fell back to `render logs` CLI anyway. Net result: three of the six MVP weeks had meaningful operational friction that could have been anticipated.

### Unknown Unknowns

1. **Port is `10000`, not `8080`.** Render injects `PORT=10000`. Spring Boot defaults to `8080`. Without `server.port=${PORT:10000}` in `application.properties`, Render's health check fails and the service hangs in "deploying" indefinitely. This is the #1 Spring Boot + Render failure.
2. **Zero-downtime deploys require a health check.** Render only cuts over traffic after a health check passes. Add `spring-boot-starter-actuator` and configure the `/actuator/health` path as the health check probe in `render.yaml` — otherwise deploys may cause brief downtime.
3. **Supabase traffic goes over the public internet.** Render's private network only connects services within the same Render region. Since Supabase is external, all DB calls cross the public internet. Latency and Supabase connection pool limits apply.
4. **Dashboard changes don't sync back to `render.yaml`.** If you configure anything via the dashboard (env vars, instance type, health check), it won't be reflected in `render.yaml`. Keep all configuration in `render.yaml` from day one to avoid drift.
5. **Build minutes can accumulate.** Each push triggers a full Docker build. Without an optimized multi-stage `Dockerfile` and `.dockerignore`, Maven downloads all dependencies on every build, making builds slow and potentially hitting plan build-minute limits during active development sprints.

## Operational Story

- **Preview deploys**: each branch push creates a preview service URL if "Auto-Deploy" is enabled in the dashboard or `render.yaml`. Preview services use the same instance type as configured — budget for free-tier previews sleeping. Protect preview URLs with Render's built-in basic auth if the app has any sensitive data.
- **Secrets**: env vars stored in Render's environment groups or per-service variables. Set via `render env set KEY=value` (CLI) or the dashboard. Not committed to the repo. `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` / `SPRING_PROFILES_ACTIVE` set here. Rotation requires re-deploy (rolling restart).
- **Rollback**: `render deploys list [SERVICE_ID] --output json` to get deploy IDs, then `render deploys rollback [SERVICE_ID] [DEPLOY_ID]`. Typical time-to-revert: 2–4 minutes (Docker pull + Spring Boot startup). DB migrations do not roll back automatically — run compensating migrations manually if needed.
- **Approval**: agent may perform unattended: deploy, set env vars, list services, tail logs, trigger rollback. Human required for: deleting a service, rotating the Render API key, billing changes, and any Supabase destructive operations.
- **Logs**: `render logs [SERVICE_ID] --tail` for live streaming. `render logs [SERVICE_ID] --output json` for structured output agent can parse. Log history searchable in the dashboard. Log streams to external providers (Datadog, Papertrail) available on paid plans — not needed for MVP.

## Risk Register

| Risk | Source | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| Free tier cold-start breaks demos | Devil's advocate | H | H | Upgrade to Starter ($7/mo) before first demo; never use free tier for reviewed sessions |
| PORT=10000 misconfiguration | Unknown unknowns | H | H | Set `server.port=${PORT:10000}` in `application.properties` on day one |
| 512MB Starter OOM under load | Devil's advocate | M | M | Set `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`; upgrade to Standard ($25/mo) if heap pressure observed |
| Rollback friction (deploy ID required) | Devil's advocate | M | L | Document the rollback command sequence in a runbook; consider a `/rollback` skill |
| MCP server limited scope | Devil's advocate | M | L | Use `render` CLI with `--json` for operations MCP doesn't cover; treat MCP as a bonus, not a primary path |
| Dashboard/render.yaml drift | Unknown unknowns | M | M | Configure everything in `render.yaml` from day one; never touch the dashboard for config |
| Supabase latency over public internet | Unknown unknowns | L | M | Choose a Render region geographically close to Supabase instance (e.g., Frankfurt for EU) |
| Pricing model in transition (post-April 2026) | Devil's advocate | M | L | Verify current plan names and limits at render.com/pricing before provisioning |
| New pricing model confusion | Pre-mortem | M | M | Treat $25/mo Standard as the realistic always-on budget for a comfortable JVM app |
| Missing health check → deploy downtime | Unknown unknowns | M | M | Add `spring-boot-starter-actuator` and configure `/actuator/health` as health check probe in `render.yaml` |

## Getting Started

1. **Add `server.port` and health check to `application.properties`:**
   ```properties
   server.port=${PORT:10000}
   management.endpoints.web.exposure.include=health
   ```

2. **Write a `Dockerfile` in `backend/`** using a multi-stage Maven build:
   ```dockerfile
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

3. **Create `render.yaml` at the repo root** (adjust `rootDir` and `healthCheckPath`):
   ```yaml
   services:
     - type: web
       name: autoskaner-ai-backend
       runtime: docker
       rootDir: backend
       plan: starter
       healthCheckPath: /actuator/health
       envVars:
         - key: SPRING_PROFILES_ACTIVE
           value: mock
         - key: ANTHROPIC_API_KEY
           sync: false
   ```

4. **Connect the GitHub repo** at dashboard.render.com → New Web Service → select `PrzemyslawPrzeworski/AutoSkanerAI`. Render detects `render.yaml` and auto-configures.

5. **Set secrets** via the Render dashboard or CLI:
   ```bash
   render env set ANTHROPIC_API_KEY=<your-key> --service autoskaner-ai-backend
   ```

6. **Add the Render MCP server** to Claude Code for agent-native operations:
   ```bash
   claude mcp add --transport http render https://mcp.render.com/mcp \
     --header "Authorization: Bearer <RENDER_API_KEY>"
   ```

## Out of Scope

The following were not evaluated in this research:
- Docker image configuration details beyond the starter `Dockerfile` above
- CI/CD pipeline setup (GitHub Actions auto-deploy on merge)
- Production-scale architecture (multi-region, HA, DR)
- Angular frontend deployment (Render static site or Cloudflare Pages — separate decision)
