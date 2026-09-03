package com.example.autoskaner_ai.analysis.llm;

import com.example.autoskaner_ai.analysis.RiskSeverity;
import com.example.autoskaner_ai.analysis.VerdictCode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisResponseParserTest {

    private AnalysisResponseParser parser;

    @BeforeEach
    void setUp() {
        parser = new AnalysisResponseParser(new ObjectMapper());
    }

    private String fixture(String name) throws Exception {
        var url = getClass().getClassLoader().getResource("fixtures/llm/" + name);
        assertThat(url).as("fixture %s not found", name).isNotNull();
        return Files.readString(Path.of(url.toURI()));
    }

    @Test
    void parsesFullValidResponse() throws Exception {
        var result = parser.parse(fixture("valid-full-response.json"), "bedrock", "haiku", 500L);

        assertThat(result).isNotNull();
        assertThat(result.extracted().make()).isEqualTo("BMW");
        assertThat(result.extracted().model()).isEqualTo("3 Series");
        assertThat(result.extracted().year()).isEqualTo(2018);
        assertThat(result.extracted().mileageKm()).isEqualTo(120000);
        assertThat(result.equipment()).hasSize(2);
        assertThat(result.riskFlags()).hasSize(1);
        assertThat(result.riskFlags().get(0).severity()).isEqualTo(RiskSeverity.MEDIUM);
        assertThat(result.sellerQuestions()).hasSize(2);
        assertThat(result.scores().completeness()).isEqualTo(75);
        assertThat(result.scores().overall()).isEqualTo(64);
        assertThat(result.verdict().code()).isEqualTo(VerdictCode.NEEDS_MORE_INFO);
        assertThat(result.meta().provider()).isEqualTo("bedrock");
        assertThat(result.meta().model()).isEqualTo("haiku");
        assertThat(result.meta().latencyMs()).isEqualTo(500L);
        assertThat(result.meta().generatedAt()).isNotNull();
    }

    @Test
    void parsesResponseWithMarkdownFence() throws Exception {
        var result = parser.parse(fixture("valid-response-with-fence.json"), "openrouter", "llama", 200L);

        assertThat(result).isNotNull();
        assertThat(result.extracted().make()).isEqualTo("Toyota");
        assertThat(result.verdict().code()).isEqualTo(VerdictCode.WORTH_CHECKING);
        assertThat(result.meta().provider()).isEqualTo("openrouter");
    }

    @Test
    void throwsOnScoreOutOfRange() throws Exception {
        String json = fixture("invalid-score-out-of-range.json");

        assertThatThrownBy(() -> parser.parse(json, "bedrock", "haiku", 100L))
                .isInstanceOf(LlmResponseSchemaException.class)
                .hasMessageContaining("150")
                .extracting(e -> ((LlmResponseSchemaException) e).getFieldPath())
                .isEqualTo("scores.completeness");
    }

    @Test
    void throwsOnInvalidVerdictCode() throws Exception {
        String json = fixture("invalid-verdict-code.json");

        assertThatThrownBy(() -> parser.parse(json, "bedrock", "haiku", 100L))
                .isInstanceOf(LlmResponseSchemaException.class)
                .extracting(e -> ((LlmResponseSchemaException) e).getFieldPath())
                .isEqualTo("verdict.code");
    }

    @Test
    void throwsOnMissingRequiredField() throws Exception {
        String json = fixture("invalid-missing-field.json");

        assertThatThrownBy(() -> parser.parse(json, "bedrock", "haiku", 100L))
                .isInstanceOf(LlmResponseSchemaException.class)
                .extracting(e -> ((LlmResponseSchemaException) e).getFieldPath())
                .isEqualTo("equipment");
    }

    @Test
    void throwsOnMalformedJson() throws Exception {
        String json = fixture("invalid-malformed-json.json");

        assertThatThrownBy(() -> parser.parse(json, "bedrock", "haiku", 100L))
                .isInstanceOf(LlmResponseSchemaException.class)
                .extracting(e -> ((LlmResponseSchemaException) e).getFieldPath())
                .isEqualTo("root");
    }

    // -------------------------------------------------------------------------------------------
    // The minimal spine. Every case below used to parse into a 200 the user could not act on.
    //
    // Oracle: the locked output schema (CLAUDE.md, and llm-analysis-wiring/plan.md § "Locked output
    // schema") — a stated product guardrail, not this class. What the parser currently accepts is
    // deliberately NOT the oracle; accepting all of these is the defect.
    // -------------------------------------------------------------------------------------------

    /**
     * The hollow 200: all six containers present, every leaf inside them null. This is the shape a
     * free-tier model produces when it answers structurally but understands nothing, and before the
     * spine check it parsed cleanly — yielding an analysis with no car, no verdict, and five scores
     * of {@code 0} that no model ever produced.
     */
    @Test
    void throwsOnAHollowResponseWhereEveryLeafIsNull() throws Exception {
        String json = fixture("hollow-all-leaves-null.json");

        assertThatThrownBy(() -> parser.parse(json, "openrouter", "free-model", 100L))
                .isInstanceOf(LlmResponseSchemaException.class)
                .extracting(e -> ((LlmResponseSchemaException) e).getFieldPath())
                .isEqualTo("scores.completeness");
    }

    /**
     * A single absent score. This is the case boxing {@code ScoresDto} exists for: with primitive
     * {@code int}, {@code risk} deserialised to {@code 0} and the parse succeeded — reporting a
     * number nobody generated for the one category the whole verdict turns on.
     */
    @Test
    void throwsOnAnAbsentScoreRatherThanReadingItAsZero() throws Exception {
        String json = fixture("invalid-missing-score.json");

        assertThatThrownBy(() -> parser.parse(json, "openrouter", "free-model", 100L))
                .isInstanceOf(LlmResponseSchemaException.class)
                .extracting(e -> ((LlmResponseSchemaException) e).getFieldPath())
                .isEqualTo("scores.risk");
    }

    @Test
    void throwsOnANullVerdictCode() throws Exception {
        String json = fixture("invalid-missing-verdict-code.json");

        assertThatThrownBy(() -> parser.parse(json, "openrouter", "free-model", 100L))
                .isInstanceOf(LlmResponseSchemaException.class)
                .extracting(e -> ((LlmResponseSchemaException) e).getFieldPath())
                .isEqualTo("verdict.code");
    }

    @Test
    void throwsWhenTheCarIsNotIdentified() throws Exception {
        String json = fixture("invalid-missing-make.json");

        assertThatThrownBy(() -> parser.parse(json, "openrouter", "free-model", 100L))
                .isInstanceOf(LlmResponseSchemaException.class)
                .extracting(e -> ((LlmResponseSchemaException) e).getFieldPath())
                .isEqualTo("extracted.make");
    }

    /**
     * Everything outside the spine stays optional, and that has to be asserted too — otherwise a
     * later tightening of the spine could quietly start 502ing a listing that simply does not
     * publish its plate, which CLAUDE.md records as ordinary for a URL-only Otomoto advert.
     */
    @Test
    void aResponseMissingOptionalFieldsStillParses() throws Exception {
        var result = parser.parse(fixture("valid-response-with-fence.json"), "openrouter", "llama", 200L);

        assertThat(result.extracted().registrationPlate()).isNull();
        assertThat(result.extracted().accidentClaim()).isNull();
        assertThat(result.extracted().make()).isEqualTo("Toyota");
    }

    // -------------------------------------------------------------------------------------------
    // Enum.valueOf(null) throws NullPointerException, not IllegalArgumentException. Both routes
    // below therefore escaped the catches entirely and surfaced as the catch-all 500 "Błąd serwera"
    // — a server error for what is plainly a malformed provider response.
    // -------------------------------------------------------------------------------------------

    @Test
    void aNullSeverityIsASchemaFailureNotAServerError() throws Exception {
        String json = fixture("invalid-null-severity.json");

        assertThatThrownBy(() -> parser.parse(json, "openrouter", "free-model", 100L))
                .isInstanceOf(LlmResponseSchemaException.class)
                .extracting(e -> ((LlmResponseSchemaException) e).getFieldPath())
                .isEqualTo("riskFlags[].severity");
    }

    @Test
    void aNullEquipmentStatusIsASchemaFailureNotAServerError() throws Exception {
        String json = fixture("invalid-null-equipment-status.json");

        assertThatThrownBy(() -> parser.parse(json, "openrouter", "free-model", 100L))
                .isInstanceOf(LlmResponseSchemaException.class)
                .extracting(e -> ((LlmResponseSchemaException) e).getFieldPath())
                .isEqualTo("equipment[].status");
    }
}
