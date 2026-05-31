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
}
