# Registry fixtures

Payloads from `historiapojazdu.gov.pl`, used by the parser and integration tests. Two
classes of file live here and the distinction is load-bearing.

## Verbatim captures — `*-found.json`, `not-found-*.json`, no `-derived` suffix

Byte copies of real responses. The only permitted edit is a documented redaction of an
identifying value: in `vehicle-data-found.json` and `timeline-data-found.json` the VIN is
replaced with the synthetic `NMTBZ3BE40R000000`, because this repo is public and the rest of
the payload is a real seller's vehicle record. Nothing the parser does depends on the VIN
value, so the shape under test is unaffected. Do not paste a real VIN back in.

Capture a new one rather than composing one, and **do not add a field mapping to
`HistoriaPojazduParser` without a captured payload showing that field name.**

One provenance caveat, stated because the rule above is only worth anything if its exceptions
are named: `not-found-hipo-0002.json` is a **transcription, not a byte capture**. The registry's
404 body was never saved to a file — the only record of it is the message text embedded in
`HistoriaPojazduServiceTest.returnsNotFoundWhenRegistryReportsHipo0002`, which was lifted from a
real `HttpClientErrorException` (`404 Not Found: "{…}"`). The two keys and both values come from
there verbatim; the whitespace and key order are whatever that message showed. Nothing reads
this file's *shape* — `HistoriaPojazduService.indicatesVehicleNotFound` string-sniffs the cause
chain for `HIPO-0002` — so the transcription is load-bearing only for that one substring. If a
real 404 is ever captured, replace this file with it.

## Derived fixtures — `*-derived.json`

Produced from a named verbatim parent by exactly one of two operations:

- **deleting a node**, or
- **changing a value**.

Never by introducing a key, renaming a key, or hand-composing an object. Every derived file
carries a top-level `_provenance` object naming its parent and the exact edit, so the claim
is checkable with a diff.

## Why the rule exists

Until 2026-08-26 the parser looked for `zdarzenia`, `szkodyIstotne`, `przebieg` and
`liczbaWlascicieli` — field names the registry has never returned. Every test passed, because
the fixtures had been hand-written to match the invented names. Production reported
`damageRecords: []` for a car carrying a registered szkoda istotna, and the UI rendered that
as "brak zgłoszonych szkód istotnych".

A fixture composed key-by-key from what the parser expects cannot detect that failure — it
*is* that failure, one file earlier. Keys come from captured bytes; a derived fixture may
change what the registry said, never the vocabulary it said it in.

One consequence worth stating: to simulate the registry's vocabulary drifting, change
`eventType` **values** (`timeline-data-drifted-derived.json`). Renaming the `eventType` key
itself would be composing a payload, which is prohibited — and is not what drift looks like
anyway.
