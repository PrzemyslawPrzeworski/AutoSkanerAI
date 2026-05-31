package com.example.autoskaner_ai.analysis.llm;

import com.example.autoskaner_ai.analysis.AiAnalysisService;
import com.example.autoskaner_ai.analysis.AnalysisResult;
import com.example.autoskaner_ai.analysis.VerdictCode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("live-llm")
@EnabledIfEnvironmentVariable(named = "AWS_PROFILE", matches = ".+",
        disabledReason = "No AWS_PROFILE — Bedrock live test skipped")
@SpringBootTest
@ActiveProfiles("bedrock")
class BedrockLiveTest {

    private static final String LISTING = """
            BMW 3 E46 2002, 180000 km, 2.0 benzyna, skrzynia manualna.
            VIN: WBAAM31060GE12345. Cena: 18500 zł.
            Auto z Polski, pierwsza ręka. Klimatyzacja, ABS, airbagi.
            Historia serwisowa dostępna. Sprzedający twierdzi bezwypadkowe,
            ale brak dokumentów potwierdzających. Opony zimowe w zestawie.
            """;

    @Autowired
    private AiAnalysisService service;

    @Test
    void analyze_realBedrockCall_returnsStructuredResult() {
        AnalysisResult result = service.analyze(LISTING);

        assertThat(result).isNotNull();
        assertThat(result.verdict()).isNotNull();
        assertThat(result.verdict().code())
                .isIn(EnumSet.allOf(VerdictCode.class));
        assertThat(result.riskFlags()).isNotNull();
        assertThat(result.scores()).isNotNull();
        assertThat(result.meta()).isNotNull();
        assertThat(result.meta().provider()).isEqualTo("bedrock");
        assertThat(result.meta().latencyMs()).isPositive();
    }
}
