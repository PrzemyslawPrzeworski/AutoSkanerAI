import { Component, computed, input, model } from '@angular/core';
import { InputTextModule } from 'primeng/inputtext';
import { TextareaModule } from 'primeng/textarea';
import { MessageModule } from 'primeng/message';
import { VehicleDataDraft, vinError } from '../../../../shared/models/vehicle-data';

/**
 * The fields the user can type about the car. Three shapes, each with one job — an earlier version
 * put all of them in a single drawer labelled by field name, and it read as a pile of optional
 * boxes with no stated purpose:
 *
 * - `vin` — the VIN alone. What the input screen asks for, because it is the only identifier the
 *   advert cannot supply: Otomoto encrypts it for logged-out fetches. The plate and the first
 *   registration date are published in the advert, so the user should never have to retype them.
 * - `registry` — VIN + plate + first registration date. Shown only after a lookup came back
 *   MISSING_INPUTS, i.e. when the advert genuinely did not carry all three.
 * - `listing` — make / model / year / price / mileage / fuel / transmission / notes. These stand in
 *   for an advert that does not exist (FR-003), so they belong behind "I have no link", not next to
 *   the registry fields they have nothing to do with.
 */
@Component({
  selector: 'app-vehicle-data-form',
  imports: [InputTextModule, TextareaModule, MessageModule],
  templateUrl: './vehicle-data-form.component.html',
  styleUrl: './vehicle-data-form.component.scss',
})
export class VehicleDataFormComponent {
  readonly draft = model.required<VehicleDataDraft>();
  readonly mode = input<'vin' | 'registry' | 'listing'>('vin');

  readonly vinError = computed(() => vinError(this.draft().vin));

  set(field: keyof VehicleDataDraft, value: string): void {
    this.draft.update((current) => ({ ...current, [field]: value }));
  }
}
