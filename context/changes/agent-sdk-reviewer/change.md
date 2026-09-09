---
change_id: agent-sdk-reviewer
title: Build the reviewer a second time on the Claude Agent SDK, then pick
status: implementing
created: 2026-09-09
updated: 2026-09-10
archived_at: null
---

## Notes

build the same code reviewer a second time on the Claude Agent SDK — the ready-made half of M5-L2's ready-made-vs-assemble-it-yourself comparison. Reuse the existing shared schema, prompt, verdict and diff modules from packages/code-reviewer; only the loop, the tools and the auth path differ. Ends in a written pick between the two runners, which is what M5-L3's promptfoo provider will wrap.
