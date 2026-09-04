import { TestBed } from '@angular/core/testing';
import { ComponentFixture } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { MarketPricePanelComponent } from './market-price-panel.component';
import {
  MarketPriceContext,
  MarketPriceSampleQuality,
  MarketPriceStatus
} from '../../../../shared/models/analysis.models';

/**
 * The reader for `sampleQuality` and `discardedCount`.
 *
 * `testing-availability-failure-paths` Phase 2 replaced this panel's old `sampleSize < 3` caveat
 * with three blocks driven by the server's own judgement, and shipped that template edit **reviewed
 * but never run** — no Node existed on the machine. So the field the backend work existed to expose
 * had a reader nobody had executed. That is the same shape of hole in reverse: Phase 6 caught a
 * server field with no reader, and the fix introduced a reader with no test.
 *
 * The oracle is `context/foundation/test-plan.md` § "Carried into Phase 3" (impl-review F4), which
 * names the four arms this file must cover — `DISPERSED`, `THIN`, `SUFFICIENT`, and a null quality —
 * plus the `discardedCount` block.
 *
 * <h2>Why `sampleSize < 3` was the wrong test, and why that matters here</h2>
 *
 * A sample of exactly 3 is what the pipeline emits when the prices were too far apart to trim at
 * all — `MIN_SAMPLE_TO_KEEP`. So the most contaminated range the server can produce was the one
 * size that showed no caveat. The caveat is not decoration: without it a range assembled from
 * three prices that happen to disagree reads exactly like a real market.
 */
function makeContext(overrides: Partial<MarketPriceContext> = {}): MarketPriceContext {
  return {
    status: 'OK' as MarketPriceStatus,
    minPricePln: 78000,
    medianPricePln: 82900,
    maxPricePln: 90000,
    sampleSize: 12,
    queryUrl: 'https://www.otomoto.pl/osobowe/bmw/seria-3',
    fetchedAt: '2026-09-04T10:00:00Z',
    sampleQuality: 'SUFFICIENT' as MarketPriceSampleQuality,
    discardedCount: 0,
    ...overrides
  };
}

