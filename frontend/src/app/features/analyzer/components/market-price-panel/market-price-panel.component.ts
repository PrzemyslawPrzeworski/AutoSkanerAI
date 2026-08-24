import { Component, input, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { MarketPriceContext } from '../../../../shared/models/analysis.models';

@Component({
  selector: 'app-market-price-panel',
  standalone: true,
  imports: [DecimalPipe],
  templateUrl: './market-price-panel.component.html',
  styleUrl: './market-price-panel.component.scss'
})
export class MarketPricePanelComponent {
  readonly marketPriceContext = input.required<MarketPriceContext | null>();
  expanded = signal(false);

  toggle(): void {
    this.expanded.update(v => !v);
  }
}
