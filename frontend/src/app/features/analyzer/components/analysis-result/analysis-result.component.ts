import { Component, input, signal, computed } from '@angular/core';
import { DatePipe } from '@angular/common';
import { CardModule } from 'primeng/card';
import { TagModule } from 'primeng/tag';
import { ProgressBarModule } from 'primeng/progressbar';
import { AnalysisResult } from '../../../../shared/models/analysis.models';

@Component({
  selector: 'app-analysis-result',
  imports: [DatePipe, CardModule, TagModule, ProgressBarModule],
  templateUrl: './analysis-result.component.html',
  styleUrl: './analysis-result.component.scss'
})
export class AnalysisResultComponent {
  readonly result = input.required<AnalysisResult>();

  riskFlagsExpanded = signal(false);

  readonly verdictClass = computed(() => {
    switch (this.result().verdict.code) {
      case 'WORTH_CHECKING': return 'verdict-green';
      case 'NEEDS_MORE_INFO': return 'verdict-orange';
      case 'HIGH_RISK_SKIP': return 'verdict-red';
    }
  });

  readonly visibleFlags = computed(() => {
    const flags = this.result().riskFlags;
    return this.riskFlagsExpanded() || flags.length <= 4 ? flags : flags.slice(0, 4);
  });

  readonly showExpandLink = computed(() => this.result().riskFlags.length > 4);

  toggleFlags(): void {
    this.riskFlagsExpanded.update(v => !v);
  }

  scoreColor(value: number): string {
    if (value >= 70) return '#22c55e';
    if (value >= 40) return '#f97316';
    return '#ef4444';
  }

  severityLabel(severity: string): string {
    switch (severity) {
      case 'HIGH': return 'WYSOKI';
      case 'MEDIUM': return 'ŚREDNI';
      case 'LOW': return 'NISKI';
      default: return severity;
    }
  }

  severitySeverity(severity: string): 'danger' | 'warn' | 'info' {
    switch (severity) {
      case 'HIGH': return 'danger';
      case 'MEDIUM': return 'warn';
      default: return 'info';
    }
  }

  equipmentLabel(status: string): string {
    switch (status) {
      case 'CONFIRMED': return 'Potwierdzono';
      case 'MISSING': return 'Brak';
      default: return 'Niesprecyzowane';
    }
  }

  equipmentSeverity(status: string): 'success' | 'danger' | 'secondary' {
    switch (status) {
      case 'CONFIRMED': return 'success';
      case 'MISSING': return 'danger';
      default: return 'secondary';
    }
  }

  format(value: boolean | null | undefined): string {
    if (value === null || value === undefined) return '—';
    return value ? 'Tak' : 'Nie';
  }

  str(value: string | number | null | undefined): string {
    return value !== null && value !== undefined ? String(value) : '—';
  }
}
