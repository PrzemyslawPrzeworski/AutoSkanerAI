import { TestBed } from '@angular/core/testing';
import { ComponentFixture } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { AnalysisResultComponent } from './analysis-result.component';
import { AnalysisResult } from '../../../../shared/models/analysis.models';

function makeResult(overrides: Partial<AnalysisResult> = {}): AnalysisResult {
  return {
    extracted: {
      make: 'BMW',
      model: '3',
      year: 2020,
      priceAmount: 45000,
      priceCurrency: 'PLN',
      mileageKm: 120000,
      fuel: 'benzyna',
      transmission: 'manual',
      originCountry: 'Polska',
      sellerType: 'prywatny',
      serviceHistoryMentioned: true,
      accidentClaim: 'bezwypadkowy',
      vinPresent: true,
      vin: null,
      registrationPlate: null,
      firstRegistrationDate: null,
    },
    equipment: [
      { name: 'klimatyzacja', status: 'CONFIRMED', note: null },
      { name: 'ABS', status: 'MISSING', note: 'Nie wspomniano' },
    ],
    riskFlags: [
      { code: 'NO_ACCIDENT_DECLARATION', severity: 'HIGH', description: 'Brak deklaracji' },
    ],
    sellerQuestions: ['Pytanie 1', 'Pytanie 2'],
    scores: { completeness: 80, equipment: 50, risk: 75, value: 60, overall: 66 },
    verdict: { code: 'NEEDS_MORE_INFO', label: 'sprawdź po doprecyzowaniu' },
    meta: {
      provider: 'mock',
      model: 'mock-v1',
      latencyMs: 10,
      generatedAt: '2026-06-01T12:00:00Z',
    },
    ...overrides,
  };
}

describe('AnalysisResultComponent', () => {
  let fixture: ComponentFixture<AnalysisResultComponent>;

  function create(result: AnalysisResult): AnalysisResultComponent {
    fixture = TestBed.createComponent(AnalysisResultComponent);
    fixture.componentRef.setInput('result', result);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AnalysisResultComponent],
    }).compileComponents();
  });

  it('WORTH_CHECKING verdict → green class on verdict card', () => {
    create(makeResult({ verdict: { code: 'WORTH_CHECKING', label: 'warto sprawdzić' } }));
    const card = fixture.debugElement.query(By.css('.verdict-card'));
    expect(card.classes['verdict-green']).toBe(true);
  });

  it('HIGH_RISK_SKIP verdict → red class on verdict card', () => {
    create(makeResult({ verdict: { code: 'HIGH_RISK_SKIP', label: 'wysokie ryzyko' } }));
    const card = fixture.debugElement.query(By.css('.verdict-card'));
    expect(card.classes['verdict-red']).toBe(true);
  });

  it('null extracted.make renders as —', () => {
    const result = makeResult();
    result.extracted = { ...result.extracted, make: null };
    create(result);
    const cells = fixture.debugElement.queryAll(By.css('.data-table td:last-child'));
    expect(cells[0].nativeElement.textContent.trim()).toBe('—');
  });

  it('6 risk flags: initially shows 4, expand shows all 6', () => {
    const flags = Array.from({ length: 6 }, (_, i) => ({
      code: `FLAG_${i}`,
      severity: 'MEDIUM' as const,
      description: `Desc ${i}`,
    }));
    const comp = create(makeResult({ riskFlags: flags }));

    expect(comp.visibleFlags().length).toBe(4);

    comp.toggleFlags();
    fixture.detectChanges();
    expect(comp.visibleFlags().length).toBe(6);
  });

  it('3 risk flags: no expand link', () => {
    const flags = Array.from({ length: 3 }, (_, i) => ({
      code: `F${i}`,
      severity: 'LOW' as const,
      description: `D${i}`,
    }));
    create(makeResult({ riskFlags: flags }));
    const link = fixture.debugElement.query(By.css('.expand-link'));
    expect(link).toBeNull();
  });

  it('MISSING equipment item has danger tag', () => {
    const comp = create(makeResult());
    expect(comp.equipmentSeverity('MISSING')).toBe('danger');
  });

  // A category scored 0 is a real score, not a missing one: CategoryScores.equipment is a
  // primitive int on the backend, and MockAiAnalysisService computes 0 when none of the
  // listed equipment is CONFIRMED. PrimeNG hides its own label at value === 0 and nests it
  // inside a fill that is 0% wide, so the number is rendered outside the bar instead —
  // otherwise a scored zero draws a blank track, indistinguishable from "not scored", which
  // is the project's absence-is-not-a-value rule arriving from the opposite direction.
  it('a category scored 0 still renders its number', () => {
    create(
      makeResult({ scores: { completeness: 80, equipment: 0, risk: 75, value: 60, overall: 53 } }),
    );
    const values = fixture.debugElement.queryAll(By.css('.score-row .score-value'));
    expect(values.map((v) => v.nativeElement.textContent.trim())).toEqual([
      '80%',
      '0%',
      '75%',
      '60%',
      '53%',
    ]);
  });

  // Pins what assistive tech gets, which is not what the eye gets: PrimeNG binds
  // aria-valuenow on the host regardless of the label it hides, so a screen reader already
  // announced the 0 that a sighted user could not see. Keep both paths asserted so a future
  // change to one does not silently drop the other.
  it('every score bar exposes its value to assistive tech, including 0', () => {
    create(
      makeResult({ scores: { completeness: 80, equipment: 0, risk: 75, value: 60, overall: 53 } }),
    );
    const bars = fixture.debugElement.queryAll(By.css('.score-row [role="progressbar"]'));
    expect(bars.map((b) => b.nativeElement.getAttribute('aria-valuenow'))).toEqual([
      '80',
      '0',
      '75',
      '60',
      '53',
    ]);
  });

  it('sellerQuestions list renders correct count', () => {
    create(makeResult({ sellerQuestions: ['Q1', 'Q2', 'Q3'] }));
    const items = fixture.debugElement.queryAll(By.css('.questions-list li'));
    expect(items.length).toBe(3);
  });
});
