---
change_id: save-view-delete-analyses
title: Saved analyses — the four CRUD operations, owner-scoped, and the UI that reaches them
status: implemented
created: 2026-09-11
updated: 2026-09-11
archived_at: null
---

## Notes

Roadmap S-03, the last link of Stream B: `data-layer-setup` (F-02) → `auth-scaffold` (F-03) →
this. F-02 shipped the `saved_analyses` table and left it unreachable; F-03 shipped the principal
a row can belong to. This change is what finally connects the two — the first write into
`saved_analyses` from an HTTP request, and the first read back out.

It covers FR-010 (save), FR-011 (list, read) and FR-012 (rename, delete). It is also the change
that makes the 10xDevs certification bar's first criterion true: *all four CRUD actions on
persisted data, reachable by a user*. Before it, the app could analyse a listing beautifully and
forget it the moment the tab closed.

**Shipped without a `/10x-plan` document, deliberately.** The hand-in is 14 September and the
shape was already fixed by F-02's schema and F-03's principal — there was no design space left to
explore, only code to write. The decisions that would have gone into a plan are recorded below
instead, which is where a reader would look for them anyway.

Two commits: `ecd3902` for the backend, `40ea495` for the frontend half and the docs. That split is safe in a
direction the auth change was not — a saved-analyses API with no UI in front of it is invisible,
not broken, so unlike F-03 (where an API demanding a token in front of a frontend that sends none
is a dead app) neither half had to land with the other.

## Decisions

**Ownership is part of the lookup, never a check after it.** `findByIdAndUserId`,
`deleteByIdAndUserId` — the owner is in the `where` clause, so there is no window in which a row
belonging to someone else is loaded into memory and then judged. The alternative (`findById`, then
`if (!row.userId.equals(userId)) throw`) is one forgotten `if` away from a data leak, and the
forgotten `if` is invisible in review because the happy path is identical.

**A 404 deliberately cannot distinguish "no such row" from "not yours".** Answering 403 for a row
that exists but belongs to another user would confirm the row exists — an id-enumeration oracle.
The message says both possibilities out loud: *"Ta analiza nie istnieje lub nie należy do Ciebie."*
The client cannot tell them apart either, and `SavedAnalysisDetailComponent` renders that same
sentence rather than inventing a more specific one.

**`userId` comes from `AuthenticatedUser.requireId`, and no request record has a `userId` field at
all.** Not "we ignore it if sent" — the field does not exist, so a future edit cannot start
trusting it by accident. `SaveAnalysisRequest` (both the Java record and the TypeScript model)
carries a comment saying so, because the absence is the design and an absence has no other way to
announce itself. The frontend test asserts this **on the wire** — `Object.keys(req.request.body)`
and `req.request.params.keys()` — not on the type, because a TypeScript type is erased and the
request is what the server actually sees.

**The whole `AnalysisResponse` is stored, not just the summary columns.** A saved analysis is
re-rendered through the *same* `AnalysisResultComponent` a fresh one uses — every panel, the CEPiK
block, the price context, the risk flags. That is only possible if the payload survives whole, so
the summary columns (`make`, `model`, `productionYear`, `priceAmount`, `mileageKm`, `verdictCode`,
`overallScore`) are a denormalised copy for the list view, not the record of truth. The alternative
— storing the summary and rebuilding a view from it — means a second renderer that drifts from the
first, and it would have to invent the panels it has no data for.

**Deletion takes two clicks, and the confirm lives in the component.** There is no undo and no
soft delete, so a misclick on a list of visually similar cards is unrecoverable; the first click
only arms it, and the armed state carries the sentence *"Usunięcie jest nieodwracalne"*. Not
`window.confirm` — it cannot be asserted in a test and cannot be styled, and a guarantee no test
protects is a guarantee for exactly as long as nobody edits the file. The two-click property is
pinned by `does not delete on the first click`, which is the test that went red when the guarantee
was deliberately broken (see Measurements).

**A 404 on delete is treated as success.** The row is gone, which is what the user asked for.
Leaving it on screen next to a failure message invites a retry that can never succeed. The list
drops it and says *"Ta analiza już nie istnieje — usunięto ją z listy."* — the outcome, not the
error. Every other status keeps the row.

