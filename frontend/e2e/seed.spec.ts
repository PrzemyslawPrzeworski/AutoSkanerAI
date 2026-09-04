/**
 * SEED TEST — the exemplar every spec in this directory is modelled on.
 *
 * What you show is what you get: a generated test inherits this file's locator strategy, its
 * waiting strategy, and its shape. Read `E2E-RULES.md` before changing anything here.
 *
 * It demonstrates the four patterns:
 *   1. role/label locators        — getByLabel, getByRole; never a CSS selector
 *   2. independence               — its own goto, its own submit, no reliance on another test
 *   3. waits on state, not time   — waitForResponse + toBeVisible; never waitForTimeout
 *   4. a name tied to the outcome — not 'test 1'
 *
 * It is also a real smoke test: it proves the paste -> analyse round trip reaches the browser at
 * all. The contract assertions on specific server fields live in `market-price-contract.spec.ts`.
 */
import { test, expect } from '@playwright/test';

test('a pasted listing comes back as a rendered analysis', async ({ page }) => {
  // Timestamped so a run is traceable in the backend log, and so this line already says the right
  // thing on the day S-03 lands persistence. See E2E-RULES.md, "No persistence yet".
  const listingText =
    `Toyota Corolla 2019, 1.8 Hybrid, 85000 km, cena 72000 PLN, ` +
    `pierwszy wlasciciel, serwisowany w ASO. [e2e-seed-${Date.now()}]`;

  await page.goto('/');

  await page.getByLabel('lub wklej treść ogłoszenia').fill(listingText);

  // Wait for the analysis call itself rather than for the spinner to disappear: the request is the
  // thing that has to succeed, and its absence is the failure this test exists to notice.
  const analysis = page.waitForResponse(
    (response) => response.url().includes('/api/analyses') && response.request().method() === 'POST'
  );
  await page.getByRole('button', { name: 'Analizuj' }).click();
  expect((await analysis).status()).toBe(200);

  // The scores block is a static heading in the result template, so it is stable regardless of what
  // the mock profile scored this particular text — see the note in market-price-contract.spec.ts
  // about which parts of the response are input-dependent.
  await expect(page.getByRole('heading', { name: 'Oceny kategorii' })).toBeVisible();
});
