import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { AnalyzerComponent } from './analyzer.component';
import { AnalysisService } from '../../core/services/analysis.service';
import {
  AnalysisRequest,
  AnalysisResponse,
  AnalysisResult,
  CepikResult
} from '../../shared/models/analysis.models';
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';

const mockResult: AnalysisResult = {
  extracted: {
    make: 'BMW', model: '3', year: 2020, priceAmount: null, priceCurrency: null,
    mileageKm: null, fuel: null, transmission: null, originCountry: null,
    sellerType: null, serviceHistoryMentioned: null, accidentClaim: null, vinPresent: null,
    vin: null, registrationPlate: 'WX00000', firstRegistrationDate: '2020-05-01'
  },
  equipment: [],
  riskFlags: [],
  sellerQuestions: [],
  scores: { completeness: 70, equipment: 70, risk: 70, value: 70, overall: 70 },
  verdict: { code: 'WORTH_CHECKING', label: 'warto sprawdzić' },
  meta: { provider: 'mock', model: 'mock-v1', latencyMs: 10, generatedAt: new Date().toISOString() }
};

const missingInputs: CepikResult = {
  status: 'MISSING_INPUTS',
  vin: null, firstRegistrationDatePl: null, deregisteredDate: null, originCountry: null,
  ownerCount: null, mileageStamps: null, damageRecords: null,
  lookupUrl: 'https://historiapojazdu.gov.pl', fetchedAt: new Date().toISOString(),
  make: null, model: null, vehicleType: null, yearOfManufacture: null,
  registrationStatus: null, technicalInspectionStatus: null, ocInsuranceValid: null,
  vehicleLost: null, odometerRolledBack: null, registrationProvince: null, events: null
};

function response(overrides: Partial<AnalysisResponse> = {}): AnalysisResponse {
  return {
    fetchStatus: 'text',
    fetchFailureReason: null,
    analysis: mockResult,
    cepikResult: null,
    marketPriceContext: null,
    ...overrides
  };
}

