---
project: AutoSkanerAI
context_type: greenfield
updated: 2026-05-24
product_type: web-app
target_scale:
  users: small
timeline_budget:
  mvp_weeks: 3
  hard_deadline: null
  after_hours_only: true
checkpoint:
  current_phase: 7
  phases_completed: [1, 2, 3, 4, 5, 6]
  frs_drafted: 16
  quality_check_status: accepted
---

## Vision & Problem Statement

Buying a used car is time-consuming and risky. Listings are incomplete, imprecise, or written with marketing spin. Sellers omit service history, dodge accident declarations, and describe equipment vaguely. A private buyer has to read each listing carefully, identify gaps and red flags, prepare questions, and decide whether the offer is worth pursuing — all of which can take dozens of minutes per listing, most of it wasted on bad offers.

AutoSkanerAI compresses that process from tens of minutes to a few minutes. Given a listing (pasted text, link, or manually entered data), the app extracts key facts, flags risks, identifies missing or ambiguous information, generates a list of questions to ask the seller, and produces a final recommendation — so the user can quickly decide whether an offer is worth their time.

Pain: Evaluating used-car listings manually is slow and error-prone — important red flags are easy to miss, and weak offers waste a lot of the buyer's time.
Person: Private individual shopping for their next used car (primary user = the builder, from personal experience).
Moment: When looking at a specific listing and deciding whether to contact the seller or move on.
Cost today: Dozens of minutes per listing spent reading, cross-referencing, gap-hunting, and question-drafting — most of it spent on offers that don't pan out.

Pain category: Workflow friction — buyers know what to look for, but assembling the full picture manually from each listing is tedious and slow.

## User & Persona

Primary persona: Private individual actively shopping for a used car. Not a professional. Evaluating a handful of offers over a few weeks. Wants to avoid getting burned by a bad deal. Primary user is the builder — shaped from personal car-buying experience.

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
  > Socrates: Counter-argument considered: "LLM extraction errors look authoritative — wrong data in a clean table could mislead." Resolution: mitigated by FR-005 (user verification table) and the guardrail that the app must never invent data.
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
- FR-014: App provides a search advisor — user enters requirements (budget, body type, year, mileage, fuel, transmission, priorities, required equipment); app suggests models, trims, and filters. Priority: nice-to-have
  > Socrates: No counter-argument at this stage — kept as nice-to-have scope signal.

### Preferences & external data
- FR-015: User can set personal preferences (budget, required equipment, priorities) that the analysis takes into account. Priority: nice-to-have
  > Socrates: No counter-argument — preferences personalise the risk and recommendation output.
- FR-016: User can paste or enter data from a CEPiK / historiapojazdu.gov.pl report; app helps interpret it alongside the listing analysis. Priority: nice-to-have
  > Socrates: No counter-argument — aligns with the notes' explicit scope: no full CEPiK API integration, but manual data + interpretation is in.

## Non-Goals

- No automatic market scraping (Otomoto, OLX, Gratka, mobile.de, etc.) — scraping is fragile, legally grey, and unnecessary when the user pastes listings manually.
- No guarantee of detecting accident damage, odometer fraud, or hidden faults — the app flags risks and gaps only; it does not replace physical inspection or a specialist.
- No full CEPiK / historiapojazdu.gov.pl API integration — users paste or enter CEPiK report data manually; no live state-registry connection in MVP.
- No monetisation or premium accounts — no payments, no subscription tiers in MVP.
- No mobile native app — web only.
- No sharing of analyses between users — analyses are private per account.

## Business Logic

The app evaluates a used-car listing and produces a risk-weighted recommendation by applying structured rules to the data extracted from that listing.

Inputs (as seen by the user): listing text, a URL, or manually entered fields — optionally supplemented with a CEPiK report and personal buyer preferences.

Rule: the app classifies each extracted data point as confirmed, missing, or unclear; applies a set of risk rules (e.g. no accident declaration → red flag; no VIN → red flag; price suspiciously low relative to year/mileage → flag); weights the findings into per-category scores; and produces a verdict label plus a list of targeted seller questions.

Output: structured data table + equipment breakdown + risk flags + seller questions + per-category scores + verdict label.

The app does not claim to definitively determine whether a car was in an accident or has hidden faults. It surfaces what the listing says, what it omits, and what is ambiguous — the final decision requires physical inspection by the buyer or a specialist.

## Non-Functional Requirements

- Analysis response time: user-perceived completion within a few seconds for a typical listing (no spinner left running for >10s without visible progress feedback).
- Factual honesty: every data point in the output is labelled as either extracted from the listing or inferred by the app. The app never presents an inference as a confirmed fact.
- Language: input and output in Polish. The app is designed for the Polish used-car market.
- Platform: web only. No mobile native app in MVP.

## User Stories

### US-01: Analysing a listing from a URL
Given I have a link to a used-car listing,
When I paste the URL into AutoSkanerAI,
Then the app attempts to fetch the listing, extracts structured data, and presents the full analysis (verified data table, equipment breakdown, risk flags, seller questions, recommendation with scores) — or falls back to manual input if the URL cannot be fetched.

## Success Criteria

### Primary
The MVP works if a user can: paste a link to a listing (with text paste as fallback) → see extracted structured data → receive a risk analysis → get a list of questions to ask the seller → receive a final recommendation (worth checking / check only after more info / high risk — skip).

### Secondary
The app clearly separates confirmed facts from inferences and shows the user where each piece of information came from.

### Guardrails
- The app must never invent data not present in the listing.
- The app must not claim it can detect if a car was in an accident — it can only flag risks and gaps.

MVP timeline: 3 weeks of after-hours work.

## Access Control

Users log in with email + password or OAuth. Listings are tied to the user's account and persist across sessions. Flat user model — no admin or guest roles in MVP. Every authenticated user has the same access.
