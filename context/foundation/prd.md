---
project: AutoSkanerAI
version: 1
status: draft
created: 2026-05-24
context_type: greenfield
product_type: web-app
target_scale:
  users: small
  qps: low
  data_volume: small
timeline_budget:
  mvp_weeks: 3
  hard_deadline: null
  after_hours_only: true
---

## Vision & Problem Statement

Buying a used car is time-consuming and risky. Listings are incomplete, imprecise, or written with marketing spin. Sellers omit service history, dodge accident declarations, and describe equipment vaguely. A private buyer has to read each listing carefully, identify gaps and red flags, prepare questions, and decide whether the offer is worth pursuing — all of which can take dozens of minutes per listing, most of it wasted on bad offers.

AutoSkanerAI compresses that process from tens of minutes to a few minutes. Given a listing (pasted text, link, or manually entered data), the app extracts key facts, flags risks, identifies missing or ambiguous information, generates a list of questions to ask the seller, and produces a final recommendation — so the user can quickly decide whether an offer is worth their time.

## User & Persona

Primary persona: a private individual actively shopping for a used car. Not a professional. Evaluating a handful of offers over a few weeks. Wants to avoid getting burned by a bad deal. Primary user is the builder — shaped from personal car-buying experience.

## Success Criteria

### Primary
The MVP works if a user can: add a listing (via link, pasted text, or manual fields) → see extracted structured data → receive a risk analysis with identified gaps → get a targeted list of questions to ask the seller → receive a final recommendation with per-category scoring.

### Secondary
The app clearly separates confirmed facts from inferences and shows the user where each piece of information came from.

### Guardrails
- The app must never present data not found in the listing as a confirmed fact.
- The app must not draw conclusions from the absence of accident information — missing data means unknown, not clean. The app may report a confirmed accident declaration (from the listing or a vehicle history report) as a fact, but must never imply a car is accident-free simply because no accident was mentioned.

## User Stories

### US-01: Analysing a listing from a URL

- **Given** a logged-in user who has a link to a used-car listing
- **When** they paste the URL into AutoSkanerAI
- **Then** the app attempts to fetch the listing, extracts structured data, and presents the full analysis (verified data table, equipment breakdown, risk flags, seller questions, recommendation with per-category scores) — or falls back to a text-paste / manual-entry input if the URL cannot be fetched

## Functional Requirements

### Listing input
- FR-001: User can add a listing by pasting a URL. Priority: must-have
  > Socrates: Counter-argument considered: "URL scraping is fragile — portals block bots, break without warning." Resolution: kept; URL is the most natural first action. Graceful fallback to text paste handles failures.
- FR-002: User can add a listing by pasting raw listing text. Priority: must-have
  > Socrates: No counter-argument — reliable fallback when URL fetch fails. Stands.
- FR-003: User can add a listing by manually entering key fields. Priority: must-have
  > Socrates: No counter-argument — covers cases where neither URL nor paste is practical. Stands.

### Extraction & display
- FR-004: App extracts structured data from the listing input (make, model, year, price, mileage, fuel, transmission, equipment, service history, accident claims, origin, seller type). Priority: must-have
  > Socrates: Counter-argument considered: "Extraction errors look authoritative — wrong data in a clean table could mislead." Resolution: mitigated by FR-005 (user verification table) and the guardrail that the app must never present inferences as confirmed facts.
- FR-005: App displays extracted data in a structured table for user verification. Priority: must-have
  > Socrates: No counter-argument — extraction quality is the whole product. Stands.

### Analysis
- FR-006: App analyses equipment presence (confirmed / missing / unclear) against a reference list. Priority: must-have
  > Socrates: No counter-argument — equipment gaps are a primary decision signal. Stands.
- FR-007: App generates a risk analysis (red flags list with short explanations). Priority: must-have
  > Socrates: No counter-argument — risk flags are the core value differentiator. Stands.
- FR-008: App generates a list of questions to ask the seller based on identified gaps and risks. Priority: must-have
  > Socrates: No counter-argument — questions are the practical output users take to the seller. Stands.
- FR-009: App outputs a final recommendation as a verdict label (worth checking / check after more info / high risk — skip) plus a per-category scoring breakdown (completeness, equipment, risk, value, overall). Priority: must-have
  > Socrates: Counter-argument considered: "A single verdict oversimplifies — users may skip a good car based on one label." Resolution: FR-009 modified to include scoring breakdown alongside the label so users can see the reasoning and override the verdict.