**A renamed row is replaced from the server's response, never from what was typed.** `updatedAt`,
and anything the server normalises, is only knowable from the response; rebuilding the row locally
would show a timestamp the database does not have. Same reasoning as the ownership decision — do
not maintain a second copy of a fact the server owns.

**A non-numeric route id is rejected before the API call.** `/saved/abc` would otherwise become
`GET /api/saved-analyses/NaN` and come back a 500 for what is, to a user, the same "not there"
outcome as a 404. Guarded in the component, and the browser check confirmed it produces no network
request and no console error at all.

**Verdict labels are a `Record` with a `?? code` fallback, not a template `@switch`.**
`verdictCode` arrives as a plain string from a row that may predate a new code, and an unknown code
must render as itself rather than as blank — a blank cell reads as "no verdict", which is a
different claim than "a verdict this build has no label for".

**The two nav links in the shell are plain `<a routerLink>`.** `app.ts` is the only eager component
in the app; one PrimeNG import there hoists the shared base chunk out of the lazy analyzer chunk,
measured at 433.50 → 526.39 kB when the logout button was tried that way. See
`frontend/CLAUDE.md` § "Auth on the client" — this change is the second time that rule bit, which
is why it is now written in two places.

**Polish number grouping is done in the component, not with the `number` pipe.** The app's
`LOCALE_ID` is still the default `en-US`, so `| number` renders a price as `64,900` — and a comma
reads as a decimal point to a Polish user, which is worse than no grouping at all. `grouped()`
calls `toLocaleString('pl-PL')` directly. Registering `pl` locale data app-wide is the real fix and
belongs with the rest of the i18n, not smuggled in here; the method's doc comment says so.

**The suggested title treats the year as a qualifier, not an identity.** `Toyota Corolla 2019`
comes from make + model + year, but a make and model that are both missing fall back to
`Analiza z <date>` even when the year is present — see Findings for why that case is not
hypothetical.

## Findings

**Two UI defects that the whole test suite could not see, found by opening a browser.** Both were
in code written that same hour, with 131 passing tests over it:

1. The save box prefilled the title **`2019`**. The mock extraction returned a year but no make or
   model, and `suggestedTitle` joined whatever parts existed — the dated fallback only fired when
   all three were absent. A list of three cars from the same year would have been three rows all
   labelled `2019`: legal, and useless for the one job a title has. The existing test covered the
   all-absent case, so it passed. Fixed, plus `does not prefill a bare year as the title` and
   `keeps the year when there is an identity for it to qualify`.
2. The list rendered `64900 PLN` and `118500 km` — no thousands grouping anywhere. No test could
   have caught this, because no test knew what the number was supposed to look like.

Neither is subtle once seen, and neither was reachable from a unit test. This is the argument for
the walkthrough being a step rather than a nicety: the suite checks the claims someone thought to
make, and a prefill nobody would choose is not a claim anyone writes down.

**Git Bash mangles non-ASCII inside inline `curl -d` arguments.** `-d '{"title":"Corolla — po
zmianie nazwy"}'` came back `400 "Nieprawidłowy JSON"`, and the em dash was the entire cause — the
*same character* in a heredoc-written file went through fine, and typing it into the browser form
later worked too. Write the JSON to a file and use `--data-binary @file`. Worth knowing because the
symptom accuses the API of a parsing bug it does not have; the first read of that 400 was "the
PATCH endpoint is broken".

**`node` under Git Bash cannot read MSYS `/tmp` paths.** `require('/tmp/x.json')` resolves to
`D:\tmp\…` and fails. Pipe the file in on stdin instead.

**`git checkout -- <path>` is a silent no-op on an untracked file.** A deliberate-break edit in a
brand-new file is *not* undone by the usual restore — the command exits 0, `git status` still shows
`??`, and the break is still there. It has to be reverted by hand and the revert verified by grep.
Caught here because `git status --porcelain` was checked after the restore; the discipline that
saved it was distrusting the restore, not remembering the rule.

## Measurements

