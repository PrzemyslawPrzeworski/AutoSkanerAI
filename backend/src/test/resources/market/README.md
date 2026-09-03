# Market-price fixtures

Jina-rendered Otomoto search markdown, used by the price extraction and statistics tests.
Mirrors the conventions in `../cepik/README.md`; read that one too, the reasoning is the same.

## The vocabulary rule

**Third-party payloads must be captured. Shapes we own may be composed.**

That line is what decides which file in here is allowed to be hand-written:

- The markdown below is Otomoto's, rendered by Jina. Neither is ours, so
  `PRICE_PATTERN`'s idea of how a price is marked up has to come from bytes somebody
  else produced. A hand-composed markdown fixture would be the regex agreeing with
  itself.
- The `List<Integer>` that `MarketPriceStatistics` consumes **is** ours — an internal
  parameter, not a wire format. Composing lists to hit the band/IQR/median boundaries is
  correct and those tests stay as they are.

## Verbatim capture — `otomoto-search-results.md`

Captured 2026-09-03 from
`https://r.jina.ai/<urlencoded>https://www.otomoto.pl/osobowe/toyota/corolla?search[filter_float_year:from]=2020&search[filter_float_year:to]=2024&search[filter_float_mileage:to]=56320`,
the exact URL shape `MarketPriceFetchService.buildUrl` produces for a 2022 Corolla with
26 320 km. 16 051 bytes, unedited.

It carries **40 prices, min 21 800, median 79 900, max 124 900** — the same sample size
production logged on 2026-08-26, so it is a representative page rather than a lucky one. The
21 800 sits on a 2022 petrol Corolla at 54 900 km and is real contamination: below the band
floor of 79 900 / 3 = 26 633, so the trim drops it and the reported minimum becomes the next
price in the file, 40 900. That one dropped price is what makes this capture worth having
over a composed one — the live page contained the bug class the trim exists for.

Nothing was redacted, because nothing needed it: this is a public search-results page and it
carries no phone numbers, emails or personal names. Cities, dealer links and listing IDs are
the advert's own public content. Checked before committing — this repo is public.

**Line endings: LF only. Zero CR bytes.** Worth stating outright, because it settles a
question the original review left open. `context/archive/…market-price-context/reviews/impl-review.md`
F1 changed the pattern from `\nPLN` to `\r?\nPLN` and recorded the blind spot *"Jina may
normalise server-side; worth confirming but fix costs nothing"*. It does normalise: this
capture was fetched from a Windows machine and still came back LF-only. So `\r?` guards
against a payload shape that has never actually arrived — defensive, not observed.

## Derived fixture — `otomoto-search-results-crlf-derived.md`

Parent: `otomoto-search-results.md`. Exactly one mechanical edit — every `\n` becomes
`\r\n`, nothing else. 16 657 bytes = 16 051 + 606 CRs. Reproduce and verify with:

```bash
sed 's/$/\r/' otomoto-search-results.md | cmp - otomoto-search-results-crlf-derived.md
```

A markdown file cannot carry the `_provenance` object the cepik JSON fixtures use, so this
paragraph is the provenance record and the `cmp` above is what makes the claim checkable.

This file exists because the verbatim capture can only ever exercise the `\n` half of
`\r?\n`.

**`\r?` is unfalsifiable, and that is worth knowing before trusting a mutation over it.**
Measured 2026-09-03 against both fixtures: deleting `\r?` from `PRICE_PATTERN` changes the
match on neither, because `[\d\s]+` already admits `\r` and absorbs it into the captured
group, where `replaceAll("\\s", "")` then removes it. No test can distinguish `\r?\n` from
`\n` while the character class stays that wide, so do not write one and do not report that
mutation as evidence — it is a no-op.

What the pair *does* pin, and what the tests in `MarketPriceFetchServiceTest` assert:

- **The guarantee, not the mechanism.** The scrape is line-ending agnostic however it ends up
  spelled. The mutation that kills `theSameCapturedPageWithWindowsLineEndingsIsReadTheSameWay`
  is narrowing the class to `[\d \n]+`: the LF fixture still parses, this one stops.
- **That the pair is actually a pair.** Normalising this file back to LF fails
  `theDerivedFixtureDiffersFromItsParentOnlyInLineEndings`, which is the `core.autocrlf`
  scenario below caught as a test rather than trusted to a config file.

## Both files are pinned `-text` in `/.gitattributes`

`core.autocrlf=true` on any clone or CI runner would rewrite both to one style and the pair
would agree — silently testing one case twice. Do not remove the pin, and do not let an
editor "fix" the line endings of either file.
