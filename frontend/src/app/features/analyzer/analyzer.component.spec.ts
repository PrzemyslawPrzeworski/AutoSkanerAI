import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { AnalyzerComponent } from './analyzer.component';
import { AnalysisService } from '../../core/services/analysis.service';
import { SavedAnalysisService } from '../../core/services/saved-analysis.service';
import {
  AnalysisRequest,
  AnalysisResponse,
  AnalysisResult,
  CepikResult,
} from '../../shared/models/analysis.models';
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';

const mockResult: AnalysisResult = {
  extracted: {
    make: 'BMW',
    model: '3',
    year: 2020,
    priceAmount: null,
    priceCurrency: null,
    mileageKm: null,
    fuel: null,
    transmission: null,
    originCountry: null,
    sellerType: null,
    serviceHistoryMentioned: null,
    accidentClaim: null,
    vinPresent: null,
    vin: null,
    registrationPlate: 'WX00000',
    firstRegistrationDate: '2020-05-01',
  },
  equipment: [],
  riskFlags: [],
  sellerQuestions: [],
  scores: { completeness: 70, equipment: 70, risk: 70, value: 70, overall: 70 },
  verdict: { code: 'WORTH_CHECKING', label: 'warto sprawdzić' },
  meta: {
    provider: 'mock',
    model: 'mock-v1',
    latencyMs: 10,
    generatedAt: new Date().toISOString(),
  },
};

const missingInputs: CepikResult = {
  status: 'MISSING_INPUTS',
  vin: null,
  firstRegistrationDatePl: null,
  deregisteredDate: null,
  originCountry: null,
  ownerCount: null,
  mileageStamps: null,
  damageRecords: null,
  lookupUrl: 'https://historiapojazdu.gov.pl',
  fetchedAt: new Date().toISOString(),
  make: null,
  model: null,
  vehicleType: null,
  yearOfManufacture: null,
  registrationStatus: null,
  technicalInspectionStatus: null,
  ocInsuranceValid: null,
  vehicleLost: null,
  odometerRolledBack: null,
  registrationProvince: null,
  events: null,
};

function response(overrides: Partial<AnalysisResponse> = {}): AnalysisResponse {
  return {
    fetchStatus: 'text',
    fetchFailureReason: null,
    analysis: mockResult,
    cepikResult: null,
    marketPriceContext: null,
    ...overrides,
  };
}

