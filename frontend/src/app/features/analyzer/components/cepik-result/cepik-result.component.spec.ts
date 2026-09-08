import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { CepikResultComponent } from './cepik-result.component';
import { CepikResult, CepikStatus } from '../../../../shared/models/analysis.models';

/**
 * The reader for the one rule this app is not allowed to get wrong: **absence of accident data
 * means unknown, never clean.**
 *
 * <h2>Why this file exists</h2>
 *
 * The backend spends five test classes and a booted-context `@SpringBootTest` proving that `null`
 * and `[]` stay distinguishable all the way onto the wire — `CepikResultSerialisationTest` asserts
 * the raw body *contains* `"damageRecords":null` and *does not contain* `"damageRecords":[]`. Until
 * this file, nothing read the other end of that wire. `damages()` applies `?? []`, one character
 * from collapsing the distinction the whole backend effort preserves, and `damageState()` was the
 * only thing standing between that and the sentence "Rejestr nie zawiera zgłoszonych szkód
 * istotnych" — a positive claim of a clean registry record about a vehicle nobody could check.
 *
 * Measured before this file was written: inverting `damageState()` left all 276 tests green.
 * `analysis-result.component.spec.ts` does instantiate this component, but never sets
 * `cepikResult`, so `@if (cepikResult())` is false and every computed stays unevaluated; both e2e
 * specs submit listing text with no VIN, so they only ever reach `MISSING_INPUTS`. See
 * `context/changes/analysis-flow-analysis/research.md` §4.1 — 12 enforcement points, 7.5 tested,
 * and all four untested ones were here.
 *
 * <h2>The shape of these tests</h2>
 *
 * Every arm asserts its own rendered sentence **and** the absence of the sentence it must not be
 * confused with. A test that only asserts its own copy is satisfied by an edit that merges two arms
 * into one string; the negative companion is what makes that impossible. The distinctness test at
 * the end guards the same thing from the other side, following the pattern
 * `market-price-panel.component.spec.ts:122` established.
 *
 * The class comment on `CepikResultComponent` records that this shipped once as a bug: a parse
 * failure looked like a clean history.
 *
 * <h2>Both guards were mutation-checked, not assumed</h2>
 *
 * A spec that passes on its first run has proved nothing about the code — only that it agrees with
 * it. Each guard was therefore inverted and the suite re-run:
 *
 * 1. `damageState()` rewritten to read `this.damages()` (the plausible "simplification", since that
 *    computed already exists and looks equivalent) — **2 tests fail**, and the reported actual value
 *    is the defect stated in full: `Received: "Rejestr nie zawiera zgłoszonych szkód istotnych…"`
 *    for a registry that was never read.
 * 2. The template's `@else if (cepikResult()!.mileageStamps === null)` rewritten to
 *    `@else if (mileageStamps().length === 0)` — **1 test fails**.
 *
 * Both mutations were reverted. Before this file existed, mutation 1 left all 276 tests green.
 */
function makeResult(overrides: Partial<CepikResult> = {}): CepikResult {
  return {
    status: 'FOUND' as CepikStatus,
    vin: 'NMTBZ3BE40R000000',
    firstRegistrationDatePl: '2019-04-11',
    deregisteredDate: null,
    originCountry: 'POLSKA',
    ownerCount: 2,
    mileageStamps: [{ date: '2025-06-01', mileageKm: 96000 }],
    damageRecords: [],
    lookupUrl: 'https://historiapojazdu.gov.pl/',
    fetchedAt: '2026-09-08T10:00:00Z',
    make: 'TOYOTA',
    model: 'COROLLA',
    vehicleType: 'SAMOCHÓD OSOBOWY',
    yearOfManufacture: 2019,
    registrationStatus: 'ZAREJESTROWANY',
    technicalInspectionStatus: 'AKTUALNE',
    ocInsuranceValid: true,
    vehicleLost: false,
    odometerRolledBack: false,
    registrationProvince: 'MAZOWIECKIE',
    events: null,
    ...overrides,
  };
}

