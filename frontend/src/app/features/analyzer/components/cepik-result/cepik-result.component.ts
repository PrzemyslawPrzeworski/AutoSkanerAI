import { Component, input, signal } from '@angular/core';
import { CepikResult } from '../../../../shared/models/analysis.models';

@Component({
  selector: 'app-cepik-result',
  standalone: true,
  imports: [],
  templateUrl: './cepik-result.component.html',
  styleUrl: './cepik-result.component.scss'
})
export class CepikResultComponent {
  readonly cepikResult = input.required<CepikResult | null>();
  mileageExpanded = signal(false);

  toggleMileage(): void {
    this.mileageExpanded.update(v => !v);
  }
}