### Persistence
- FR-010: User can save a listing analysis to their account. Priority: must-have
  > Socrates: No counter-argument — without persistence, the app has no memory across sessions. Stands.
- FR-011: User can view previously saved listing analyses. Priority: must-have
  > Socrates: No counter-argument — pairs with FR-010. Stands.
- FR-012: User can delete a saved listing. Priority: must-have
  > Socrates: No counter-argument — basic data hygiene. Stands.

### Comparison & advisory
- FR-013: User can compare multiple saved listings side by side. Priority: nice-to-have
  > Socrates: Counter-argument considered: "Full comparison UI is complex — a list with scores may be enough." Resolution: kept as nice-to-have; user confirmed comparison is a real use case since nobody buys the first car they look at.
- FR-014: App provides a search advisor — user enters requirements (budget, body type, year, mileage, fuel, transmission, priorities, required equipment); app suggests models, trims, and search filters. Priority: nice-to-have
  > Socrates: No counter-argument at this stage — kept as nice-to-have scope signal.

### Preferences & external data
- FR-015: User can set personal preferences (budget, required equipment, priorities) that the analysis takes into account. Priority: nice-to-have
  > Socrates: No counter-argument — preferences personalise the risk and recommendation output.
- FR-016: User can paste or enter data from a vehicle history report; app helps interpret it alongside the listing analysis. Priority: nice-to-have
  > Socrates: No counter-argument — aligns with explicit scope: no full state-registry API integration, but manual data input + interpretation is in.
- FR-017: App queries the CEPiK / historiapojazdu.gov.pl registry directly using the vehicle identification number and displays the retrieved history alongside the listing analysis. Priority: nice-to-have

## Non-Functional Requirements

- Analysis response time: a listing analysis produces its result within a perceptible wait; any operation exceeding 2 seconds presents continuous visible progress to the user, and no analysis runs for longer than 30 seconds without a visible result or error.
- Factual honesty: every data point in the output is labelled as either extracted from the listing or inferred by the app. The app never presents an inference as a confirmed fact.
- Language: input and output in Polish. The app is designed for the Polish used-car market.
- Platform: web only. The app is usable on the latest two major versions of mainstream desktop browsers. No mobile native app in MVP.

## Business Logic

The app evaluates a used-car listing and produces a risk-weighted recommendation by applying structured rules to the data extracted from that listing.

Inputs (as seen by the user): listing text, a URL, or manually entered fields — optionally supplemented with a vehicle history report and personal buyer preferences.

Rule: the app classifies each extracted data point as confirmed, missing, or unclear; applies a set of risk rules (e.g. no accident declaration → red flag; no vehicle identification number → red flag; price suspiciously low relative to year and mileage → flag); weights the findings into per-category scores; and produces a verdict label plus a list of targeted seller questions.

Output: structured data table + equipment breakdown + risk flags + seller questions + per-category scores (completeness, equipment, risk, value, overall) + verdict label.

The app reports accident information only when it is explicitly present in the listing or in a vehicle history report provided by the user. Absence of accident information is surfaced as an information gap, not as evidence the car is accident-free. The final determination of a car's history requires physical inspection and verification by the buyer or a specialist.

## Access Control

Users authenticate with email and password or via a third-party OAuth provider. Listing analyses are tied to the authenticated user's account and persist across sessions. Flat user model — no admin or guest roles in MVP. Every authenticated user has identical access to their own data. Unauthenticated visitors cannot access any analysis functionality.

## Non-Goals

- No automatic market scraping (Polish and international used-car portals) — scraping is fragile, legally grey, and unnecessary when the user pastes listings manually.
- No claim of accident-free status from silence — the app reports confirmed accident information when present in the listing or vehicle history report, and flags the absence of that information as an unknown, never as a clean record. It does not replace physical inspection or a specialist's assessment.
- No live CEPiK integration in MVP — users paste or enter vehicle history data manually for the first version (FR-016). Live registry queries are explicitly planned as a post-MVP nice-to-have (FR-017) once the core flow is stable.
- No complete factory equipment database for all makes and models — equipment analysis is based on listing text and a curated reference list, not a full manufacturer specification database.
- No monetisation or premium accounts — no payments, no subscription tiers in MVP.
- No mobile native app — web only.
- No sharing of analyses between users — analyses are private per account; no public listings or shared workspaces.

## Open Questions

No open questions at time of writing. All shape-notes quality checks passed (status: accepted).
