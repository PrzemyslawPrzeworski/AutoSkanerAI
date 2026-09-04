/**
 * RISK — the frontend/backend contract, the one gap that is structurally invisible to both suites.
 *
 * `frontend/src/app/shared/models/analysis.models.ts` is a hand-written mirror of the backend's
 * Java records. The backend suite asserts its own JSON; the frontend suite asserts against
 * hand-written doubles. So nothing else in this repo ever puts real backend JSON in front of the
 * real component, and a field rename or a shape change ships green with the panel rendering
 * nothing. test-plan.md §6.7 records the near-miss: `sampleQuality` shipped server-side while the
 * template that reads it did not, and "the server field would have been read by nothing".
 *
 * Seed: `seed.spec.ts`.   Rules: `E2E-RULES.md`.   Risk map: `context/foundation/test-plan.md` §2.
 *
 * Why the assertions compare the DOM against the response's OWN json, rather than against 45000 /
 * 55000 / 70000: hardcoding the mock's constants would make this a test of
 * `MockMarketPriceEnrichmentService` — which test-plan.md §7 excludes on purpose — and it would go
 * red on any harmless change to the mock. Comparing the two sides of the same round trip is what
 * makes this a contract test: it asks whether the number the server sent is the number the user
 * sees, whatever that number happens to be.
 *
 * Two things deliberately NOT asserted here:
 *
 *   - `sampleQuality`'s rendered arms. This response is `SUFFICIENT`, which renders no caveat — and
 *     a missing field renders no caveat either, so "no caveat is visible" cannot fail when the
 *     contract breaks. That is a hallucinated assertion, so instead the field's PRESENCE is
 *     asserted on the json (which is falsifiable) and the four rendered arms stay with
 *     `market-price-panel.component.spec.ts`, which owns them.
 *   - the analysis scores and verdict. The mock profile's scoring is input-dependent (the same
 *     listing scored `overall: 41` via curl and `35` through the browser on a one-word edit), so
 *     pinning them here would be pinning the mock. The market-price context is input-independent,
 *     which is exactly why it is the right target for a contract assertion.
 */
import { test, expect } from '@playwright/test';

/**
 * Digits only, so the assertions survive a locale change. Angular renders `| number: '1.0-0'` with
 * the app's locale — today en-US, so `45000` reaches the DOM as "45,000" — and a switch to `pl`
 * would make it "45 000". Either is a formatting decision, not a contract break, and this test must
 * not go red for one. `\s` already covers the NBSP and narrow-NBSP that some locales group with, so
 * only the comma needs naming.
 */
const digitsOnly = (text: string): string => text.replace(/[\s,]/g, '');

test("the market-price panel renders the server's own numbers", async ({ page }) => {
  const listingText =
    `Toyota Corolla 2019, 1.8 Hybrid, 85000 km, cena 72000 PLN, ` +
    `pierwszy wlasciciel, serwisowany w ASO. [e2e-market-${Date.now()}]`;

  await page.goto('/');
  await page.getByLabel('lub wklej treść ogłoszenia').fill(listingText);

  const analysis = page.waitForResponse(
    (response) => response.url().includes('/api/analyses') && response.request().method() === 'POST'
  );
  await page.getByRole('button', { name: 'Analizuj' }).click();

  const response = await analysis;
  expect(response.status()).toBe(200);
  const market = (await response.json()).marketPriceContext;

  // Guards on the oracle, not the behaviour: if the profile ever stops returning a healthy sample
  // this test has nothing to compare and must say so, rather than passing on an empty panel.
  expect(market, 'the response carried no marketPriceContext at all').toBeTruthy();
  expect(market.status).toBe('OK');

  // The field from the near-miss. Falsifiable in the direction that actually broke: a backend that
  // stops sending it fails here, where "no caveat rendered" could not tell the difference.
  // `toBeTruthy`, deliberately, not `not.toBeNull()` — a field the backend drops arrives as
  // `undefined`, which passes `not.toBeNull()`. That would have been a hallucinated assertion
  // protecting nothing in precisely the direction §6.7 records.
  expect(market.sampleQuality, 'an OK sample must carry a quality judgement').toBeTruthy();

  // The header is a real button (role="button" + keyboard handlers), and it carries min and max.
  const panel = page.getByRole('button', { name: /Kontekst cenowy/ });
  await expect(panel).toBeVisible();
  expect(digitsOnly(await panel.innerText())).toContain(
    digitsOnly(`Kontekst cenowy: ${market.minPricePln} – ${market.maxPricePln} PLN`)
  );

  // Median and sample size are behind the collapsed panel, so this is a real user action, not setup.
  await panel.click();

  // `main` rather than a container selector: role-based, and stable against the panel's markup.
  // Each expectation is label-anchored, so a bare "12" elsewhere on the page cannot satisfy it.
  const main = page.getByRole('main');
  await expect(main.getByText('Mediana')).toBeVisible();
  const rendered = digitsOnly(await main.innerText());
  expect(rendered).toContain(digitsOnly(`Mediana ${market.medianPricePln} PLN`));
  expect(rendered).toContain(digitsOnly(`Liczba ogłoszeń ${market.sampleSize}`));

  // queryUrl is the last field the panel consumes, and the only one that reaches an attribute.
  await expect(page.getByRole('link', { name: /Otomoto/ })).toHaveAttribute(
    'href',
    market.queryUrl
  );
});