Suites after this change: backend **363** tests in 40 classes (~23.7 s), frontend **134** in 14 spec
files (~5.4 s). The backend half added **23**: `SavedAnalysisControllerTest` (11) and
`SavedAnalysisServiceTest` (10) are new classes, and `SavedAnalysisRepositoryTest` — which came from
F-02 with the entity — gained 2. Adding the per-class totals instead gives 31 and a wrong conclusion
that some label drifted; the class count moved 38 → 40, not 41, for the same reason. The frontend half
added 35 across three new spec files and the analyzer's spec. The frontend timing read ~4.8 s on one
run and ~5.4 s on the next with no code between them: at this layer the cost is the Angular bundle
build, so sub-second movement there is noise and not a signal about the tests.

**Three deliberate-break checks, each red in the right place:**

| Break | Expected to catch it | Result |
|---|---|---|
| `deleteByIdAndUserId(id, userId)` → `deleteById(id)` | the two ownership assertions | 3 of 10 red, including both |
| `askDelete` deleting immediately instead of arming | the two-click guarantee | 2 red at `component.askDelete(41)` |
| a `userId` added to the save payload | the on-the-wire owner check | red with `expected [ 'title', 'analysis', 'userId' ] to not include 'userId'` |

The third one is the useful shape to copy: the failure message names the leaked field, so a future
regression reads as "you sent an owner" rather than "an assertion failed".

## Verified against a live local API

Every operation exercised over HTTP against a running backend (in-memory H2), not asserted from
tests. Two throwaway accounts, both local-only; passwords generated with `openssl rand` and
discarded unprinted.

| Operation | Owner | Second account |
|---|---|---|
| `GET /api/saved-analyses` anonymous | 401 | — |
| `POST /api/saved-analyses` | 201, payload round-trips whole | — |
| `GET /api/saved-analyses` | 1 row, every summary column populated | `[]` |
| `GET /api/saved-analyses/1` | 200 | 404 |
| `PATCH /api/saved-analyses/1` | 200, `note` → `null`, `updatedAt` advanced past `createdAt` | 404 |
| `DELETE /api/saved-analyses/1` | 204, then 404 on a repeat | 404 |

The `PATCH` was the one that mattered most to see rather than assume: `updatedAt`
(`14:42:20.562272`) genuinely moved past `createdAt` (`14:41:59.988747`) while `createdAt` stayed
put, which is the pair of facts a `@PreUpdate` typo silently breaks.

The second account's `[]` list is the same assertion as its three 404s, from the other side: it is
not that it was refused the row, it is that the row is not in its world at all.

**Browser walkthrough** (`:4200`, backend under `mock`): anonymous `/` → `/login?returnUrl=%2F`;
register → both nav links appear; analyse → save with a typed title and note → confirmation swaps
in with a link to `/saved`; the row shows year, price, mileage, verdict label, score, note and
timestamp; rename (title *and* note, em dash included) → row updates in place; `Otwórz analizę` →
`/saved/1` renders every panel of the stored analysis, CEPiK block correctly saying the registry
*was not checked* rather than that the car is clean; `/saved/999` → the "nie istnieje lub nie
należy do Ciebie" message; `/saved/abc` → rejected with no request made; delete → first click arms
with the irreversibility warning, `Anuluj` restores, second click removes the row; empty state
survives a reload, so the server agrees rather than just the local list.

## Left undone

- **No pagination, search or sort.** The list is every row the user owns, newest first. Fine at
  demo scale and a real problem at a hundred rows; the endpoint would need a `Pageable` and the
  component an infinite scroll or pager.
- **A saved analysis cannot be re-run.** The stored payload is a snapshot — the LLM's verdict as of
  that day, with the market range as of that day. Nothing re-fetches it, and nothing marks it
  stale, so a row read six months later reads as current. FR-013's "compare two analyses" would
  need this settled first.
- **The list shows `createdAt` only.** A renamed row's `updatedAt` advances in the database and is
  returned in the response, but nothing displays it — a user cannot tell which note they edited
  last.
- **No export, no share, no bulk delete.** Deleting five rows is ten clicks.
- **Still no delete-account endpoint**, so the throwaway rows this change created locally, and the
  two `probe-…@example.pl` rows in production from F-03, all stay.
- **Not yet verified in production.** The frontend half is committed but the deploy and the live
  check are outstanding — until then this is verified locally and nothing more.
