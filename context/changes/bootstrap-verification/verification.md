---
bootstrapped_at: 2026-05-24T19:40:00Z
starter_id: spring
starter_name: Spring Boot
project_name: autoskaner-ai
language_family: java
package_manager: maven
cwd_strategy: subdir-then-move
bootstrapper_confidence: verified
phase_3_status: ok
audit_command: "null"
---

## Hand-off

```yaml
starter_id: spring
package_manager: maven
project_name: autoskaner-ai
hints:
  language_family: java
  team_size: solo
  deployment_target: fly
  ci_provider: github-actions
  ci_default_flow: auto-deploy-on-merge
  bootstrapper_confidence: verified
  path_taken: standard
  quality_override: false
  self_check_answers: null
  has_auth: true
  has_payments: false
  has_realtime: false
  has_ai: true
  has_background_jobs: false
```

**Why this stack**: Solo developer with strong Java and Angular experience building a used-car listing analyser in 3 weeks of after-hours work. Java is the builder's strongest language by a wide margin, which outweighs the thinner AI ecosystem compared to JS/TS — calling an LLM API from Java is straightforward HTTP regardless. Spring Boot is the recommended default for (web-app, java), passes all four agent-friendly gates, and has verified bootstrapper confidence. AI integration is handled via a custom AiAnalysisService interface with a MockAiAnalysisService (deterministic mocks) and LlmAnalysisService (real LLM provider — Claude API or OpenAI) as Profile-switched implementations; this is a code-level pattern, not a scaffolding concern. Fly.io is the deployment target for the backend; Render is a noted alternative worth revisiting given its simpler Git integration for a first-time deployer. Angular will be scaffolded separately as the frontend. CI on GitHub Actions with auto-deploy on merge.

## Pre-scaffold verification

| Signal      | Value                              | Severity | Notes                                     |
| ----------- | ---------------------------------- | -------- | ----------------------------------------- |
| npm package | n/a — non-JS starter               | n/a      | no npm package to check                   |
| GitHub repo | not run                            | unknown  | gh CLI not available in this environment  |

## Scaffold log

**Resolved invocation**: `curl -s https://start.spring.io/starter.tgz -d dependencies=web,devtools -d type=maven-project -d javaVersion=21 -d groupId=com.example -d artifactId=autoskaner-ai | tar -xzf - -C .bootstrap-scaffold`
**Strategy**: scaffold into temp directory then move files up
**Exit code**: 0
**Files moved**: 10
**Conflicts (.scaffold siblings)**: none
**.gitignore handling**: moved silently (no prior .gitignore in cwd)
**.bootstrap-scaffold cleanup**: deleted

Files moved:
- `.gitattributes`
- `.gitignore`
- `.mvn/wrapper/maven-wrapper.properties`
- `HELP.md`
- `mvnw`
- `mvnw.cmd`
- `pom.xml`
- `src/main/java/com/example/autoskaner_ai/AutoskanerAiApplication.java`
- `src/main/resources/application.properties`
- `src/test/java/com/example/autoskaner_ai/AutoskanerAiApplicationTests.java`

## Post-scaffold audit

**Tool**: skipped — no built-in audit tool for java
**Recommended external tool**: OWASP Dependency-Check (`mvn org.owasp:dependency-check-maven:check`) or Snyk (`snyk test`) for vulnerability scanning of Maven dependencies. Consider adding one to your CI pipeline before going to production.

## Hints recorded but not acted on

| Hint                    | Value                  |
| ----------------------- | ---------------------- |
| bootstrapper_confidence | verified               |
| quality_override        | false                  |
| path_taken              | standard               |
| self_check_answers      | null                   |
| team_size               | solo                   |
| deployment_target       | fly                    |
| ci_provider             | github-actions         |
| ci_default_flow         | auto-deploy-on-merge   |
| has_auth                | true                   |
| has_payments            | false                  |
| has_realtime            | false                  |
| has_ai                  | true                   |
| has_background_jobs     | false                  |

## Next steps

Next: a future skill will set up agent context (CLAUDE.md, AGENTS.md). For now, your project is scaffolded and verified — happy hacking.

Useful manual steps in the meantime:
- `git init` (if you have not already) to start your own repo history.
- Review any `.scaffold` siblings the conflict policy created and decide which version to keep (none in this run — clean scaffold).
- Run `./mvnw spring-boot:run` to verify the project starts locally.
- Consider adding OWASP Dependency-Check to your Maven build for ongoing vulnerability tracking.
