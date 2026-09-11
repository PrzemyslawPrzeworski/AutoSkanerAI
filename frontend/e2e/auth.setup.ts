/**
 * AUTH SETUP — runs once, before every spec, and produces the `storageState` they all start from.
 *
 * `E2E-RULES.md` forbids logging in inside a test, and F-03 is what finally gives that rule a
 * subject: `/` sits behind `authGuard`, so a bare `page.goto('/')` now redirects to `/login` and
 * every spec in this directory would fail on its first locator. Doing the login here rather than in
 * each spec keeps a contract test about the contract.
 *
 * Two facts about the session design make this file work at all, and both are load-bearing:
 *
 *   - **The access token lives in memory, so it is NOT in `storageState`.** Only the refresh token is
 *     persisted (`localStorage`), which is the whole point of the split — see `auth.service.ts`. What
 *     a restored context therefore has is a *renewable* session, not a live one: `authGuard` calls
 *     `restoreSession()`, which spends one `/api/auth/refresh` before the first render. That call is
 *     part of every spec's page load now; a spec asserting on request counts must expect it.
 *   - **A refresh token is a stateless JWT and is not consumed by use.** There is no server-side
 *     record and no revocation (backend/CLAUDE.md § Persistence), so the same stored token restores
 *     any number of parallel contexts. If refresh ever becomes single-use, this file has to mint one
 *     session per worker instead, and every spec will start failing intermittently — that symptom is
 *     the pointer back here.
 *
 * Registering rather than logging in is deliberate: there is no seeded account, no fixture loader and
 * no way to delete one afterwards, so the account this creates is the account it uses. The address is
 * timestamped for the same reason the listing text is — a re-run against a reused server must not
 * collide with the row the previous run left behind.
 */
import { test as setup, expect } from '@playwright/test';
import { STORAGE_STATE } from '../playwright.config';

setup('a registered account leaves a restorable session behind', async ({ page }) => {
  const email = `e2e-${Date.now()}@example.pl`;

  await page.goto('/register');

  await page.getByLabel('Adres e-mail').fill(email);
  await page.getByLabel('Hasło').fill('e2e-correct-horse');

  const registered = page.waitForResponse(
    (response) =>
      response.url().includes('/api/auth/register') && response.request().method() === 'POST',
  );
  await page.getByRole('button', { name: 'Utwórz konto' }).click();
  // 201, not 200 — registration creates a row, and `AuthController` says so. Asserted exactly rather
  // than as `ok()` because a redirect or a 204 would also be "not an error" and neither carries the
  // token pair this file exists to capture.
  expect((await registered).status()).toBe(201);

  // The redirect is the signal that the token pair was accepted and `guestGuard` now refuses this
  // page — waiting on the URL rather than on a spinner, per the rules.
  await page.waitForURL('http://localhost:4200/');

  // Not the analyser heading: the point of this assertion is that the app knows *who* is signed in,
  // which is the state `storageState` has to carry. An anonymous page renders no address.
  await expect(page.getByText(email)).toBeVisible();

  await page.context().storageState({ path: STORAGE_STATE });
});
