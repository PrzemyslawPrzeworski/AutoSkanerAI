import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  EditSavedAnalysisRequest,
  SaveAnalysisRequest,
  SavedAnalysisDetail,
  SavedAnalysisSummary,
} from '../../shared/models/saved-analysis.models';

/**
 * The four CRUD operations on saved analyses (FR-010 … FR-012).
 *
 * <p>No method takes a user id, and that is the contract rather than an omission: the owner is the
 * subject of the bearer token the interceptor attaches, so the server decides whose rows these are.
 * A 404 from any of these means "not yours or not there" — the API deliberately cannot tell them
 * apart, so neither can this client.
 */
@Injectable({ providedIn: 'root' })
export class SavedAnalysisService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/api/saved-analyses`;

  save(request: SaveAnalysisRequest): Observable<SavedAnalysisDetail> {
    return this.http.post<SavedAnalysisDetail>(this.base, request);
  }

  list(): Observable<SavedAnalysisSummary[]> {
    return this.http.get<SavedAnalysisSummary[]>(this.base);
  }

  get(id: number): Observable<SavedAnalysisDetail> {
    return this.http.get<SavedAnalysisDetail>(`${this.base}/${id}`);
  }

  edit(id: number, request: EditSavedAnalysisRequest): Observable<SavedAnalysisDetail> {
    return this.http.patch<SavedAnalysisDetail>(`${this.base}/${id}`, request);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`);
  }
}
