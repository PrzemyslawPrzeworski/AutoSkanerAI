import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AnalysisService } from './analysis.service';
import { AnalysisResponse } from '../../shared/models/analysis.models';

describe('AnalysisService', () => {
  let service: AnalysisService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(AnalysisService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('posts to /api/analyses and returns AnalysisResponse', () => {
    const mockResponse: AnalysisResponse = {
      fetchStatus: 'text',
      fetchFailureReason: null,
      analysis: null
    };

    service.analyze({ listingText: 'BMW 3 2020' }).subscribe(res => {
      expect(res.fetchStatus).toBe('text');
    });

    const req = httpMock.expectOne('/api/analyses');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ listingText: 'BMW 3 2020' });
    req.flush(mockResponse);
  });

  it('sends url field when provided', () => {
    service.analyze({ url: 'https://otomoto.pl/listing/1' }).subscribe();

    const req = httpMock.expectOne('/api/analyses');
    expect(req.request.body).toEqual({ url: 'https://otomoto.pl/listing/1' });
    req.flush({ fetchStatus: 'url_failed', fetchFailureReason: 'blocked', analysis: null });
  });

  it('propagates 400 HttpErrorResponse', () => {
    service.analyze({ listingText: '' }).subscribe({
      error: err => expect(err.status).toBe(400)
    });

    httpMock.expectOne('/api/analyses').flush(
      { status: 400, error: 'Błąd walidacji', messages: [], timestamp: '' },
      { status: 400, statusText: 'Bad Request' }
    );
  });

  it('propagates 502 HttpErrorResponse', () => {
    service.analyze({ listingText: 'test' }).subscribe({
      error: err => expect(err.status).toBe(502)
    });

    httpMock.expectOne('/api/analyses').flush(
      { status: 502, error: 'Błąd usługi LLM', messages: [], timestamp: '' },
      { status: 502, statusText: 'Bad Gateway' }
    );
  });
});
