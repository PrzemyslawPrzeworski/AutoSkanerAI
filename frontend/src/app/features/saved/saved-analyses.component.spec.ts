import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { SavedAnalysesComponent } from './saved-analyses.component';
import { SavedAnalysisService } from '../../core/services/saved-analysis.service';
import { AnalysisResponse } from '../../shared/models/analysis.models';
import {
  SavedAnalysisDetail,
  SavedAnalysisSummary,
} from '../../shared/models/saved-analysis.models';

const emptyAnalysis: AnalysisResponse = {
  fetchStatus: 'text',
  fetchFailureReason: null,
  analysis: null,
  cepikResult: null,
  marketPriceContext: null,
};

function row(id: number, title: string, note: string | null = null): SavedAnalysisSummary {
  return {
    id,
    title,
    note,
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
}

function detailOf(summary: SavedAnalysisSummary): SavedAnalysisDetail {
  return { summary, analysis: emptyAnalysis };
}

function notFound(): HttpErrorResponse {
  return new HttpErrorResponse({ status: 404, statusText: 'Not Found' });
}

interface ServiceStub {
  list: ReturnType<typeof vi.fn>;
  get: ReturnType<typeof vi.fn>;
  save: ReturnType<typeof vi.fn>;
  edit: ReturnType<typeof vi.fn>;
  delete: ReturnType<typeof vi.fn>;
}

function setup(stub: Partial<ServiceStub>) {
  const service: ServiceStub = {
    list: vi.fn(() => of([])),
    get: vi.fn(),
    save: vi.fn(),
    edit: vi.fn(),
    delete: vi.fn(),
    ...stub,
  };
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      { provide: SavedAnalysisService, useValue: service },
    ],
  });
  const fixture = TestBed.createComponent(SavedAnalysesComponent);
  fixture.detectChanges();
  return { fixture, component: fixture.componentInstance, service };
}

describe('SavedAnalysesComponent', () => {
  it('lists what the API returned', () => {
    const { component } = setup({ list: vi.fn(() => of([row(41, 'Corolla'), row(42, 'Yaris')])) });

    expect(component.loading()).toBe(false);
    expect(component.items().map((item) => item.title)).toEqual(['Corolla', 'Yaris']);
    expect(component.error()).toBeNull();
  });

  it('reports a failed list rather than showing an empty one', () => {
    const { component } = setup({ list: vi.fn(() => throwError(() => notFound())) });

    expect(component.items()).toEqual([]);
    expect(component.error()).toContain('Nie udało się wczytać');
  });

  it('replaces the renamed row with the server’s version', () => {
    const renamed = { ...row(41, 'Nowy tytuł', 'Sprawdzone'), updatedAt: '2026-09-12T08:00:00Z' };
    const { component, service } = setup({
      list: vi.fn(() => of([row(41, 'Stary tytuł')])),
      edit: vi.fn(() => of(detailOf(renamed))),
    });

    component.startEdit(component.items()[0]);
    component.editTitle.set('Nowy tytuł');
    component.editNote.set('Sprawdzone');
    component.saveEdit(41);

    expect(service.edit).toHaveBeenCalledWith(41, { title: 'Nowy tytuł', note: 'Sprawdzone' });
    expect(component.items()[0].title).toBe('Nowy tytuł');
    expect(component.items()[0].updatedAt).toBe('2026-09-12T08:00:00Z');
    expect(component.editingId()).toBeNull();
  });

  it('sends a cleared note as null, not as an empty string', () => {
    const { component, service } = setup({
      list: vi.fn(() => of([row(41, 'Tytuł', 'stara notatka')])),
      edit: vi.fn(() => of(detailOf(row(41, 'Tytuł')))),
    });

    component.startEdit(component.items()[0]);
    component.editNote.set('   ');
    component.saveEdit(41);

    expect(service.edit).toHaveBeenCalledWith(41, { title: 'Tytuł', note: null });
  });

  it('refuses a blank title without calling the API', () => {
    const { component, service } = setup({ list: vi.fn(() => of([row(41, 'Tytuł')])) });

    component.startEdit(component.items()[0]);
    component.editTitle.set('  ');
    component.saveEdit(41);

    expect(service.edit).not.toHaveBeenCalled();
    expect(component.editError()).toContain('Nazwa nie może być pusta');
    expect(component.items()[0].title).toBe('Tytuł');
  });

  /**
   * The assertion that makes deletion recoverable-by-not-happening: nothing is removed until the
   * second click, so a misclick on a list of similar cards costs nothing.
   */
  it('does not delete on the first click', () => {
    const { component, service } = setup({ list: vi.fn(() => of([row(41, 'Corolla')])) });

    component.askDelete(41);

    expect(service.delete).not.toHaveBeenCalled();
    expect(component.confirmingDeleteId()).toBe(41);
    expect(component.items().length).toBe(1);
  });

  it('deletes only the confirmed row', () => {
    const { component, service } = setup({
      list: vi.fn(() => of([row(41, 'Corolla'), row(42, 'Yaris')])),
      delete: vi.fn(() => of(undefined)),
    });

    component.askDelete(41);
    component.confirmDelete(41);

    expect(service.delete).toHaveBeenCalledWith(41);
    expect(component.items().map((item) => item.id)).toEqual([42]);
    expect(component.confirmingDeleteId()).toBeNull();
  });

  it('cancelling a delete leaves the row alone', () => {
    const { component, service } = setup({ list: vi.fn(() => of([row(41, 'Corolla')])) });

    component.askDelete(41);
    component.cancelDelete();

    expect(service.delete).not.toHaveBeenCalled();
    expect(component.items().length).toBe(1);
    expect(component.confirmingDeleteId()).toBeNull();
  });

  /**
   * A 404 on delete is the outcome the user asked for — the row is gone. Leaving it on screen and
   * reporting a failure would invite a retry that can never succeed.
   */
  it('drops a row the server says is already gone', () => {
    const { component } = setup({
      list: vi.fn(() => of([row(41, 'Corolla')])),
      delete: vi.fn(() => throwError(() => notFound())),
    });

    component.askDelete(41);
    component.confirmDelete(41);

    expect(component.items()).toEqual([]);
    expect(component.error()).toContain('już nie istnieje');
  });

  it('keeps a row the server failed to delete for any other reason', () => {
    const { component } = setup({
      list: vi.fn(() => of([row(41, 'Corolla')])),
      delete: vi.fn(() =>
        throwError(() => new HttpErrorResponse({ status: 500, statusText: 'Server Error' })),
      ),
    });

    component.askDelete(41);
    component.confirmDelete(41);

    expect(component.items().length).toBe(1);
    expect(component.error()).toContain('Nie udało się usunąć');
  });

  /**
   * A price is grouped, and grouped the Polish way — never with the comma the default `en-US` locale
   * would produce, which a Polish reader takes for a decimal point.
   */
  it('groups a price without using a comma', () => {
    const { component } = setup({});

    const price = component.grouped(64900);
    expect(price).not.toContain(',');
    expect(price.replace(/\s| /g, '')).toBe('64900');
    expect(price).not.toBe('64900');
    expect(component.grouped(null)).toBe('');
  });

  it('renders an unknown verdict code as itself rather than as blank', () => {
    const { component } = setup({});

    expect(component.verdictLabel('WORTH_CHECKING')).toBe('warto sprawdzić');
    expect(component.verdictLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(component.verdictLabel(null)).toBeNull();
  });
});
