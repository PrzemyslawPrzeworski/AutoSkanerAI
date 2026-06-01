import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AnalysisRequest, AnalysisResponse } from '../../shared/models/analysis.models';

@Injectable({ providedIn: 'root' })
export class AnalysisService {
  private readonly http = inject(HttpClient);

  analyze(request: AnalysisRequest): Observable<AnalysisResponse> {
    return this.http.post<AnalysisResponse>(
      `${environment.apiUrl}/api/analyses`,
      request
    );
  }
}
