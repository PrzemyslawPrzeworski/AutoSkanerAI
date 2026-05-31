# Deployment Plan — AutoSkanerAI Frontend on Cloudflare Pages

**Status:** Complete — deployed and verified 2026-05-24
**Platform:** Cloudflare Pages (static hosting)
**Scope:** Frontend only (Angular 21.2). Backend deployment is covered in `deployment-plan-backend.md`.
**Reference:** `context/foundation/infrastructure.md` (researched 2026-05-24)
**Backend URL:** `https://autoskanerai.onrender.com`

---

## Context

The Angular 21.2 frontend has no deployment setup yet. There are no environment files, no SPA routing redirect rule, no CORS configuration on the backend, and no Cloudflare account connected to the repo. `infrastructure.md` deferred frontend deployment as a separate decision and named Cloudflare Pages as the candidate alongside Render Static Sites. Cloudflare Pages is chosen here for its free tier (unlimited requests and bandwidth), global CDN, and automatic preview deployments per branch.

Known gaps to close before a first successful deploy:

| Gap | Risk if skipped |
|---|---|
| Node.js < 20 locally | `npm run build` fails — Angular 21 requires Node 20+ |
| Wrangler CLI not installed | Cannot deploy from CLI or verify build locally |
| No Cloudflare account / GitHub not connected | Cannot create Pages project |
| No `frontend/src/environments/` files | Build uses no `apiUrl` — every API call will fail in production |
| No `_redirects` in `public/` | Direct URL navigation to any Angular route returns a 404 |
| No CORS on Spring backend | Every API call from the browser fails with a CORS error |
| `angular.json` missing `fileReplacements` | Production build never swaps `environment.ts` → `environment.prod.ts` |
| `FRONTEND_URL` not set on Render | Backend CORS locked to hardcoded origins; breaks when custom domain added |
| Node version not pinned on Cloudflare Pages | Build uses Node 18 (Cloudflare default) and fails silently on Angular 21 |

---

## Phase 0 — Prerequisites

### 0.1 — Node.js ≥ 20

> Goal: Angular 21 build toolchain requires Node 20+.