describe('AnalyzerComponent', () => {
  const analysisSpy = { analyze: vi.fn() };
  const savedSpy = { save: vi.fn() };

  beforeEach(async () => {
    analysisSpy.analyze.mockReset();
    savedSpy.save.mockReset();

    await TestBed.configureTestingModule({
      imports: [AnalyzerComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AnalysisService, useValue: analysisSpy },
        { provide: SavedAnalysisService, useValue: savedSpy },
      ],
    }).compileComponents();
  });

  function create() {
    const fixture = TestBed.createComponent(AnalyzerComponent);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  it('initial state: form visible, no error, no result, no banner', () => {
    const comp = create();

    expect(comp.analysisResponse()).toBeNull();
    expect(comp.error()).toBeNull();
    expect(comp.fetchFailedBanner()).toBeNull();
    expect(comp.loading()).toBe(false);
  });

  it('blank submit sets error signal', () => {
    const comp = create();

    comp.submit();

    expect(comp.error()).toContain('Wklej URL');
    expect(analysisSpy.analyze).not.toHaveBeenCalled();
  });

  it('text submit → response signal set', () => {
    const comp = create();
    analysisSpy.analyze.mockReturnValue(of(response()));

    comp.listingText.set('BMW 3 2020');
    comp.submit();

    expect(comp.analysisResponse()?.analysis).toEqual(mockResult);
    expect(comp.loading()).toBe(false);
  });

  it('URL submit → url_failed sets fetchFailedBanner', () => {
    const comp = create();
    analysisSpy.analyze.mockReturnValue(
      of(response({ fetchStatus: 'url_failed', fetchFailureReason: 'blocked', analysis: null })),
    );

    comp.url.set('https://otomoto.pl/listing/1');
    comp.submit();

    expect(comp.fetchFailedBanner()).toBeTruthy();
    expect(comp.analysisResponse()).toBeNull();
  });

  // Manual fields alone are a valid third input mode — no URL, no pasted advert.
  it('manual fields alone are enough to submit', () => {
    const comp = create();
    analysisSpy.analyze.mockReturnValue(of(response({ fetchStatus: 'manual' })));

    comp.vehicleDraft.update((d) => ({ ...d, make: 'Toyota', year: '2022' }));
    comp.submit();

    expect(comp.error()).toBeNull();
    const sent = analysisSpy.analyze.mock.calls.at(-1)![0] as AnalysisRequest;
    expect(sent.manual).toEqual(expect.objectContaining({ make: 'Toyota', year: 2022 }));
  });

  // A mistyped VIN would cost a full analysis and come back with an empty history panel that
  // reads as the registry's fault, so it is caught before the request goes out.
  it('a malformed VIN blocks submission with an explanation', () => {
    const comp = create();

    comp.vehicleDraft.update((d) => ({ ...d, vin: 'TOO-SHORT' }));
    comp.listingText.set('BMW 3 2020');
    comp.submit();

    expect(comp.error()).toContain('17 znaków');
    expect(analysisSpy.analyze).not.toHaveBeenCalled();
  });

  // The plate and the first registration date are published in the advert; the VIN is not. So the
  // VIN on its own has to be a complete thing to type — never a form the user must finish.
  it('a VIN on its own is enough, with no plate or registration date', () => {
    const comp = create();
    analysisSpy.analyze.mockReturnValue(of(response()));

    comp.url.set('https://www.otomoto.pl/x');
    comp.vehicleDraft.update((d) => ({ ...d, vin: 'NMTBZ3BE40R000000' }));
    comp.submit();

    expect(comp.error()).toBeNull();
    const sent = analysisSpy.analyze.mock.calls.at(-1)![0] as AnalysisRequest;
    expect(sent.vin).toBe('NMTBZ3BE40R000000');
    expect(sent.registrationPlate).toBeUndefined();
    expect(sent.firstRegistrationDate).toBeUndefined();
  });

  it('typed registry fields are sent as overrides', () => {
    const comp = create();
    analysisSpy.analyze.mockReturnValue(of(response()));

    comp.listingText.set('BMW 3 2020');
    comp.vehicleDraft.update((d) => ({
      ...d,
      vin: ' nmtbz3be40r000000 ',
      registrationPlate: 'wx00000',
      firstRegistrationDate: '2020-05-01',
    }));
    comp.submit();

    const sent = analysisSpy.analyze.mock.calls.at(-1)![0] as AnalysisRequest;
    expect(sent.vin).toBe('NMTBZ3BE40R000000');
    expect(sent.registrationPlate).toBe('WX00000');
    expect(sent.firstRegistrationDate).toBe('2020-05-01');
  });

  it('MISSING_INPUTS offers the registry follow-up and prefills what was extracted', () => {
    const comp = create();
    analysisSpy.analyze.mockReturnValue(of(response({ cepikResult: missingInputs })));

    comp.listingText.set('BMW 3 2020');
    comp.submit();

    expect(comp.registryInputsMissing()).toBe(true);
    // The advert carried the plate and the date; only the VIN is left for the user to type.
    expect(comp.vehicleDraft().registrationPlate).toBe('WX00000');
    expect(comp.vehicleDraft().firstRegistrationDate).toBe('2020-05-01');
    expect(comp.vehicleDraft().vin).toBe('');
  });

  // Naming the one field that is actually missing, rather than restating that three are required
  // at a user looking at two filled boxes.
  it('the recheck names only the fields that are still missing', () => {
    const comp = create();
    analysisSpy.analyze.mockReturnValue(of(response({ cepikResult: missingInputs })));
    comp.listingText.set('BMW 3 2020');
    comp.submit();
    analysisSpy.analyze.mockClear();

    comp.recheckWithRegistryData();

    expect(comp.error()).toContain('numer VIN');
    // Both came from the advert, so neither is asked for again.
    expect(comp.error()).not.toContain('numer rejestracyjny');
    expect(comp.error()).not.toContain('data pierwszej rejestracji');
    expect(analysisSpy.analyze).not.toHaveBeenCalled();
  });

  it('the recheck runs once the VIN is typed, using the plate and date from the advert', () => {
    const comp = create();
    analysisSpy.analyze.mockReturnValue(of(response({ cepikResult: missingInputs })));
    comp.listingText.set('BMW 3 2020');
    comp.submit();
    analysisSpy.analyze.mockClear();

    comp.vehicleDraft.update((d) => ({ ...d, vin: 'NMTBZ3BE40R000000' }));
    comp.recheckWithRegistryData();

    expect(comp.error()).toBeNull();
    const sent = analysisSpy.analyze.mock.calls.at(-1)![0] as AnalysisRequest;
    expect(sent.vin).toBe('NMTBZ3BE40R000000');
    expect(sent.registrationPlate).toBe('WX00000');
    expect(sent.firstRegistrationDate).toBe('2020-05-01');
  });

  it('HTTP 400 maps to Polish validation message', () => {
    const comp = create();
    const err = new HttpErrorResponse({
      status: 400,
      error: { messages: ['url: nieprawidłowy format URL'] },
    });
    analysisSpy.analyze.mockReturnValue(throwError(() => err));

    comp.url.set('https://not-really');
    comp.submit();

    expect(comp.error()).toContain('url: nieprawidłowy');
  });

  it('HTTP 502 maps to LLM unavailable message', () => {
    const comp = create();
    const err = new HttpErrorResponse({ status: 502, error: {} });
    analysisSpy.analyze.mockReturnValue(throwError(() => err));

    comp.listingText.set('test');
    comp.submit();

    expect(comp.error()).toContain('niedostępny');
  });

  it('reset clears all state, including the typed vehicle data', () => {
    const comp = create();
    analysisSpy.analyze.mockReturnValue(of(response()));

    comp.listingText.set('BMW');
    comp.vehicleDraft.update((d) => ({ ...d, vin: 'NMTBZ3BE40R000000' }));
    comp.submit();
    expect(comp.analysisResponse()).not.toBeNull();

    comp.reset();

    expect(comp.url()).toBe('');
    expect(comp.listingText()).toBe('');
    expect(comp.vehicleDraft().vin).toBe('');
    expect(comp.analysisResponse()).toBeNull();
    expect(comp.error()).toBeNull();
    expect(comp.fetchFailedBanner()).toBeNull();
  });

  it('prefills the save title from the extraction', () => {
    const comp = create();
    analysisSpy.analyze.mockReturnValue(of(response()));

    comp.listingText.set('BMW 3 2020');
    comp.submit();

    expect(comp.saveTitle()).toBe('BMW 3 2020');
  });

  it('falls back to a dated title when the extraction found no identity', () => {
    const comp = create();
    const anonymous = response();
    anonymous.analysis!.extracted = {
      ...anonymous.analysis!.extracted,
      make: null,
      model: null,
      year: null,
    };
    analysisSpy.analyze.mockReturnValue(of(anonymous));

    comp.listingText.set('cokolwiek');
    comp.submit();

    // Never the word "brak" or an empty title: this string is the label the user scans the saved
    // list by, so an invented make would be worse than a date.
    expect(comp.saveTitle()).toContain('Analiza z ');
  });

  /**
   * Found in a live walkthrough: the mock extraction returned a year and no make or model, and the
   * title prefilled as "2019". The year qualifies an identity; it is not one.
   */
  it('does not prefill a bare year as the title', () => {
    const comp = create();
    const yearOnly = response();
    yearOnly.analysis!.extracted = {
      ...yearOnly.analysis!.extracted,
      make: null,
      model: null,
      year: 2019,
    };
    analysisSpy.analyze.mockReturnValue(of(yearOnly));

    comp.listingText.set('cokolwiek');
    comp.submit();

    expect(comp.saveTitle()).not.toBe('2019');
    expect(comp.saveTitle()).toContain('Analiza z ');
  });

  it('keeps the year when there is an identity for it to qualify', () => {
    const comp = create();
    const modelOnly = response();
    modelOnly.analysis!.extracted = {
      ...modelOnly.analysis!.extracted,
      make: null,
      model: 'Corolla',
      year: 2019,
    };
    analysisSpy.analyze.mockReturnValue(of(modelOnly));

    comp.listingText.set('cokolwiek');
    comp.submit();

    expect(comp.saveTitle()).toBe('Corolla 2019');
  });

  it('saves the analysis exactly as it came back, with the typed title and url', () => {
    const comp = create();
    const analysed = response();
    analysisSpy.analyze.mockReturnValue(of(analysed));
    savedSpy.save.mockReturnValue(of({ summary: { id: 41 }, analysis: analysed }));

    comp.url.set(' https://otomoto.pl/oferta/1 ');
    comp.submit();
    comp.saveTitle.set('  Corolla z OLX  ');
    comp.saveNote.set(' Dzwonić po 18:00 ');
    comp.saveAnalysis();

    expect(savedSpy.save).toHaveBeenCalledWith({
      title: 'Corolla z OLX',
      note: 'Dzwonić po 18:00',
      sourceUrl: 'https://otomoto.pl/oferta/1',
      analysis: analysed,
    });
    expect(comp.savedId()).toBe(41);
    expect(comp.saveError()).toBeNull();
  });

  /** No `userId` field exists to send; the server takes the owner from the bearer token. */
  it('sends no owner with the save', () => {
    const comp = create();
    const analysed = response();
    analysisSpy.analyze.mockReturnValue(of(analysed));
    savedSpy.save.mockReturnValue(of({ summary: { id: 41 }, analysis: analysed }));

    comp.listingText.set('BMW');
    comp.submit();
    comp.saveAnalysis();

    expect(Object.keys(savedSpy.save.mock.calls[0][0])).toEqual([
      'title',
      'note',
      'sourceUrl',
      'analysis',
    ]);
    expect(savedSpy.save.mock.calls[0][0].userId).toBeUndefined();
  });

  it('omits a blank note and a blank url rather than sending empty strings', () => {
    const comp = create();
    analysisSpy.analyze.mockReturnValue(of(response()));
    savedSpy.save.mockReturnValue(of({ summary: { id: 41 }, analysis: response() }));

    comp.listingText.set('BMW');
    comp.submit();
    comp.saveAnalysis();

    expect(savedSpy.save.mock.calls[0][0].note).toBeUndefined();
    expect(savedSpy.save.mock.calls[0][0].sourceUrl).toBeUndefined();
  });

  it('refuses a blank title without calling the API', () => {
    const comp = create();
    analysisSpy.analyze.mockReturnValue(of(response()));

    comp.listingText.set('BMW');
    comp.submit();
    comp.saveTitle.set('   ');
    comp.saveAnalysis();

    expect(savedSpy.save).not.toHaveBeenCalled();
    expect(comp.saveError()).toContain('Podaj nazwę');
  });

  it('does not save the same analysis twice', () => {
    const comp = create();
    analysisSpy.analyze.mockReturnValue(of(response()));
    savedSpy.save.mockReturnValue(of({ summary: { id: 41 }, analysis: response() }));

    comp.listingText.set('BMW');
    comp.submit();
    comp.saveAnalysis();
    comp.saveAnalysis();

    expect(savedSpy.save).toHaveBeenCalledTimes(1);
  });

  it('reports a failed save and leaves it retryable', () => {
    const comp = create();
    analysisSpy.analyze.mockReturnValue(of(response()));
    savedSpy.save.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 500, error: {} })),
    );

    comp.listingText.set('BMW');
    comp.submit();
    comp.saveAnalysis();

    expect(comp.saveError()).toContain('Nie udało się zapisać');
    expect(comp.savedId()).toBeNull();
    expect(comp.saving()).toBe(false);
  });

  /**
   * A re-check produces a different analysis under the same result view, so a previous save must stop
   * counting as this one's — otherwise the screen claims the new verdict is saved when the stored row
   * holds the old one.
   */
  it('a re-analysis clears the saved confirmation', () => {
    const comp = create();
    analysisSpy.analyze.mockReturnValue(of(response()));
    savedSpy.save.mockReturnValue(of({ summary: { id: 41 }, analysis: response() }));

    comp.listingText.set('BMW');
    comp.submit();
    comp.saveAnalysis();
    expect(comp.savedId()).toBe(41);

    comp.submit();

    expect(comp.savedId()).toBeNull();
  });

  it('reset clears the save box too', () => {
    const comp = create();
    analysisSpy.analyze.mockReturnValue(of(response()));
    savedSpy.save.mockReturnValue(of({ summary: { id: 41 }, analysis: response() }));

    comp.listingText.set('BMW');
    comp.submit();
    comp.saveAnalysis();

    comp.reset();

    expect(comp.saveTitle()).toBe('');
    expect(comp.saveNote()).toBe('');
    expect(comp.savedId()).toBeNull();
    expect(comp.saveError()).toBeNull();
  });
});