describe('AnalyzerComponent', () => {
  const analysisSpy = { analyze: vi.fn() };

  beforeEach(async () => {
    analysisSpy.analyze.mockReset();

    await TestBed.configureTestingModule({
      imports: [AnalyzerComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AnalysisService, useValue: analysisSpy }
      ]
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

  it('text submit → response signal set', fakeAsync(() => {
    const comp = create();
    analysisSpy.analyze.mockReturnValue(of(response()));

    comp.listingText.set('BMW 3 2020');
    comp.submit();
    tick();

    expect(comp.analysisResponse()?.analysis).toEqual(mockResult);
    expect(comp.loading()).toBe(false);
  }));

  it('URL submit → url_failed sets fetchFailedBanner', fakeAsync(() => {
    const comp = create();
    analysisSpy.analyze.mockReturnValue(
      of(response({ fetchStatus: 'url_failed', fetchFailureReason: 'blocked', analysis: null }))
    );

    comp.url.set('https://otomoto.pl/listing/1');
    comp.submit();
    tick();

    expect(comp.fetchFailedBanner()).toBeTruthy();
    expect(comp.analysisResponse()).toBeNull();
  }));

  // Manual fields alone are a valid third input mode — no URL, no pasted advert.
  it('manual fields alone are enough to submit', fakeAsync(() => {
    const comp = create();
    analysisSpy.analyze.mockReturnValue(of(response({ fetchStatus: 'manual' })));

    comp.vehicleDraft.update(d => ({ ...d, make: 'Toyota', year: '2022' }));
    comp.submit();
    tick();

    expect(comp.error()).toBeNull();
    const sent = analysisSpy.analyze.mock.calls.at(-1)![0] as AnalysisRequest;
    expect(sent.manual).toEqual(expect.objectContaining({ make: 'Toyota', year: 2022 }));
  }));

  // A mistyped VIN would cost a full analysis and come back with an empty history panel that
  // reads as the registry's fault, so it is caught before the request goes out.
  it('a malformed VIN blocks submission with an explanation', () => {
    const comp = create();

    comp.vehicleDraft.update(d => ({ ...d, vin: 'TOO-SHORT' }));
    comp.listingText.set('BMW 3 2020');
    comp.submit();

    expect(comp.error()).toContain('17 znaków');
    expect(comp.vehicleFieldsOpen()).toBe(true);
    expect(analysisSpy.analyze).not.toHaveBeenCalled();
  });

  it('typed registry fields are sent as overrides', fakeAsync(() => {
    const comp = create();
    analysisSpy.analyze.mockReturnValue(of(response()));

    comp.listingText.set('BMW 3 2020');
    comp.vehicleDraft.update(d => ({
      ...d,
      vin: ' nmtbz3be40r000000 ',
      registrationPlate: 'wx00000',
      firstRegistrationDate: '2020-05-01'
    }));
    comp.submit();
    tick();

    const sent = analysisSpy.analyze.mock.calls.at(-1)![0] as AnalysisRequest;
    expect(sent.vin).toBe('NMTBZ3BE40R000000');
    expect(sent.registrationPlate).toBe('WX00000');
    expect(sent.firstRegistrationDate).toBe('2020-05-01');
  }));

  it('MISSING_INPUTS offers the registry follow-up and prefills what was extracted', fakeAsync(() => {
    const comp = create();
    analysisSpy.analyze.mockReturnValue(of(response({ cepikResult: missingInputs })));

    comp.listingText.set('BMW 3 2020');
    comp.submit();
    tick();

    expect(comp.registryInputsMissing()).toBe(true);
    // The advert carried the plate and the date; only the VIN is left for the user to type.
    expect(comp.vehicleDraft().registrationPlate).toBe('WX00000');
    expect(comp.vehicleDraft().firstRegistrationDate).toBe('2020-05-01');
    expect(comp.vehicleDraft().vin).toBe('');
  }));

  it('the recheck refuses to run without all three registry fields', fakeAsync(() => {
    const comp = create();
    analysisSpy.analyze.mockReturnValue(of(response({ cepikResult: missingInputs })));
    comp.listingText.set('BMW 3 2020');
    comp.submit();
    tick();
    analysisSpy.analyze.mockClear();

    comp.recheckWithRegistryData();

    expect(comp.error()).toContain('wszystkie trzy');
    expect(analysisSpy.analyze).not.toHaveBeenCalled();
  }));

  it('HTTP 400 maps to Polish validation message', fakeAsync(() => {
    const comp = create();
    const err = new HttpErrorResponse({
      status: 400,
      error: { messages: ['url: nieprawidłowy format URL'] }
    });
    analysisSpy.analyze.mockReturnValue(throwError(() => err));

    comp.url.set('https://not-really');
    comp.submit();
    tick();

    expect(comp.error()).toContain('url: nieprawidłowy');
  }));

  it('HTTP 502 maps to LLM unavailable message', fakeAsync(() => {
    const comp = create();
    const err = new HttpErrorResponse({ status: 502, error: {} });
    analysisSpy.analyze.mockReturnValue(throwError(() => err));

    comp.listingText.set('test');
    comp.submit();
    tick();

    expect(comp.error()).toContain('niedostępny');
  }));

  it('reset clears all state, including the typed vehicle data', fakeAsync(() => {
    const comp = create();
    analysisSpy.analyze.mockReturnValue(of(response()));

    comp.listingText.set('BMW');
    comp.vehicleDraft.update(d => ({ ...d, vin: 'NMTBZ3BE40R000000' }));
    comp.submit();
    tick();
    expect(comp.analysisResponse()).not.toBeNull();

    comp.reset();

    expect(comp.url()).toBe('');
    expect(comp.listingText()).toBe('');
    expect(comp.vehicleDraft().vin).toBe('');
    expect(comp.analysisResponse()).toBeNull();
    expect(comp.error()).toBeNull();
    expect(comp.fetchFailedBanner()).toBeNull();
  }));
});