describe('MarketPricePanelComponent', () => {
  let fixture: ComponentFixture<MarketPricePanelComponent>;

  /**
   * Creates the panel and expands it. Every caveat sits inside `@if (expanded())`, so a spec that
   * renders and looks straight for the copy asserts nothing about the caveat and everything about
   * the panel starting collapsed.
   */
  function createExpanded(context: MarketPriceContext | null): MarketPricePanelComponent {
    const comp = create(context);
    comp.toggle();
    fixture.detectChanges();
    return comp;
  }

  function create(context: MarketPriceContext | null): MarketPricePanelComponent {
    fixture = TestBed.createComponent(MarketPricePanelComponent);
    fixture.componentRef.setInput('marketPriceContext', context);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  function caveatTexts(): string[] {
    return fixture.debugElement
      .queryAll(By.css('.caveat'))
      .map(el => el.nativeElement.textContent.replace(/\s+/g, ' ').trim());
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MarketPricePanelComponent]
    }).compileComponents();
  });

  // -------------------------------------------------------------------------------------------
  // The four sampleQuality arms
  // -------------------------------------------------------------------------------------------

  it('DISPERSED → says the range is not one market, not that the sample is small', () => {
    createExpanded(makeContext({ sampleQuality: 'DISPERSED', sampleSize: 3 }));

    const texts = caveatTexts();
    expect(texts.length).toBe(1);
    // The distinction the two caveats exist to draw. A DISPERSED sample of 3 is not a small
    // sample — it is ten prices that agreed on nothing, reported untrimmed. Calling that "mała
    // próbka" would describe the count and hide the finding.
    expect(texts[0]).toContain('bardzo się różnią');
    expect(texts[0]).not.toContain('Mała próbka');
  });

  it('THIN → names the sample size', () => {
    createExpanded(makeContext({ sampleQuality: 'THIN', sampleSize: 4 }));

    const texts = caveatTexts();
    expect(texts.length).toBe(1);
    expect(texts[0]).toContain('Mała próbka');
    // The count is interpolated, so a caveat that lost its binding still reads plausibly.
    expect(texts[0]).toContain('4');
    expect(texts[0]).not.toContain('bardzo się różnią');
  });

  it('SUFFICIENT → no caveat at all', () => {
    createExpanded(makeContext({ sampleQuality: 'SUFFICIENT', sampleSize: 40, discardedCount: 0 }));

    expect(caveatTexts()).toEqual([]);
  });

  it('null quality → no caveat invented on the client', () => {
    // A null quality means the server made no judgement. The panel used to decide this itself from
    // sampleSize; if it ever does again, a small sample would be labelled THIN here on the client's
    // own authority — which is exactly the reasoning that produced the `sampleSize < 3` bug.
    createExpanded(makeContext({ sampleQuality: null, sampleSize: 2, discardedCount: 0 }));

    expect(caveatTexts()).toEqual([]);
  });

  it('DISPERSED and THIN do not render the same sentence', () => {
    // Four separate assertions on four expected strings all still pass if a later edit collapses
    // two of those strings into one — each test would simply be updated to the new shared value.
    // Comparing the two *actual* rendered texts is what cannot be satisfied that way.
    createExpanded(makeContext({ sampleQuality: 'DISPERSED', sampleSize: 3 }));
    const dispersed = caveatTexts()[0];

    createExpanded(makeContext({ sampleQuality: 'THIN', sampleSize: 3 }));
    const thin = caveatTexts()[0];

    // Both must exist before "they differ" means anything. Found by reintroducing the old
    // `sampleSize < 3` condition to check this file bites: DISPERSED then rendered no caveat at all,
    // `dispersed` was undefined, and `undefined !== 'Mała próbka…'` passed the comparison while the
    // arm it was meant to protect had disappeared. A distinctness check is only as strong as its
    // weakest operand.
    expect(dispersed).toBeDefined();
    expect(thin).toBeDefined();
    expect(dispersed).not.toBe(thin);
  });

  // -------------------------------------------------------------------------------------------
  // discardedCount — substantiates the range rather than caveating it
  // -------------------------------------------------------------------------------------------

  it('a positive discardedCount explains why the range is narrow', () => {
    createExpanded(makeContext({ sampleQuality: 'SUFFICIENT', discardedCount: 3 }));

    const texts = caveatTexts();
    expect(texts.length).toBe(1);
    expect(texts[0]).toContain('Pominięto 3');
  });

  it('discardedCount of 0 says nothing, and neither does null', () => {
    // Truthy check on purpose: 0 means the trim ran and dropped nothing, null means there was no
    // sample to trim. "Pominięto 0 nietypowych ogłoszeń" is noise in the first case and a claim the
    // panel has no standing to make in the second.
    createExpanded(makeContext({ sampleQuality: 'SUFFICIENT', discardedCount: 0 }));
    expect(caveatTexts()).toEqual([]);

    createExpanded(makeContext({ sampleQuality: 'SUFFICIENT', discardedCount: null }));
    expect(caveatTexts()).toEqual([]);
  });

  it('a quality caveat and a discard count are both shown', () => {
    // They answer different questions — "how much weight does this carry" and "why is this span
    // narrower than the Otomoto link shows" — so one must not suppress the other.
    createExpanded(makeContext({ sampleQuality: 'THIN', sampleSize: 4, discardedCount: 2 }));

    const texts = caveatTexts();
    expect(texts.length).toBe(2);
    expect(texts.some(t => t.includes('Mała próbka'))).toBe(true);
    expect(texts.some(t => t.includes('Pominięto 2'))).toBe(true);
  });

  // -------------------------------------------------------------------------------------------
  // The caveats are behind the toggle, which is itself part of the contract
  // -------------------------------------------------------------------------------------------

  it('a collapsed panel shows the range but no caveat', () => {
    create(makeContext({ sampleQuality: 'DISPERSED', sampleSize: 3 }));

    // Worth pinning rather than treating as an implementation detail: the header is what most users
    // read, and it carries a bare min-max with no hint that the sample was untrimmable. If the
    // caveat should be promoted out of the body, this test is where that decision gets made
    // explicitly instead of drifting.
    expect(fixture.debugElement.query(By.css('.panel-header'))).not.toBeNull();
    expect(caveatTexts()).toEqual([]);
  });

  // -------------------------------------------------------------------------------------------
  // Statuses. A degraded panel must not borrow the OK panel's numbers.
  // -------------------------------------------------------------------------------------------

  it('null context renders nothing', () => {
    create(null);

    expect(fixture.debugElement.query(By.css('.market-price-panel'))).toBeNull();
  });

  it('MISSING_INPUTS renders nothing', () => {
    create(makeContext({ status: 'MISSING_INPUTS', sampleQuality: null, discardedCount: null }));

    expect(fixture.debugElement.query(By.css('.market-price-panel'))).toBeNull();
  });

  it('FETCH_FAILED says the comparison is unavailable and shows no range', () => {
    // The degraded records carry null prices, so a header that rendered anyway would read
    // "Kontekst cenowy: – PLN". Asserting the OK header is absent is the load-bearing half.
    create(makeContext({
      status: 'FETCH_FAILED',
      minPricePln: null, medianPricePln: null, maxPricePln: null, sampleSize: null,
      sampleQuality: null, discardedCount: null
    }));

    const degraded = fixture.debugElement.query(By.css('.panel-degraded'));
    expect(degraded).not.toBeNull();
    expect(degraded.nativeElement.textContent).toContain('niedostępne');
    expect(fixture.debugElement.query(By.css('.panel-header'))).toBeNull();
  });

  it('INSUFFICIENT_DATA is worded as "we looked", not as "we could not look"', () => {
    // The two degraded branches must stay distinguishable: one means the fetch broke, the other
    // means the fetch worked and there were not enough comparable listings. Collapsing them tells
    // the user to retry something that will return the same answer.
    create(makeContext({
      status: 'INSUFFICIENT_DATA',
      minPricePln: null, medianPricePln: null, maxPricePln: null, sampleSize: null,
      sampleQuality: null, discardedCount: null
    }));

    const degraded = fixture.debugElement.query(By.css('.panel-degraded'));
    expect(degraded.nativeElement.textContent).toContain('Brak wystarczających ogłoszeń');
    expect(degraded.nativeElement.textContent).not.toContain('niedostępne');
    expect(fixture.debugElement.query(By.css('.panel-header'))).toBeNull();
  });
});
