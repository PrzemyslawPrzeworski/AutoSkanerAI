## Team engineering rules

Installed by `@przemyslawprzeworski/ai-toolkit`. Edits inside the sentinel markers are
overwritten on the next `npm install` — put your own rules outside them.

These are the always-on rules. The full review checklist lives in the `code-review` skill
(`.claude/skills/code-review/SKILL.md`) and is loaded only when a review is asked for; keeping it
out of here is deliberate, because a checklist in `CLAUDE.md` is read on every turn and pays for
itself on almost none of them.

- **Never present absent data as a negative finding.** "No record found" and "record found, and
  it is empty" are different answers and must stay distinguishable from the data source to the
  screen. A `?? []`, an `orElse(List.of())`, or a UI branch keyed on `length === 0` that merges
  the two is a defect, not defensive coding — it turns "we did not check" into "we checked and it
  was fine".
- **A check whose signal cannot fail is worse than no check.** No `catch` that swallows and exits
  0, no `|| true` around a verification step, no glob that reports green when it matched nothing.
  A layer that cannot report its own absence will eventually be absent.
- **Fail loudly on a missing toolchain, and check the capability rather than the variable.** Look
  for `javac`, not for `JAVA_HOME`; "present and wrong" reads far worse than "absent".
- **No secrets in source, in fixtures, or in logs.** Environment variables only. User-supplied
  text on its way into a log gets control characters stripped and its length bounded.
- **Untrusted text reaching a model prompt is delimited, escaped, and placed after the
  instructions.** Model output never reaches a shell command, a label, a filename, or an `eval`.
- **A changed behaviour needs a changed test.** A fixture hand-written to match the implementation
  is not evidence; anything parsing an external payload needs a verbatim capture.
- **Errors name the operation and the relevant input.** No empty `catch`. Never return a default
  where the honest answer is "I could not tell".