- [ ] 0.1.1 Check: `node --version` — expect `v20.x.x` or `v22.x.x`
- [ ] 0.1.2 If Node 18 or older is active, install Node 20 LTS:
  - **winget:** `winget install OpenJS.NodeJS.LTS`
  - **Direct:** download from [nodejs.org/en/download](https://nodejs.org/en/download/) — choose "LTS"
- [ ] 0.1.3 Verify npm is also updated: `npm --version` — expect `10.x`

**Edge case — multiple Node versions via nvm:**
If `nvm` is installed, run `nvm use 20` (or `nvm install 20 && nvm use 20`). The `.nvmrc` file in `frontend/` can pin the version for the project: create `frontend/.nvmrc` containing `20`.

---

### 0.2 — Wrangler CLI

> Goal: Wrangler is the Cloudflare CLI — the Cloudflare counterpart to the `render` CLI. Used to deploy from the terminal, manage Pages projects, and tail logs.

- [ ] 0.2.1 Check if already installed: `wrangler --version`
- [ ] 0.2.2 If missing, install globally:
  ```bash
  npm install -g wrangler
  ```
- [ ] 0.2.3 Verify: `wrangler --version` — expect `3.x` or `4.x`
- [ ] 0.2.4 **Fix PATH for Git Bash on Windows** — npm global installs land in `~/AppData/Roaming/npm/` which Git Bash may not include. Check first:
  ```bash
  which wrangler || echo "not on PATH"
  ```
  If `not on PATH`, add to `~/.bashrc`:
  ```bash
  export PATH="$HOME/AppData/Roaming/npm:$PATH"
  ```
  Then reload: `source ~/.bashrc` and re-verify with `wrangler --version`

- [ ] 0.2.5 Authenticate via browser OAuth:
  ```bash
  wrangler login
  ```
  Opens a browser → authorise → token saved automatically to `%APPDATA%\wrangler\config.json` on Windows
- [ ] 0.2.6 Verify auth: `wrangler whoami` — expect output like:
  ```
  Getting User settings...
  👋 You are logged in with an OAuth Token, associated with the email przemyslaw.przeworski@gmail.com
  ```

- [ ] 0.2.7 **Set `CLOUDFLARE_API_TOKEN` persistently in `~/.bashrc`** (needed for Wrangler CLI and MCP server — same pattern as `RENDER_API_KEY`):
  ```bash
  export CLOUDFLARE_API_TOKEN=<your-token>   # see Phase 0.4 for how to generate
  ```
  Add this line to `~/.bashrc` so it persists across sessions.

**Edge case — corporate firewall blocks Wrangler OAuth callback:**
Wrangler listens on `localhost:8976` for the OAuth callback. If the port is blocked or the browser tab times out, use API token auth as the fallback (see Phase 0.4 — generate the token first, then set `CLOUDFLARE_API_TOKEN` in the shell and skip `wrangler login`). `wrangler whoami` will still work via the token.

**Edge case — `wrangler login` opens browser but token never saves:**
On Windows, Wrangler sometimes fails to write to `%APPDATA%\wrangler\config.json` if the path doesn't exist yet. Create it manually: `mkdir -p "$APPDATA/wrangler"` then retry `wrangler login`.

**Edge case — multiple Cloudflare accounts:**
Wrangler uses the account associated with the OAuth token. If you have multiple accounts, `wrangler whoami` shows which one is active. To switch, run `wrangler logout` then `wrangler login` with the correct account, or set `CLOUDFLARE_ACCOUNT_ID` env var to target a specific account explicitly.

---

### 0.3 — Cloudflare account and GitHub access

> Goal: Cloudflare Pages can pull commits and trigger deploys on push.

- [ ] 0.3.1 Create a Cloudflare account at [cloudflare.com](https://cloudflare.com) (free; use GitHub login to simplify OAuth)
- [ ] 0.3.2 Verify the account is active: `wrangler whoami` after login (step 0.2.6) should show your email — no further account setup needed at this point
- [ ] 0.3.3 Connect GitHub to Cloudflare Pages:
  - Cloudflare dashboard → **Workers & Pages → Pages → Create → Connect to Git**
  - Click **Connect GitHub** → authorise the Cloudflare GitHub app
  - Under **Repository access**, choose **Only select repositories** → add `AutoSkanerAI`
  - Click **Save** on the GitHub side
- [ ] 0.3.4 Confirm `AutoSkanerAI` appears in the Cloudflare repo picker (same pattern as the Render step — if "No repositories found", the GitHub app permission wasn't saved)

**Edge case — "No repositories found" in Cloudflare repo picker:**
Go to **github.com → Settings → Applications → Installed GitHub Apps → Cloudflare Pages → Configure** and grant access to `AutoSkanerAI`. Same flow as the Render GitHub app connection.

**Edge case — repo is private:**
Private repos work with the GitHub OAuth grant. If the repo is made private after connecting, re-authorise the Cloudflare GitHub app from GitHub Settings → Applications.

**Edge case — Cloudflare account ID:**
Some `wrangler pages` commands require `--account-id`. Find yours at dash.cloudflare.com → right sidebar → "Account ID". Add to `~/.bashrc` for convenience:
```bash
export CLOUDFLARE_ACCOUNT_ID=<your-account-id>
```

---

### 0.4 — Cloudflare API token

> Goal: token required for Wrangler CLI deploys, CI/CD automation, and the Phase 7 MCP server. Must be generated before completing step 0.2.7.

- [ ] 0.4.1 Generate at **dash.cloudflare.com → My Profile → API Tokens → Create Token**
  - Choose **Create Custom Token** (the "Edit Cloudflare Workers" template does not cover Pages)
  - Configure permissions:

    | Permission | Level |
    |---|---|
    | Account → Cloudflare Pages → Edit | Account |
    | Account → Account Settings → Read | Account |

  - Set **Account Resources** to your account (not "All accounts")
  - TTL: leave as no expiry for MVP, or set 1 year
  - Click **Continue to summary → Create Token**

- [ ] 0.4.2 Copy the token immediately — shown **only once**
- [ ] 0.4.3 Store in password manager
- [ ] 0.4.4 Add to `~/.bashrc` (referencing the slot from step 0.2.7):
  ```bash
  export CLOUDFLARE_API_TOKEN=<your-token>
  ```
  Then reload: `source ~/.bashrc`
- [ ] 0.4.5 Verify: `wrangler whoami` — should show your account email without opening a browser

**Edge case — token shows "insufficient permissions" on `wrangler pages` commands:**
The "Edit Cloudflare Workers" template omits Pages permissions. If you used that template, delete the token and create a new one with the custom permissions in step 0.4.1.

**Edge case — token rotation:**
Rotating the token requires updating it in: `~/.bashrc`, any CI secrets (GitHub Actions), and the Claude Code MCP server config (Phase 7). Cloudflare does not auto-rotate tokens.

---

### 0.5 — Confirm Cloudflare account ID in shell

> Goal: some wrangler commands require `--account-id`; having it pre-set avoids repeated lookups.

- [ ] 0.5.1 Find your account ID: dash.cloudflare.com → right-hand sidebar → **Account ID** (32-character hex string)
- [ ] 0.5.2 Add to `~/.bashrc`:
  ```bash
  export CLOUDFLARE_ACCOUNT_ID=<your-account-id>
  ```
- [ ] 0.5.3 Reload and verify: `echo $CLOUDFLARE_ACCOUNT_ID` — expect the 32-char ID

---

### 0.6 — Install frontend dependencies

> Goal: `node_modules` must exist before `npm run build` can run.

- [ ] 0.6.1 Run: `cd frontend && npm install`
- [ ] 0.6.2 Verify: no `npm ERR!` lines in output; `node_modules/` directory exists
- [ ] 0.6.3 Verify Angular CLI is available: `cd frontend && npx ng version` — expect Angular CLI 21.x

**Edge case — `npm install` fails with `EACCES` permission error:**
On Windows with Git Bash, npm sometimes fails on global installs due to permission issues with the AppData path. Run `npm install` (not `npm install -g`) inside `frontend/` — this is a local install and doesn't require elevated permissions.

---

### 0.7 — Verify Angular build output path

> Goal: confirm where `npm run build` writes files so Cloudflare Pages is pointed at the right directory.

- [ ] 0.7.1 Run locally: `cd frontend && npm run build`
- [ ] 0.7.2 Confirm output lands in `dist/frontend/browser/` — this is the Angular 21 `@angular/build:application` default
- [ ] 0.7.3 Confirm `dist/frontend/browser/index.html` exists after the build

**Edge case — output path differs:**
If `angular.json` has an explicit `outputPath` override, the actual path may differ (e.g. `dist/frontend/` without the `browser/` subfolder). Check `angular.json` → `projects.frontend.architect.build.options.outputPath` and use whatever value is there as the Cloudflare Pages "Build output directory".

---

### 0.8 — API keys and secrets inventory

> Goal: all secrets identified and stored before Phase 4 sets them on Cloudflare Pages.

| Key | Where to obtain | Where to set | Notes |
|---|---|---|---|
| `CLOUDFLARE_API_TOKEN` | Cloudflare dashboard (Phase 0.4) | `~/.bashrc` + CI secrets | Never commit to git |
| `CLOUDFLARE_ACCOUNT_ID` | Cloudflare dashboard sidebar (Phase 0.5) | `~/.bashrc` | Not secret, but convenient to have pre-set |
| `FRONTEND_URL` | Assigned after Phase 4 (e.g. `https://autoskaner-ai.pages.dev`) | Render env var (Phase 5) | Set on backend so CORS header is correct |

- [ ] 0.8.1 Confirm `CLOUDFLARE_API_TOKEN` is generated and in `~/.bashrc` (Phase 0.4)
- [ ] 0.8.2 Confirm `CLOUDFLARE_ACCOUNT_ID` is in `~/.bashrc` (Phase 0.5)
- [ ] 0.8.3 Verify no secrets are tracked by git:
  ```bash
  git grep -r "CLOUDFLARE_API_TOKEN\|pages.dev" -- ':!*.md' ':!*.json'
  ```
  Expect zero results

---

## Phase 1 — Angular environment files

> Goal: inject the correct backend URL at build time. Dev hits `localhost:10000`; production hits Render.

- [ ] 1.1 Create `frontend/src/environments/environment.ts`:
  ```typescript
  export const environment = {
    production: false,
    apiUrl: 'http://localhost:10000'
  };
  ```

- [ ] 1.2 Create `frontend/src/environments/environment.prod.ts`:
  ```typescript
  export const environment = {
    production: true,
    apiUrl: 'https://autoskanerai.onrender.com'
  };
  ```

- [ ] 1.3 Add `fileReplacements` to the production build config in `frontend/angular.json`, inside `projects.frontend.architect.build.configurations.production`:
  ```json
  "fileReplacements": [
    {
      "replace": "src/environments/environment.ts",
      "with": "src/environments/environment.prod.ts"
    }
  ]
  ```

- [ ] 1.4 Verify: run `npm run build` — confirm the build succeeds with no "Cannot find module environment" errors

**Edge case — Angular 21 `@angular/build:application` builder:**
The modern builder supports `fileReplacements` identically to the classic `@angular-devkit/build-angular:browser` builder. No migration or extra config needed.

**Edge case — `apiUrl` hardcoded in `environment.prod.ts`:**
Angular is a static SPA — environment variables are baked in at build time, not injected at runtime. If the backend URL changes, update `environment.prod.ts` and redeploy. There is no runtime `window.ENV` pattern needed for MVP.

---

## Phase 2 — SPA routing fix

> Goal: Cloudflare Pages must serve `index.html` for every path so Angular's client-side router handles navigation. Without this, any direct URL (e.g. `https://autoskaner-ai.pages.dev/analyses`) returns a 404.

- [ ] 2.1 Create `frontend/public/_redirects`:
  ```
  /*  /index.html  200
  ```

- [ ] 2.2 Verify the file lands in the build output: after `npm run build`, confirm `dist/frontend/browser/_redirects` exists
  - Angular's `@angular/build:application` copies everything from `public/` to the build output root automatically

**Edge case — `public/` already has a `_redirects`:**
Check `frontend/public/` before creating. Only `favicon.ico` is there in the current scaffold, so no conflict.

**Edge case — Cloudflare Pages `_redirects` syntax:**
The format is `<source> <destination> <status>`. For SPA fallback the status must be `200` (rewrite), not `301` or `302` (redirect). A `301` would cause a redirect loop.

---

## Phase 3 — Backend CORS configuration

> Goal: allow the Cloudflare Pages origin to call the Render backend. Without CORS headers, the browser blocks every API response.

- [ ] 3.1 Create `backend/src/main/java/com/example/autoskaner_ai/common/CorsConfig.java`:
  ```java
  package com.example.autoskaner_ai.common;

  import org.springframework.beans.factory.annotation.Value;
  import org.springframework.context.annotation.Configuration;
  import org.springframework.web.servlet.config.annotation.CorsRegistry;
  import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

  @Configuration
  public class CorsConfig implements WebMvcConfigurer {

      @Value("${frontend.url:https://autoskaner-ai.pages.dev}")
      private String frontendUrl;

      @Override
      public void addCorsMappings(CorsRegistry registry) {
          registry.addMapping("/api/**")
              .allowedOrigins(
                  "http://localhost:4200",
                  frontendUrl
              )
              .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
              .allowedHeaders("*")
              .allowCredentials(false);
      }
  }
  ```

- [ ] 3.2 Add to `render.yaml` under `envVars`:
  ```yaml
  - key: FRONTEND_URL
    sync: false
  ```
  Set the value on Render after the Cloudflare Pages URL is known (Phase 4).

- [ ] 3.3 Add to `backend/src/main/resources/application.properties`:
  ```properties
  frontend.url=${FRONTEND_URL:https://autoskaner-ai.pages.dev}
  ```

- [ ] 3.4 Push backend changes → Render auto-deploys
- [ ] 3.5 Verify CORS headers — use an `/api/` endpoint, not `/actuator/health`:
  ```bash
  curl -s -I -X OPTIONS \
    -H "Origin: https://autoskaner-ai.pages.dev" \
    -H "Access-Control-Request-Method: POST" \
    https://autoskanerai.onrender.com/api/analyses \
    | grep -i "access-control"
  ```
  Expect: `Access-Control-Allow-Origin: https://autoskaner-ai.pages.dev`

  **Important:** CORS is configured only for `/api/**`. Sending the Origin header to `/actuator/health` will return **no** `Access-Control-Allow-Origin` header — this is correct, not a bug. Always test CORS against an `/api/` path.

**Edge case — auth (Spring Security) added later:**
When Spring Security is wired up, `WebMvcConfigurer` CORS config is bypassed by the security filter chain. At that point, add `.cors(cors -> cors.configurationSource(...))` to the `SecurityFilterChain` bean. The `CorsConfig` class can be reused as a `CorsConfigurationSource` bean.

**Edge case — Cloudflare Pages preview branch URLs:**
Preview deployments get URLs like `<hash>.autoskaner-ai.pages.dev`. These are not covered by the single `frontendUrl` origin. For MVP this is acceptable — previews will have CORS blocked. To allow previews, replace the fixed origin with a pattern using `allowedOriginPatterns("https://*.autoskaner-ai.pages.dev")` instead of `allowedOrigins(frontendUrl)`.

---

## Phase 4 — Cloudflare Pages project setup

> Goal: Pages project created, connected to GitHub, and configured to build Angular on every push to `main`.

- [ ] 4.1 In the Cloudflare dashboard → **Pages → Create a project → Connect to Git**
  - Select `PrzemyslawPrzeworski/AutoSkanerAI`
- [ ] 4.2 Configure build settings:

  | Setting | Value |
  |---|---|
  | Framework preset | Angular |
  | Root directory | `frontend` |
  | Build command | `npm run build` |
  | Build output directory | `dist/frontend/browser` |

- [ ] 4.3 Add environment variable in the Pages dashboard → **Settings → Environment variables**:

  | Variable | Value | Environment |
  |---|---|---|
  | `NODE_VERSION` | `20` | Production + Preview |

- [ ] 4.4 Click **Save and Deploy** — first build will run

**Edge case — Node version not pinned:**
Cloudflare Pages defaults to Node 18. Angular 21 requires Node 20+. Without `NODE_VERSION=20` the build fails with: `error NG0908: Angular requires Node.js ...`. Set this variable before the first build.

**Edge case — Root directory resolution:**
`Root directory: frontend` means Cloudflare runs `npm run build` inside `frontend/`. The build output path is then relative to `frontend/`, so `dist/frontend/browser` refers to `frontend/dist/frontend/browser` on the Cloudflare build machine. This is correct.

**Edge case — `package-lock.json` present:**
Cloudflare Pages automatically uses `npm ci` when `package-lock.json` exists (it does), ensuring a reproducible install. No action needed.

**Edge case — `autoDeploy` on all branches:**
Cloudflare Pages creates preview deployments for every branch push by default. These are free and useful for reviewing PRs. Preview URLs are: `https://<branch-name>.autoskaner-ai.pages.dev`. To disable, go to Pages → Settings → Builds & deployments → Branch deploy controls.

---

## Phase 5 — Set `FRONTEND_URL` on Render

> Goal: update Render with the real Cloudflare Pages URL so CORS headers are correct.

- [ ] 5.1 After Phase 4 completes, note the assigned Pages URL (e.g. `https://autoskaner-ai.pages.dev`)
- [ ] 5.2 Set on Render via REST API (CLI v2 has no `env set` command):
  ```bash
  curl -X PUT "https://api.render.com/v1/services/srv-d89ni3i8qa3s73e6fub0/env-vars" \
    -H "Authorization: Bearer $RENDER_API_KEY" \
    -H "Content-Type: application/json" \
    -d '[{"key":"FRONTEND_URL","value":"https://autoskaner-ai.pages.dev"}]'
  ```
  Or via the Render dashboard → Service → Environment → add `FRONTEND_URL`
- [ ] 5.3 Render triggers a rolling restart to pick up the new env var
- [ ] 5.4 Re-run the CORS curl check from Phase 3.5 to confirm the header reflects the new origin

**Edge case — custom domain added later:**
If a custom domain (e.g. `autoskaner.pl`) is pointed at Cloudflare Pages later, update `FRONTEND_URL` on Render to match. Until then `pages.dev` is the correct origin.

---

## Phase 6 — First deploy verification

> Goal: end-to-end smoke test of the deployed frontend.

- [ ] 6.1 Visit `https://autoskaner-ai.pages.dev` — Angular app loads (blank shell is fine at this stage)
- [ ] 6.2 Navigate to any route directly (e.g. `https://autoskaner-ai.pages.dev/test`) — expect Angular to handle it, not a 404 from Cloudflare
- [ ] 6.3 Open browser DevTools → Console — no CORS errors
- [ ] 6.4 Push a trivial commit to `main` (e.g. update a comment) — confirm a new Pages deployment appears in the Cloudflare dashboard within 1 minute

**Edge case — first deploy shows blank white page:**
Open DevTools → Console. If you see `Failed to load resource: 404` for `.js` files, the build output directory is pointing at the wrong path. Double-check `dist/frontend/browser` vs `dist/frontend` in the Pages settings.

**Edge case — CORS error still appears after Phase 3–5:**
Browser caches `OPTIONS` preflight responses. Hard-refresh (`Ctrl+Shift+R`) or open DevTools → Network → Disable cache, then reload.

---

## Phase 7 — Wrangler MCP (optional, AI-native ops)

> Goal: enable agent-driven Cloudflare Pages deploys and log inspection from within Claude Code.

- [ ] 7.1 Add the Cloudflare MCP server:
  ```bash
  claude mcp add --transport stdio cloudflare -- npx -y @cloudflare/mcp-server-cloudflare
  ```
- [ ] 7.2 Ensure `CLOUDFLARE_API_TOKEN` is set in the shell before starting Claude Code (the MCP server reads it from the environment)
- [ ] 7.3 Verify: ask Claude Code "list my Cloudflare Pages projects" — expect `autoskaner-ai` in the result

**Edge case — `@cloudflare/mcp-server-cloudflare` scope:**
As of 2026-05-25 the Cloudflare MCP server is community-maintained and primarily targets Cloudflare Workers. Pages support may be partial. If Pages operations are unavailable via MCP, use `wrangler pages deployment list` and `wrangler pages deploy` from the CLI instead. Treat MCP as a bonus, not the primary ops path.

---

## Verification checklist (end-to-end)

**Prerequisites (Phase 0)**
- [ ] `node --version` → `v20.x` or higher; `npm --version` → `10.x`
- [ ] `wrangler --version` → `3.x` or `4.x`; `which wrangler` resolves (PATH set in `~/.bashrc`)
- [ ] `wrangler whoami` shows Cloudflare account email (OAuth or token auth)
- [ ] `echo $CLOUDFLARE_API_TOKEN` returns the token (set in `~/.bashrc`)
- [ ] `echo $CLOUDFLARE_ACCOUNT_ID` returns the 32-char account ID (set in `~/.bashrc`)
- [ ] Cloudflare account created; GitHub app granted access to `AutoSkanerAI`
- [ ] `AutoSkanerAI` repo visible in Cloudflare Pages repo picker
- [ ] `cd frontend && npm install` succeeds; `node_modules/` exists
- [ ] `cd frontend && npm run build` succeeds; `dist/frontend/browser/index.html` exists
- [ ] `git grep -r "CLOUDFLARE_API_TOKEN" -- ':!*.md'` returns zero results

**Build (Phases 1–2)**
- [ ] `cd frontend && npm run build` succeeds with zero errors
- [ ] `dist/frontend/browser/index.html` exists
- [ ] `dist/frontend/browser/_redirects` exists and contains `/*  /index.html  200`

**Backend CORS (Phase 3)**
- [ ] `curl -s -I -X OPTIONS -H "Origin: https://autoskaner-ai.pages.dev" -H "Access-Control-Request-Method: POST" https://autoskanerai.onrender.com/api/analyses | grep -i access-control` returns `Access-Control-Allow-Origin: https://autoskaner-ai.pages.dev`
  Note: testing against `/actuator/health` will return no CORS headers — correct, since CORS is only on `/api/**`.

**Deploy (Phases 4–6)**
- [ ] `https://autoskaner-ai.pages.dev` loads Angular app without console errors
- [ ] Direct route navigation returns Angular app, not a 404
- [ ] Push to `main` triggers automatic Pages redeploy (confirm in Cloudflare dashboard)
- [ ] No CORS errors in browser DevTools after a real API call

---

## Out of scope (this plan)

- Custom domain setup (e.g. `autoskaner.pl`) — post-MVP
- Cloudflare Access / authentication at the CDN level — not needed for MVP
- Content Security Policy headers (`_headers` file) — post-MVP
- GitHub Actions CI pipeline for the frontend — separate decision
- Analytics / Web Vitals monitoring — post-MVP
