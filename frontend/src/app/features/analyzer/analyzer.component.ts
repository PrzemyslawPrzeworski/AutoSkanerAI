import { Component, signal, OnDestroy } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { InputTextModule } from 'primeng/inputtext';
import { TextareaModule } from 'primeng/textarea';
import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { SkeletonModule } from 'primeng/skeleton';
import { AnalysisService } from '../../core/services/analysis.service';
import { AnalysisResult } from '../../shared/models/analysis.models';

@Component({
  selector: 'app-analyzer',
  imports: [InputTextModule, TextareaModule, ButtonModule, MessageModule, SkeletonModule],
  templateUrl: './analyzer.component.html',
  styleUrl: './analyzer.component.scss'
})
export class AnalyzerComponent implements OnDestroy {
  url = signal('');
  listingText = signal('');
  loading = signal(false);
  loadingMessage = signal('Analizuję ogłoszenie...');
  fetchFailedBanner = signal<string | null>(null);
  error = signal<string | null>(null);
  result = signal<AnalysisResult | null>(null);

  private rotationInterval: ReturnType<typeof setInterval> | null = null;

  private readonly loadingMessages = [
    'Analizuję ogłoszenie...',
    'Sprawdzam ryzyko i wyposażenie...',
    'Generuję rekomendacje...'
  ];
  private msgIndex = 0;

  constructor(private readonly analysisService: AnalysisService) {}

  ngOnDestroy(): void {
    this.stopRotation();
  }

  submit(): void {
    const urlVal = this.url().trim();
    const textVal = this.listingText().trim();

    if (!urlVal && !textVal) {
      this.error.set('Wklej URL lub tekst ogłoszenia');
      return;
    }

    this.loading.set(true);
    this.error.set(null);
    this.fetchFailedBanner.set(null);
    this.result.set(null);
    this.startRotation();

    this.analysisService.analyze({
      url: urlVal || undefined,
      listingText: textVal || undefined
    }).subscribe({
      next: response => {
        this.stopRotation();
        this.loading.set(false);
        if (response.fetchStatus === 'url_failed') {
          this.fetchFailedBanner.set(
            'Nie udało się pobrać ogłoszenia. Wklej treść ręcznie poniżej.'
          );
        } else {
          this.result.set(response.analysis!);
        }
      },
      error: (err: HttpErrorResponse) => {
        this.stopRotation();
        this.loading.set(false);
        this.error.set(this.mapError(err));
      }
    });
  }

  reset(): void {
    this.url.set('');
    this.listingText.set('');
    this.loading.set(false);
    this.loadingMessage.set(this.loadingMessages[0]);
    this.fetchFailedBanner.set(null);
    this.error.set(null);
    this.result.set(null);
    this.stopRotation();
  }

  private startRotation(): void {
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
