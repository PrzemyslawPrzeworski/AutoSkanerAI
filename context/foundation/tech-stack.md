---
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
---

## Why this stack

Solo developer with strong Java and Angular experience building a used-car listing analyser in 3 weeks of after-hours work. Java is the builder's strongest language by a wide margin, which outweighs the thinner AI ecosystem compared to JS/TS — calling an LLM API from Java is straightforward HTTP regardless. Spring Boot is the recommended default for (web-app, java), passes all four agent-friendly gates, and has verified bootstrapper confidence. AI integration is handled via a custom AiAnalysisService interface with a MockAiAnalysisService (deterministic mocks) and LlmAnalysisService (real LLM provider — Claude API or OpenAI) as Profile-switched implementations; this is a code-level pattern, not a scaffolding concern. Fly.io is the deployment target for the backend; Render is a noted alternative worth revisiting given its simpler Git integration for a first-time deployer. Angular will be scaffolded separately as the frontend. CI on GitHub Actions with auto-deploy on merge.
