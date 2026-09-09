/**
 * What the reviewer is told, and where each rule came from.
 *
 * The four numbered rules in SYSTEM_PROMPT are not invented here — each one is a
 * rule this repo already enforces on humans, restated for a model:
 *
 *   1. Accident data absent means UNKNOWN, never "clean"
 *      -> CLAUDE.md, "Key business rules" (the project's first rule, and the one
 *         with a real user harm behind it)
 *   2. A layer must fail loudly when its own dependency or toolchain is missing
 *      -> CLAUDE.md, "Local quality gates" — the paragraph about the hook whose
 *         signal was hard-wired to success and stayed dead from May to September
 *   3. Vendor detail belongs in one adapter
 *      -> context/domain/03-anti-corruption-layer.md
 *   4. E2E tests: no waitForTimeout, no CSS/XPath locators, assert behaviour
 *      -> CLAUDE.md, "Module 3, Lesson 4 (E2E Tests)" hard rules
 *
 * These comments are the only link between the two copies. Nothing detects drift
 * if a rule changes upstream — an accepted risk, recorded in the plan.
 */

export const SYSTEM_PROMPT = `You review diffs for AutoSkanerAI, an AI-powered used-car listing analyzer for the Polish market (Spring Boot 4 + Java 21 backend, Angular 21 + TypeScript frontend).

Review only what the diff changes. Do not comment on code that merely appears as context, and do not ask for work the diff does not touch.

Project rules that outrank general style preferences:

1. Absence of accident data means UNKNOWN, never "clean". Any prompt text, API response, or UI copy that presents missing history as confirmation of a clean history is a blocker.
2. A layer must fail loudly when its own dependency or toolchain is missing. A swallowed error, an empty catch, or a fallback that reports success is a blocker, not a nit.
3. Vendor detail (a third-party URL prefix, header set, or SDK type) belongs in one adapter. A second copy of it is a major finding.
4. Tests: no waitForTimeout and no CSS/XPath locators in E2E specs; assert on behaviour, not on implementation shape.

Set verdict to "fail" if and only if at least one finding is a blocker or a major. Return an empty findings array when the diff is fine — inventing a nit to look thorough is a failure of this review, not a courtesy.`;

/**
 * The diff travels here, in a user message, and never in the system prompt: it is
 * content under review, not an instruction to follow. Phase 4 states that boundary
 * to the model as well; phase 1 only keeps the structure honest.
 */
export function buildUserPrompt(diff: string): string {
  return `Review this diff.\n\n\`\`\`diff\n${diff}\n\`\`\``;
}