describe('CepikResultComponent', () => {
  let fixture: ComponentFixture<CepikResultComponent>;

  function create(result: CepikResult | null): CepikResultComponent {
    fixture = TestBed.createComponent(CepikResultComponent);
    fixture.componentRef.setInput('cepikResult', result);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  /** Normalised so an assertion does not depend on where the template wraps a line. */
  function textOf(selector: string): string {
    const el = fixture.debugElement.query(By.css(selector));
    return el === null ? '' : el.nativeElement.textContent.replace(/\s+/g, ' ').trim();
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CepikResultComponent],
    }).compileComponents();
  });

  // ---------------------------------------------------------------------------------------------
  // The three damage states. This is the whole point of the file.
  // ---------------------------------------------------------------------------------------------

  it('damageRecords null → says the history could not be read, and makes no claim about damage', () => {
    // The load-bearing test in this file. `damages()` applies `?? []`, so deriving `damageState()`
    // from it instead of from the raw field would make this arm unreachable and every unread
    // registry would render as "nie zawiera zgłoszonych szkód". That is the 2026-08-26 defect,
    // restated on the frontend.
    create(makeResult({ damageRecords: null }));

    const damage = textOf('.cepik-damage');
    expect(damage).toContain('brak danych to nie to samo co brak szkód');
    expect(damage).not.toContain('Rejestr nie zawiera zgłoszonych szkód istotnych');
    expect(damage).not.toContain('Szkody istotne zgłoszone do rejestru');
  });

  it('damageRecords [] → says the registry reported nothing, without claiming it was unread', () => {
    create(makeResult({ damageRecords: [] }));

    const damage = textOf('.cepik-damage');
    expect(damage).toContain('Rejestr nie zawiera zgłoszonych szkód istotnych');
    // And it must keep its own hedge: a registry with no report is not a car with no repairs.
    expect(damage).toContain('Nie wyklucza to napraw');
    expect(damage).not.toContain('brak danych to nie to samo co brak szkód');
  });

  it('damageRecords populated → leads with the finding and its count', () => {
    create(
      makeResult({
        damageRecords: [
          {
            date: '2022-03-14',
            description: 'Szkoda istotna',
            insurer: 'PZU SA',
            categories: ['elementy nośne'],
          },
        ],
      }),
    );

    const damage = textOf('.cepik-damage');
    // The count is asserted inside the sentence, not as a bare '1' — the record's own date string
    // contains a '1', so `toContain('1')` would pass whatever the count rendered as.
    expect(damage).toContain('Szkody istotne zgłoszone do rejestru (1)');
    expect(damage).not.toContain('Rejestr nie zawiera zgłoszonych szkód istotnych');
    expect(damage).not.toContain('brak danych to nie to samo co brak szkód');
  });

  it('the three damage sentences are mutually distinct', () => {
    // Three tests each asserting their own expected string all still pass if a later edit collapses
    // two arms into one sentence — each test would simply be updated to the new shared value.
    // Comparing the three *actual* rendered texts is what cannot be satisfied that way.
    create(makeResult({ damageRecords: null }));
    const unknown = textOf('.cepik-damage');

    create(makeResult({ damageRecords: [] }));
    const noneReported = textOf('.cepik-damage');

    create(
      makeResult({
        damageRecords: [{ date: null, description: null, insurer: null, categories: null }],
      }),
    );
    const reported = textOf('.cepik-damage');

    // All three must be non-empty before "they differ" means anything: `textOf` returns '' for a
    // missing element, and '' !== '' is false but '' !== 'anything' is true, so an arm that
    // disappeared entirely would otherwise pass the distinctness check.
    expect(unknown).not.toBe('');
    expect(noneReported).not.toBe('');
    expect(reported).not.toBe('');

    expect(unknown).not.toBe(noneReported);
    expect(noneReported).not.toBe(reported);
    expect(unknown).not.toBe(reported);
  });

  // ---------------------------------------------------------------------------------------------
  // mileageStamps carries the same three-state distinction, on a quieter row
  // ---------------------------------------------------------------------------------------------

  it('mileageStamps null → names the read failure instead of showing no row', () => {
    create(makeResult({ mileageStamps: null }));

    const card = textOf('.cepik-card');
    expect(card).toContain('nie udało się odczytać z rejestru');
  });

  it('mileageStamps [] → no reading and no read-failure row either', () => {
    // The distinction the `@else if` exists for. An empty timeline is a registry that answered with
    // no odometer readings; rendering "nie udało się odczytać" there would blame the tool for a
    // fact, and rendering nothing for `null` would hide a failure.
    create(makeResult({ mileageStamps: [] }));

    const card = textOf('.cepik-card');
    expect(card).not.toContain('nie udało się odczytać z rejestru');
    expect(card).not.toContain('Ostatni odczyt przebiegu');
  });

  // ---------------------------------------------------------------------------------------------
  // The degraded statuses. Each must disclaim, and none may borrow the clean-registry sentence.
  // ---------------------------------------------------------------------------------------------

  it('MISSING_INPUTS says outright that no check is not a clean record', () => {
    create(makeResult({ status: 'MISSING_INPUTS', damageRecords: null, mileageStamps: null }));

    const card = textOf('.cepik-missing');
    expect(card).toContain('Brak weryfikacji nie oznacza, że pojazd jest bezszkodowy');
    // The FOUND card and its damage block must not render at all on a degraded status.
    expect(fixture.debugElement.query(By.css('.cepik-damage'))).toBeNull();
  });

  it('LOOKUP_FAILED distinguishes an outage from an absence of damage', () => {
    create(makeResult({ status: 'LOOKUP_FAILED', damageRecords: null, mileageStamps: null }));

    const card = textOf('.cepik-failed');
    expect(card).toContain('To nie jest informacja o braku szkód');
    expect(card).not.toContain('Rejestr nie zawiera zgłoszonych szkód istotnych');
    expect(fixture.debugElement.query(By.css('.cepik-damage'))).toBeNull();
  });

  it('NOT_FOUND blames the inputs, not the vehicle', () => {
    // The one degraded arm that is not about a failure: all three inputs were supplied and the
    // registry had no matching entry. Telling the user their data may not match is actionable;
    // implying the car is unknown to the registry is not, and implying it is clean is forbidden.
    create(makeResult({ status: 'NOT_FOUND', damageRecords: null, mileageStamps: null }));

    const card = textOf('.cepik-not-found');
    expect(card).toContain('Rejestr nie zwrócił pojazdu dla podanych danych');
    expect(card).not.toContain('Rejestr nie zawiera zgłoszonych szkód istotnych');
    expect(fixture.debugElement.query(By.css('.cepik-damage'))).toBeNull();
  });

  it('null result renders nothing at all', () => {
    create(null);

    expect(fixture.debugElement.query(By.css('.cepik-section'))).toBeNull();
  });
});
