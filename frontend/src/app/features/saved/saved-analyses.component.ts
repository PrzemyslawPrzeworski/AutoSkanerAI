import { Component, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { InputTextModule } from 'primeng/inputtext';
import { TextareaModule } from 'primeng/textarea';
import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { SkeletonModule } from 'primeng/skeleton';
import { SavedAnalysisService } from '../../core/services/saved-analysis.service';
import { SavedAnalysisSummary } from '../../shared/models/saved-analysis.models';

/**
 * The Polish label per verdict code. Kept as an exhaustive lookup with a fallback rather than a
 * template `@switch`, because `verdictCode` arrives as a plain string from a saved row that may
 * predate a new code — an unknown code must render as itself, not as blank.
 */
const VERDICT_LABELS: Record<string, string> = {
  WORTH_CHECKING: 'warto sprawdzić',
  NEEDS_MORE_INFO: 'potrzeba więcej informacji',
  HIGH_RISK_SKIP: 'wysokie ryzyko',
};

/**
 * FR-011 (list and read) and FR-012 (rename, delete) — the read/update/delete end of the CRUD that
 * {@link AnalyzerComponent} opens with a save.
 *
 * <p>Rename and delete both operate on ids the server re-checks against the token's owner, so a stale
 * tab acting on a row deleted elsewhere gets a 404 and is told the row is gone rather than being left
 * looking at it. That is why every mutation refreshes from the response or the list, never from local
 * assumptions.
 */
@Component({
  selector: 'app-saved-analyses',
  imports: [
    InputTextModule,
    TextareaModule,
    ButtonModule,
    MessageModule,
    SkeletonModule,
    RouterLink,
    DatePipe,
  ],
  templateUrl: './saved-analyses.component.html',
  styleUrl: './saved-analyses.component.scss',
})
export class SavedAnalysesComponent {
  private readonly savedAnalyses = inject(SavedAnalysisService);

  readonly items = signal<SavedAnalysisSummary[]>([]);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  /** The row whose rename form is open, by id. Only one at a time — a list of forms is not an editor. */
  readonly editingId = signal<number | null>(null);
  readonly editTitle = signal('');
  readonly editNote = signal('');
  readonly editError = signal<string | null>(null);
  readonly busyId = signal<number | null>(null);

  /**
   * Deletion is irreversible and there is no undo, so it takes two clicks. An in-component confirm
   * rather than `window.confirm`, which cannot be asserted in a test and cannot be styled.
   */
  readonly confirmingDeleteId = signal<number | null>(null);

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.savedAnalyses.list().subscribe({
      next: (items) => {
        this.items.set(items);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.error.set('Nie udało się wczytać zapisanych analiz. Odśwież stronę.');
      },
    });
  }

  verdictLabel(code: string | null): string | null {
    return code === null ? null : (VERDICT_LABELS[code] ?? code);
  }

  /**
   * Thousands-grouped for reading, in Polish convention. Not the `number` pipe: that formats under the
   * app's `LOCALE_ID`, which is still the default `en-US`, so a price would come out `64,900` — a comma
   * reads as a decimal point to a Polish user, which is worse than no grouping at all. Registering
   * `pl` locale data app-wide is the real fix and belongs with the rest of the i18n, not here.
   */
  grouped(value: number | null): string {
    return value === null ? '' : value.toLocaleString('pl-PL');
  }

  startEdit(item: SavedAnalysisSummary): void {
    this.editingId.set(item.id);
    this.editTitle.set(item.title);
    this.editNote.set(item.note ?? '');
    this.editError.set(null);
    this.confirmingDeleteId.set(null);
  }

  cancelEdit(): void {
    this.editingId.set(null);
    this.editError.set(null);
  }

  saveEdit(id: number): void {
    if (this.busyId() !== null) {
      return;
    }
    const title = this.editTitle().trim();
    if (title.length === 0) {
      this.editError.set('Nazwa nie może być pusta.');
      return;
    }
    const note = this.editNote().trim();

    this.busyId.set(id);
    this.editError.set(null);
    this.savedAnalyses.edit(id, { title, note: note.length > 0 ? note : null }).subscribe({
      next: (detail) => {
        this.busyId.set(null);
        this.editingId.set(null);
        // Replaced from the server's answer, not from what was typed: `updatedAt` and any field the
        // server normalised are only knowable from the response.
        this.items.update((items) => items.map((item) => (item.id === id ? detail.summary : item)));
      },
      error: (err: HttpErrorResponse) => {
        this.busyId.set(null);
        this.editError.set(this.mutationError(err, 'Nie udało się zmienić nazwy.'));
      },
    });
  }

  askDelete(id: number): void {
    this.confirmingDeleteId.set(id);
    this.editingId.set(null);
    this.error.set(null);
  }

  cancelDelete(): void {
    this.confirmingDeleteId.set(null);
  }

  confirmDelete(id: number): void {
    if (this.busyId() !== null) {
      return;
    }
    this.busyId.set(id);
    this.error.set(null);
    this.savedAnalyses.delete(id).subscribe({
      next: () => {
        this.busyId.set(null);
        this.confirmingDeleteId.set(null);
        this.items.update((items) => items.filter((item) => item.id !== id));
      },
      error: (err: HttpErrorResponse) => {
        this.busyId.set(null);
        this.confirmingDeleteId.set(null);
        // A 404 means the row is already gone — for a delete that is the outcome the user wanted, so
        // drop it from the list and say what happened instead of reporting a failure.
        if (err.status === 404) {
          this.items.update((items) => items.filter((item) => item.id !== id));
          this.error.set('Ta analiza już nie istnieje — usunięto ją z listy.');
          return;
        }
        this.error.set('Nie udało się usunąć analizy. Spróbuj ponownie.');
      },
    });
  }

  private mutationError(err: HttpErrorResponse, fallback: string): string {
    if (err.status === 404) {
      return 'Ta analiza już nie istnieje.';
    }
    if (err.status === 400) {
      return err.error?.messages?.join('; ') ?? fallback;
    }
    return fallback;
  }
}
