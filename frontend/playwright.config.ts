import { defineConfig, devices } from '@playwright/test';

/**
 * E2E config. See `e2e/E2E-RULES.md` for the rules every spec here follows, and
 * `context/foundation/test-plan.md` §3 for why this layer is deliberately tiny.
 *
 * The whole point of this layer is that it is the ONLY place real backend JSON meets the real
 * component. So the boundaries that stay real are the ones the other two suites fake:
 *
 *   real      browser -> Angular HttpClient -> dev-server proxy -> Spring controller -> Jackson
 *   mocked    the LLM, Jina Reader, and the vehicle registry
 *
 * The mocking happens SERVER-side, via `SPRING_PROFILES_ACTIVE=mock`, and it has to: the backend
 * calls those three itself, so a browser-level `page.route()` would never see them. That profile
 * also makes the run deterministic and fast — a real analysis is ~27 s of live LLM call, which is
 * neither. Nothing about the transport, the serialisation, or the rendering is stubbed.
 *
 * Specs live in `e2e/`, not `src/`, on purpose: `tsconfig.spec.json` includes only
 * `src/**` + '/' + '*.spec.ts', so Vitest never tries to collect a Playwright spec.
 */
/*
 * Playwright runs a `webServer.command` through the platform shell — `cmd.exe` on Windows, `sh`
 * elsewhere — and the two disagree about the wrapper. `cmd` rejects `./mvnw` outright ("'.' is not
 * recognized as an internal or external command"), and a bare `mvnw.cmd` is not found either, since
 * cmd's implicit current-directory lookup is not something to rely on — so it needs `.\mvnw.cmd`
 * spelled out. `sh` needs `./mvnw`, because the repo root is not on PATH. Pick per platform rather
 * than committing the one form that happens to run on the author's machine.
 */
const MVNW = process.platform === 'win32' ? '.\\mvnw.cmd' : './mvnw';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: process.env.CI ? 'list' : [['list'], ['html', { open: 'never' }]],

  use: {
    baseURL: 'http://localhost:4200',
    trace: 'on-first-retry',
  },

  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],

  /*
   * Both servers, so `npx playwright test` works from cold.
   *
   * The backend inherits JAVA_HOME from the environment rather than hardcoding a path — a machine
   * path in committed config is wrong, and Maven's own error is loud enough. `-o` (offline) matches
   * what the git hooks do; if you have just bumped a dependency, run `./mvnw test` online once.
   */
  webServer: [
    {
      command: `${MVNW} -o spring-boot:run`,
      cwd: '../backend',
      // Readiness probe, verified to answer 200 {"status":"UP"}. Not `/api/analyses` — that is
      // POST-only and answers a probe GET with 500, which reads as "never became ready".
      url: 'http://localhost:10000/actuator/health',
      reuseExistingServer: !process.env.CI,
      timeout: 120_000,
      env: { SPRING_PROFILES_ACTIVE: 'mock' },
      stdout: 'ignore',
      stderr: 'pipe',
    },
    {
      command: 'npm start',
      url: 'http://localhost:4200',
      reuseExistingServer: !process.env.CI,
      timeout: 120_000,
      stdout: 'ignore',
      stderr: 'pipe',
    },
  ],
});
