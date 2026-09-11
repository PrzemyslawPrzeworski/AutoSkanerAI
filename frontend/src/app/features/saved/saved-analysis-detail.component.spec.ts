import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { convertToParamMap } from '@angular/router';
import { of, throwError } from 'rxjs';
import { SavedAnalysisDetailComponent } from './saved-analysis-detail.component';
import { SavedAnalysisService } from '../../core/services/saved-analysis.service';
import { AnalysisResponse, AnalysisResult } from '../../shared/models/analysis.models';
import { SavedAnalysisDetail } from '../../shared/models/saved-analysis.models';

const savedResult: AnalysisResult = {
  extracted: {
    make: 'Toyota',
    model: 'Corolla',
    year: 2019,
    priceAmount: 64900,
    priceCurrency: 'PLN',
    mileageKm: 118500,
    fuel: null,
    transmission: null,
    originCountry: null,
    sellerType: null,
    serviceHistoryMentioned: null,
    accidentClaim: null,
    vinPresent: null,
    vin: null,
    registrationPlate: null,
    firstRegistrationDate: null,
  },
  equipment: [],
  riskFlags: [],
  sellerQuestions: [],
  scores: { completeness: 70, equipment: 65, risk: 60, value: 75, overall: 68 },
  verdict: { code: 'WORTH_CHECKING', label: 'warto sprawdzić' },
  meta: { provider: 'mock', model: 'mock-v1', latencyMs: 10, generatedAt: '2026-09-11T10:00:00Z' },
};

const savedAnalysis: AnalysisResponse = {
  fetchStatus: 'text',
  fetchFailureReason: null,
  analysis: savedResult,
  cepikResult: null,
  marketPriceContext: null,
};

const detail: SavedAnalysisDetail = {
  summary: {
    id: 41,
    title: 'Corolla z OLX',
    note: 'Dzwonić po 18:00',
    sourceUrl: 'https://example.pl/oferta',
    make: 'Toyota',
    model: 'Corolla',
    productionYear: 2019,
    priceAmount: 64900,
    priceCurrency: 'PLN',
    mileageKm: 118500,
    verdictCode: 'WORTH_CHECKING',
    overallScore: 68,
    createdAt: '2026-09-11T10:00:00Z',
    updatedAt: '2026-09-11T10:00:00Z',
  },
  analysis: savedAnalysis,
};

function setup(id: string, get: ReturnType<typeof vi.fn>) {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      { provide: SavedAnalysisService, useValue: { get } },
      {
        provide: ActivatedRoute,
        useValue: { snapshot: { paramMap: convertToParamMap({ id }) } },
      },
    ],
  });
  const fixture = TestBed.createComponent(SavedAnalysisDetailComponent);
  fixture.detectChanges();
  return { fixture, component: fixture.componentInstance };
}

describe('SavedAnalysisDetailComponent', () => {
  it('loads the saved analysis by the route id', () => {
    const get = vi.fn(() => of(detail));
    const { component } = setup('41', get);

    expect(get).toHaveBeenCalledWith(41);
    expect(component.loading()).toBe(false);
    expect(component.detail()?.summary.title).toBe('Corolla z OLX');
    // The payload comes back as a whole AnalysisResponse, which is what lets the detail view render
    // through the same result component a fresh analysis uses.
    expect(component.detail()?.analysis.analysis?.verdict.code).toBe('WORTH_CHECKING');
  });

  it('says a missing or foreign analysis is not available, without distinguishing the two', () => {
    const get = vi.fn(() =>
      throwError(() => new HttpErrorResponse({ status: 404, statusText: 'Not Found' })),
    );
    const { component } = setup('999', get);

    expect(component.detail()).toBeNull();
    expect(component.error()).toBe('Ta analiza nie istnieje lub nie należy do Ciebie.');
  });

  it('never asks the API for a non-numeric id', () => {
    const get = vi.fn();
    const { component } = setup('nonsense', get);

    expect(get).not.toHaveBeenCalled();
    expect(component.error()).toBe('Nie znaleziono takiej analizy.');
    expect(component.loading()).toBe(false);
  });

  it('reports a server failure separately from a missing row', () => {
    const get = vi.fn(() =>
      throwError(() => new HttpErrorResponse({ status: 500, statusText: 'Server Error' })),
    );
    const { component } = setup('41', get);

    expect(component.error()).toContain('Nie udało się wczytać');
  });
});
