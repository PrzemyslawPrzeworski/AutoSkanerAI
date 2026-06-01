---
change_id: s-01
title: Core analysis flow — paste URL or text, receive full AI analysis on screen
status: impl_reviewed
created: 2026-06-01
updated: 2026-06-01

archived_at: null
---

## Notes

S-01 from `context/foundation/roadmap.md`. Unlocked by F-01 (llm-analysis-wiring, complete).

User pastes a URL to a Polish used-car listing (or raw text if URL cannot be fetched) and sees the full analysis: extracted data table, equipment breakdown, risk flags, seller questions, per-category scores, and verdict label.

PRD refs: FR-001, FR-002, FR-004, FR-005, FR-006, FR-007, FR-008, FR-009, US-01.

Open question to resolve during planning: URL fallback UX — automatic redirect to text-paste on fetch failure, or manual switch?
