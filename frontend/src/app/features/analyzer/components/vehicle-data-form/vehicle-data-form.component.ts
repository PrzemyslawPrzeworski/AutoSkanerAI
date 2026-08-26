import { Component, computed, input, model } from '@angular/core';
import { InputTextModule } from 'primeng/inputtext';
import { TextareaModule } from 'primeng/textarea';
import { MessageModule } from 'primeng/message';
import { VehicleDataDraft, vinError } from '../../../../shared/models/vehicle-data';

/**
 * The fields the user can type about the car. Two shapes:
 *
 * - `registry` — VIN, plate, first registration date only. Used after an analysis comes back
 *   without a registry result, where those three are the only thing missing.
 * - `full` — the registry three plus make / model / year / price / mileage / fuel / transmission /
 *   notes, which on their own are enough to analyse a car with no advert at all (FR-003).
 */
@Component({
  selector: 'app-vehicle-data-form',
  imports: [InputTextModule, TextareaModule, MessageModule],
  templateUrl: './vehicle-data-form.component.html',
  styleUrl: './vehicle-data-form.component.scss'
})
export class VehicleDataFormComponent {
  readonly draft = model.required<VehicleDataDraft>();
  readonly mode = input<'registry' | 'full'>('full');

  readonly vinError = computed(() => vinError(this.draft().vin));

  set(field: keyof VehicleDataDraft, value: string): void {
    this.draft.update(current => ({ ...current, [field]: value }));
  }
}
