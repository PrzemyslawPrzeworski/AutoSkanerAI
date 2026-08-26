import { Component, computed, signal, OnDestroy } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { InputTextModule } from 'primeng/inputtext';
import { TextareaModule } from 'primeng/textarea';
import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { SkeletonModule } from 'primeng/skeleton';
import { AnalysisService } from '../../core/services/analysis.service';
import { AnalysisResponse } from '../../shared/models/analysis.models';
import {
  VehicleDataDraft,
  draftToRequest,
  emptyDraft,
  isDraftEmpty,
  missingRegistryFields,
  prefillFromExtracted,
  vinError
} from '../../shared/models/vehicle-data';
import { AnalysisResultComponent } from './components/analysis-result/analysis-result.component';
import { VehicleDataFormComponent } from './components/vehicle-data-form/vehicle-data-form.component';

@Component({
  selector: 'app-analyzer',
  imports: [
    InputTextModule,
    TextareaModule,
    ButtonModule,
    MessageModule,
    SkeletonModule,
    AnalysisResultComponent,
    VehicleDataFormComponent
  ],
  templateUrl: './analyzer.component.html',
  styleUrl: './analyzer.component.scss'
})
export class AnalyzerComponent implements OnDestroy {
  url = signal('');
  listingText = signal('');
  vehicleDraft = signal<VehicleDataDraft>(emptyDraft());
  listingFieldsOpen = signal(false);
  loading = signal(false);
  loadingMessage = signal('Analizuję ogłoszenie...');
  fetchFailedBanner = signal<string | null>(null);
  error = signal<string | null>(null);
  analysisResponse = signal<AnalysisResponse | null>(null);

  /**
   * The registry lookup needs VIN + plate + first registration date. When it came back without
   * them, the result view offers the three fields instead of leaving the user to guess why the
   * history panel is empty — that guessing is the whole reason this slice exists.
   */
  readonly registryInputsMissing = computed(
    () => this.analysisResponse()?.cepikResult?.status === 'MISSING_INPUTS'
  );

  readonly vinError = computed(() => vinError(this.vehicleDraft().vin));

  private rotationInterval: ReturnType<typeof setInterval> | null = null;

  private readonly loadingMessages = [
    'Analizuję ogłoszenie...',
    'Sprawdzam ryzyko i wyposażenie...',
    'Sprawdzam historię w rejestrze...',
    'Generuję rekomendacje...'
  ];
  private msgIndex = 0;

  constructor(private readonly analysisService: AnalysisService) {}

  ngOnDestroy(): void {
    this.stopRotation();
  }

  toggleListingFields(): void {
    this.listingFieldsOpen.update(open => !open);
  }

  submit(): void {
    const urlVal = this.url().trim();
    const textVal = this.listingText().trim();
    const draft = this.vehicleDraft();

    if (!urlVal && !textVal && isDraftEmpty(draft)) {
      this.error.set('Wklej URL, treść ogłoszenia albo wypełnij dane pojazdu');
      this.listingFieldsOpen.set(true);
      return;
    }

    // Checked before the request, because a mistyped VIN costs a full ~30 s analysis and comes
    // back with an empty history panel that looks like the registry's fault.
    const vinProblem = this.vinError();
    if (vinProblem) {
      this.error.set(vinProblem);
      return;
    }

    this.loading.set(true);
    this.error.set(null);
    this.fetchFailedBanner.set(null);
    this.analysisResponse.set(null);
    this.startRotation();

    this.analysisService
      .analyze(
        draftToRequest({ url: urlVal || undefined, listingText: textVal || undefined }, draft)
      )
      .subscribe({
        next: response => {
          this.stopRotation();
          this.loading.set(false);
          if (response.fetchStatus === 'url_failed') {
            this.fetchFailedBanner.set(
              'Nie udało się pobrać ogłoszenia. Wklej treść ręcznie poniżej.'
            );
          } else if (response.analysis) {
            this.analysisResponse.set(response);
            // Seeds the follow-up form, so a user completing the registry data only types what
            // is genuinely missing.
            this.vehicleDraft.update(current =>
              prefillFromExtracted(current, response.analysis?.extracted)
            );
          } else {
            this.error.set('Otrzymano niepełną odpowiedź serwera.');
          }
        },
        error: (err: HttpErrorResponse) => {
          this.stopRotation();
          this.loading.set(false);
          this.error.set(this.mapError(err));
        }
      });
  }

  /**
   * Re-runs the same analysis with the completed registry fields. It is a full re-analysis, not a
   * lookup-only call, on purpose: the CEPiK findings have to pass back through the scoring
   * adjustment, and that only happens on the analysis path. The copy warns about the wait.
   */
  recheckWithRegistryData(): void {
    // The fields are prefilled from the extraction, so an empty one means the advert did not carry
    // it either — naming exactly those beats repeating "all three are required" at a user who is
    // looking at two filled boxes. The registry really does need all three, so this still blocks:
    // submitting without them buys a 30 s wait and the same MISSING_INPUTS back.
    const missing = missingRegistryFields(this.vehicleDraft());
    if (missing.length) {
      this.error.set(
        `Rejestr potrzebuje jeszcze: ${missing.join(', ')}. Bez tego nie da się go zapytać.`
      );
      return;
    }
    this.submit();
  }

  reset(): void {
    this.url.set('');
    this.listingText.set('');
    this.vehicleDraft.set(emptyDraft());
    this.listingFieldsOpen.set(false);
    this.loading.set(false);
    this.loadingMessage.set(this.loadingMessages[0]);
    this.fetchFailedBanner.set(null);
    this.error.set(null);
    this.analysisResponse.set(null);
    this.stopRotation();
  }

  private startRotation(): void {
    this.stopRotation();
    this.msgIndex = 0;
    this.loadingMessage.set(this.loadingMessages[0]);
    this.rotationInterval = setInterval(() => {
      this.msgIndex = (this.msgIndex + 1) % this.loadingMessages.length;
      this.loadingMessage.set(this.loadingMessages[this.msgIndex]);
    }, 7000);
  }

  private stopRotation(): void {
    if (this.rotationInterval !== null) {
      clearInterval(this.rotationInterval);
      this.rotationInterval = null;
    }
  }

  private mapError(err: HttpErrorResponse): string {
    if (err.status === 400) {
      const messages: string[] = err.error?.messages;
      return messages?.length ? messages.join('; ') : 'Błąd walidacji danych.';
    }
    if (err.status === 502) {
      return 'Serwis AI jest tymczasowo niedostępny. Spróbuj ponownie.';
    }
    return 'Błąd serwera. Spróbuj ponownie.';
  }
}
