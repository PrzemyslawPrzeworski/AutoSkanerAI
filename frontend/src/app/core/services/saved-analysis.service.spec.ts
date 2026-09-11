import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { SavedAnalysisService } from './saved-analysis.service';
import { AnalysisResponse } from '../../shared/models/analysis.models';
import {
  SavedAnalysisDetail,
  SavedAnalysisSummary,
} from '../../shared/models/saved-analysis.models';

const analysis: AnalysisResponse = {
  fetchStatus: 'text',
  fetchFailureReason: null,
  analysis: null,
  cepikResult: null,
  marketPriceContext: null,
};

const summary: SavedAnalysisSummary = {
  id: 41,
  title: 'Corolla z OLX',
  note: null,
  sourceUrl: null,
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
};

const detail: SavedAnalysisDetail = { summary, analysis };

describe('SavedAnalysisService', () => {
  let service: SavedAnalysisService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(SavedAnalysisService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('posts the whole analysis to /api/saved-analyses', () => {
    service.save({ title: 'Corolla z OLX', analysis }).subscribe((res) => {
      expect(res.summary.id).toBe(41);
    });

    const req = httpMock.expectOne('/api/saved-analyses');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.title).toBe('Corolla z OLX');
    expect(req.request.body.analysis).toEqual(analysis);
    req.flush(detail);
  });

  /**
   * The structural half of "the owner comes from the token": no method here has anywhere to put a
   * user id, so no request body can carry one. Asserted on the wire rather than on the type, because
   * a type is erased and the request is what the server sees.
   */
  it('never sends a userId on any of the four operations', () => {
    service.save({ title: 'X', analysis }).subscribe();
    const created = httpMock.expectOne('/api/saved-analyses');
    expect(Object.keys(created.request.body)).not.toContain('userId');
    created.flush(detail);

    service.edit(41, { title: 'Y', note: null }).subscribe();
    const edited = httpMock.expectOne('/api/saved-analyses/41');
    expect(Object.keys(edited.request.body)).not.toContain('userId');
    edited.flush(detail);

    service.list().subscribe();
    const listed = httpMock.expectOne('/api/saved-analyses');
    expect(listed.request.params.keys()).toEqual([]);
    listed.flush([summary]);

    service.delete(41).subscribe();
    const deleted = httpMock.expectOne('/api/saved-analyses/41');
    expect(deleted.request.params.keys()).toEqual([]);
    deleted.flush(null);
  });

  it('gets the list', () => {
    service.list().subscribe((items) => {
      expect(items.length).toBe(1);
      expect(items[0].title).toBe('Corolla z OLX');
    });

    const req = httpMock.expectOne('/api/saved-analyses');
    expect(req.request.method).toBe('GET');
    req.flush([summary]);
  });

  it('gets one by id', () => {
    service.get(41).subscribe((res) => expect(res.summary.id).toBe(41));

    const req = httpMock.expectOne('/api/saved-analyses/41');
    expect(req.request.method).toBe('GET');
    req.flush(detail);
  });

  it('patches title and note', () => {
    service.edit(41, { title: 'Nowy tytuł', note: 'Sprawdzone' }).subscribe();

    const req = httpMock.expectOne('/api/saved-analyses/41');
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ title: 'Nowy tytuł', note: 'Sprawdzone' });
    req.flush(detail);
  });

  it('deletes by id', () => {
    service.delete(41).subscribe();

    const req = httpMock.expectOne('/api/saved-analyses/41');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('propagates the 404 that means "not yours or not there"', () => {
    service.get(999).subscribe({ error: (err) => expect(err.status).toBe(404) });

    httpMock.expectOne('/api/saved-analyses/999').flush(
      {
        status: 404,
        error: 'Nie znaleziono analizy',
        messages: ['Ta analiza nie istnieje lub nie należy do Ciebie.'],
        timestamp: '',
      },
      { status: 404, statusText: 'Not Found' },
    );
  });
});
