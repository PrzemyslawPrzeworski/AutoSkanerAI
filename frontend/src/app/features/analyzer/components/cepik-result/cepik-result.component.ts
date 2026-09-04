import { Component, computed, input, signal } from '@angular/core';
import {
  CepikResult,
  DamageRecord,
  ExtractedData,
  MileageStamp,
  VehicleEvent,
} from '../../../../shared/models/analysis.models';

/**
 * Three-state, never two. `damageRecords === null` means the registry was never asked or did not
 * answer; `[]` means it answered and reported nothing. The template must not merge them — the
 * previous version rendered both as "Brak zgłoszonych szkód istotnych w CEPiK", so a parse
 * failure looked like a clean history.
 */
export type DamageState = 'unknown' | 'none-reported' | 'reported';

/** A registry finding serious enough to lead with, rather than sit in a table row. */
export interface CepikAlert {
  title: string;
  detail: string;
}

@Component({
  selector: 'app-cepik-result',
  standalone: true,
  imports: [],
  templateUrl: './cepik-result.component.html',
  styleUrl: './cepik-result.component.scss',
})
export class CepikResultComponent {
  readonly cepikResult = input.required<CepikResult | null>();

  /** Optional: enables the registry-vs-listing cross-check. Nothing else depends on it. */
  readonly listing = input<ExtractedData | null>(null);

  mileageExpanded = signal(false);
  timelineExpanded = signal(false);

  toggleMileage(): void {
    this.mileageExpanded.update((v) => !v);
  }

  toggleTimeline(): void {
    this.timelineExpanded.update((v) => !v);
  }

  readonly damages = computed<DamageRecord[]>(() => this.cepikResult()?.damageRecords ?? []);

  readonly damageState = computed<DamageState>(() => {
    const records = this.cepikResult()?.damageRecords;
    if (records === null || records === undefined) return 'unknown';
    return records.length > 0 ? 'reported' : 'none-reported';
  });

  readonly mileageStamps = computed<MileageStamp[]>(() => this.cepikResult()?.mileageStamps ?? []);

  readonly lastMileage = computed<MileageStamp | null>(() => {
    const stamps = this.mileageStamps();
    return stamps.length > 0 ? stamps[stamps.length - 1] : null;
  });

  readonly events = computed<VehicleEvent[]>(() => this.cepikResult()?.events ?? []);

  /**
   * High-severity findings, surfaced above the detail table. A szkoda istotna is a
   * buy-or-walk-away fact; it does not belong in a row the eye slides past.
   */
  readonly alerts = computed<CepikAlert[]>(() => {
    const result = this.cepikResult();
    if (!result) return [];
    const alerts: CepikAlert[] = [];

    if (result.vehicleLost === true) {
      alerts.push({
        title: 'Pojazd zgłoszony jako utracony',
        detail:
          'CEPiK oznacza ten pojazd jako utracony (kradzież). Nie kupuj bez wyjaśnienia w policji.',
      });
    }

    if (result.odometerRolledBack === true) {
      alerts.push({
        title: 'Rejestr wykrył cofnięcie drogomierza',
        detail:
          'Kolejny odczyt licznika był niższy od poprzedniego. Faktyczny przebieg jest nieznany.',
      });
    }

    for (const damage of this.damages()) {
      const parts: string[] = [];
      if (damage.categories?.length) parts.push(damage.categories.join(', '));
      if (damage.insurer) parts.push('ubezpieczyciel: ' + damage.insurer);
      alerts.push({
        title: 'Szkoda istotna — ' + (damage.date ?? 'data nieznana'),
        detail:
          parts.length > 0
            ? parts.join(' · ')
            : (damage.description ?? 'Brak szczegółów w rejestrze.'),
      });
    }

    return alerts;
  });

  /**
   * Registry identity against what the listing claims. The registry is authoritative, so a
   * mismatch is a stronger signal than any sentence in the description.
   */
  readonly identityMismatches = computed<string[]>(() => {
    const result = this.cepikResult();
    const listing = this.listing();
    if (!result || !listing) return [];
    const mismatches: string[] = [];

    if (
      result.yearOfManufacture !== null &&
      listing.year !== null &&
      result.yearOfManufacture !== listing.year
    ) {
      mismatches.push(
        `Rok produkcji: rejestr ${result.yearOfManufacture}, ogłoszenie ${listing.year}`,
      );
    }

    // Registry names are upper-case and often carry the make inside the model ("TOYOTA COROLLA"),
    // so compare loosely — a substring hit either way counts as agreement.
    if (!this.namesAgree(result.make, listing.make)) {
      mismatches.push(`Marka: rejestr ${result.make}, ogłoszenie ${listing.make}`);
    }
    if (!this.namesAgree(result.model, listing.model)) {
      mismatches.push(`Model: rejestr ${result.model}, ogłoszenie ${listing.model}`);
    }

    return mismatches;
  });

  private namesAgree(registry: string | null, listed: string | null): boolean {
    if (!registry || !listed) return true;
    const a = registry.trim().toLowerCase();
    const b = listed.trim().toLowerCase();
    return a.includes(b) || b.includes(a);
  }

  /**
   * Only the direction that favours the seller — registry higher than advertised — and only past
   * a tolerance. Sellers round ("26 000 km" against a registry reading of 26 320), and the
   * registry reading is months old, so a small gap is normal and warning about it trains the
   * user to ignore the warning that matters.
   */
  readonly hasMileageMismatchRisk = computed(() => {
    const last = this.lastMileage();
    const listed = this.listing()?.mileageKm;
    if (!last?.mileageKm || !listed) return false;
    const tolerance = Math.max(2000, listed * 0.05);
    return last.mileageKm - listed > tolerance;
  });

  yesNo(value: boolean | null): string {
    if (value === null || value === undefined) return 'brak danych';
    return value ? 'Tak' : 'Nie';
  }

  str(value: string | number | null): string {
    return value !== null && value !== undefined && value !== '' ? String(value) : '—';
  }

  km(value: number | null): string {
    return value === null || value === undefined ? '—' : value.toLocaleString('pl-PL') + ' km';
  }
}
