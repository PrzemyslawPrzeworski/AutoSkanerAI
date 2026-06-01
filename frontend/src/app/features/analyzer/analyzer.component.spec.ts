import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { By } from '@angular/platform-browser';
import { AnalyzerComponent } from './analyzer.component';
import { AnalysisService } from '../../core/services/analysis.service';
import { AnalysisResponse, AnalysisResult } from '../../shared/models/analysis.models';
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';

const mockResult: AnalysisResult = {
  extracted: {
    make: 'BMW', model: '3', year: 2020, priceAmount: null, priceCurrency: null,
    mileageKm: null, fuel: null, transmission: null, originCountry: null,
    sellerType: null, serviceHistoryMentioned: null, accidentClaim: null, vinPresent: null
  },
  equipment: [],
  riskFlags: [],
  sellerQuestions: [],
  scores: { completeness: 70, equipment: 70, risk: 70, value: 70, overall: 70 },
  verdict: { code: 'WORTH_CHECKING', label: 'warto sprawdzić' },
  meta: { provider: 'mock', model: 'mock-v1', latencyMs: 10, generatedAt: new Date().toISOString() }
};

describe('AnalyzerComponent', () => {
  let analysisSpy: jasmine.SpyObj<AnalysisService>;

  beforeEach(async () => {
    analysisSpy = jasmine.createSpyObj('AnalysisService', ['analyze']);

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

  it('initial state: form visible, no error, no result, no banner', () => {
    const fixture = TestBed.createComponent(AnalyzerComponent);
    fixture.detectChanges();
    const comp = fixture.componentInstance;

    expect(comp.result()).toBeNull();
    expect(comp.error()).toBeNull();
    expect(comp.fetchFailedBanner()).toBeNull();
    expect(comp.loading()).toBe(false);
  });

  it('blank submit sets error signal', () => {
    const fixture = TestBed.createComponent(AnalyzerComponent);
    fixture.detectChanges();
    const comp = fixture.componentInstance;

    comp.submit();

    expect(comp.error()).toBe('Wklej URL lub tekst ogłoszenia');
    expect(analysisSpy.analyze).not.toHaveBeenCalled();
  });

  it('text submit → result signal set, form hidden', fakeAsync(() => {
    const fixture = TestBed.createComponent(AnalyzerComponent);
    fixture.detectChanges();
    const comp = fixture.componentInstance;
    const response: AnalysisResponse = { fetchStatus: 'text', fetchFailureReason: null, analysis: mockResult };
    analysisSpy.analyze.and.returnValue(of(response));

    comp.listingText.set('BMW 3 2020');
    comp.submit();
    tick();

    expect(comp.result()).toEqual(mockResult);
    expect(comp.loading()).toBe(false);
  }));

  it('URL submit → url_failed sets fetchFailedBanner', fakeAsync(() => {
    const fixture = TestBed.createComponent(AnalyzerComponent);
    fixture.detectChanges();
    const comp = fixture.componentInstance;
    const response: AnalysisResponse = { fetchStatus: 'url_failed', fetchFailureReason: 'blocked', analysis: null };
    analysisSpy.analyze.and.returnValue(of(response));

    comp.url.set('https://otomoto.pl/listing/1');
    comp.submit();
    tick();

    expect(comp.fetchFailedBanner()).toBeTruthy();
    expect(comp.result()).toBeNull();
  }));

  it('HTTP 400 maps to Polish validation message', fakeAsync(() => {
    const fixture = TestBed.createComponent(AnalyzerComponent);
    fixture.detectChanges();
    const comp = fixture.componentInstance;
    const err = new HttpErrorResponse({ status: 400, error: { messages: ['url: nieprawidłowy format URL'] } });
    analysisSpy.analyze.and.returnValue(throwError(() => err));

    comp.url.set('not-a-url');
    comp.submit();
    tick();

    expect(comp.error()).toContain('url: nieprawidłowy');
  }));

  it('HTTP 502 maps to LLM unavailable message', fakeAsync(() => {
    const fixture = TestBed.createComponent(AnalyzerComponent);
    fixture.detectChanges();
    const comp = fixture.componentInstance;
    const err = new HttpErrorResponse({ status: 502, error: {} });
    analysisSpy.analyze.and.returnValue(throwError(() => err));

    comp.listingText.set('test');
    comp.submit();
    tick();

    expect(comp.error()).toContain('niedostępny');
  }));

  it('reset clears all state', fakeAsync(() => {
    const fixture = TestBed.createComponent(AnalyzerComponent);
    fixture.detectChanges();
    const comp = fixture.componentInstance;
    const response: AnalysisResponse = { fetchStatus: 'text', fetchFailureReason: null, analysis: mockResult };
    analysisSpy.analyze.and.returnValue(of(response));

    comp.listingText.set('BMW');
    comp.submit();
    tick();
    expect(comp.result()).not.toBeNull();

    comp.reset();

    expect(comp.url()).toBe('');
    expect(comp.listingText()).toBe('');
    expect(comp.result()).toBeNull();
    expect(comp.error()).toBeNull();
    expect(comp.fetchFailedBanner()).toBeNull();
  }));
});
