# LLM response fixtures

Model-facing JSON in the locked output schema, used by `AnalysisResponseParserTest` and by the
service tests that need a well-formed envelope to unwrap.

## These are composed, not captured

Unlike `../../cepik/` and `../../market/`, everything here is hand-written, and that is correct
under the vocabulary rule stated in `../../market/README.md`: **third-party payloads must be
captured, shapes we own may be composed.** The output schema is ours — `AnalysisPrompt` dictates
it and `AnalysisResponseParser` enforces it — so composing a fixture that violates it in exactly
one way is the only way to test one rejection at a time. A captured model response cannot be
aimed at a specific branch.

The envelope *around* the content (`choices[0].message.content`) is OpenRouter's, not ours. It is
composed here anyway, because these fixtures are the content and the tests that need a real
envelope shape assert against the live-tagged path instead.

## The VIN in these files is illustrative, not a real vehicle's

`WBAAM31060GE12345` appears in `valid-full-response.json` and in the six fixtures derived from it.
It is **not captured from any listing**: it is the example VIN written into `AnalysisPrompt`'s own
few-shot text, and the fixtures inherited it when they were composed against that prompt. It is
BMW-shaped (`WBA` is BMW's WMI) and passes a shape check, which is what makes it useful as an
example and also what makes it worth saying out loud here — this repo is public, and a
plausible-looking VIN with no provenance note invites the assumption that somebody's car is in the
test suite.

The synthetic VIN used elsewhere in the suite is `NMTBZ3BE40R000000` (Toyota WMI, all-zero serial
— unmistakably fabricated). Prefer that one in anything new. These files keep
`WBAAM31060GE12345` only so they stay consistent with the prompt they were written against;
changing it would mean changing `AnalysisPrompt`, which is a separate decision.

Nothing else in these files refers to a real vehicle, seller, or listing.
