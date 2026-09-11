import { Component, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MessageModule } from 'primeng/message';
import { SkeletonModule } from 'primeng/skeleton';
import { SavedAnalysisService } from '../../core/services/saved-analysis.service';
import { SavedAnalysisDetail } from '../../shared/models/saved-analysis.models';
import { AnalysisResultComponent } from '../analyzer/components/analysis-result/analysis-result.component';

/**
 * FR-011, the read half: one saved analysis, rendered through the *same* {@link
 * AnalysisResultComponent} a fresh analysis uses.
 *
 * <p>That reuse is why the server stores the response payload whole and reads it back rather than
 * reconstructing a view model from the summary columns — a second renderer for saved rows would drift
 * from the live one, and the rule that absent registry data is never shown as a clean history would
 * then have to hold in two places.
 */
@Component({
  selector: 'app-saved-analysis-detail',
  imports: [MessageModule, SkeletonModule, RouterLink, AnalysisResultComponent],
  templateUrl: './saved-analysis-detail.component.html',
  styleUrl: './saved-analyses.component.scss',
})
export class SavedAnalysisDetailComponent {
  private readonly savedAnalyses = inject(SavedAnalysisService);
  private readonly route = inject(ActivatedRoute);

  readonly detail = signal<SavedAnalysisDetail | null>(null);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  constructor() {
    const id = Number(this.route.snapshot.paramMap.get('id'));
    // A non-numeric id would otherwise reach the API as `/api/saved-analyses/NaN` and come back 500;
    // it is the same "not yours or not there" outcome, so it gets the same message.
    if (!Number.isInteger(id) || id <= 0) {
      this.loading.set(false);
      this.error.set('Nie znaleziono takiej analizy.');
      return;
    }
    this.savedAnalyses.get(id).subscribe({
      next: (detail) => {
        this.detail.set(detail);
        this.loading.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this.loading.set(false);
        this.error.set(
          err.status === 404
            ? 'Ta analiza nie istnieje lub nie należy do Ciebie.'
            : 'Nie udało się wczytać analizy. Spróbuj ponownie.',
        );
      },
    });
  }
}
