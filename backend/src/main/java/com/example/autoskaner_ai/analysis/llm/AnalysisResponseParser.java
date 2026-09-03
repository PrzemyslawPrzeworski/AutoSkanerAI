package com.example.autoskaner_ai.analysis.llm;

import com.example.autoskaner_ai.analysis.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Component
public class AnalysisResponseParser {

    private final ObjectMapper objectMapper;

    public AnalysisResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AnalysisResult parse(String rawModelText, String provider, String model, long latencyMs) {
        String json = stripFences(rawModelText);
        ResponseDto dto;
        try {
            dto = objectMapper.readValue(json, ResponseDto.class);
        } catch (Exception e) {
            throw new LlmResponseSchemaException("Nie można sparsować JSON: " + e.getMessage(), "root");
        }

        validateRequired(dto);
        validateScores(dto.scores());
        ExtractedData extracted = mapExtracted(dto.extracted());
        List<EquipmentItem> equipment = mapEquipment(dto.equipment());
        List<RiskFlag> riskFlags = mapRiskFlags(dto.riskFlags());
        List<String> sellerQuestions = dto.sellerQuestions() != null ? dto.sellerQuestions() : List.of();
        CategoryScores scores = mapScores(dto.scores());
        Verdict verdict = mapVerdict(dto.verdict());
        AnalysisMeta meta = new AnalysisMeta(provider, model, latencyMs, Instant.now());

        return new AnalysisResult(extracted, equipment, riskFlags, sellerQuestions, scores, verdict, meta);
    }

    private String stripFences(String raw) {
        String trimmed = raw.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline != -1) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3).stripTrailing();
            }
        }
        return trimmed;
    }

    private void validateRequired(ResponseDto dto) {
        if (dto.extracted() == null) throw new LlmResponseSchemaException("Pole wymagane", "extracted");
        if (dto.equipment() == null) throw new LlmResponseSchemaException("Pole wymagane", "equipment");
        if (dto.riskFlags() == null) throw new LlmResponseSchemaException("Pole wymagane", "riskFlags");
        if (dto.sellerQuestions() == null) throw new LlmResponseSchemaException("Pole wymagane", "sellerQuestions");
        if (dto.scores() == null) throw new LlmResponseSchemaException("Pole wymagane", "scores");
        if (dto.verdict() == null) throw new LlmResponseSchemaException("Pole wymagane", "verdict");
        validateSpine(dto);
    }

    /**
     * The minimal spine: the fields without which a 200 cannot mean anything.
     *
     * <p>The six checks above only assert that the six <em>containers</em> arrived. A provider can
     * answer 200 with every one of them present and every leaf inside them null, and that used to
     * parse — producing an analysis with no car, no verdict and, because {@code ScoresDto} held
     * primitive {@code int}, five scores silently coerced from absent to {@code 0}. A 502 naming
     * the missing field is honest; a 200 carrying nothing is not.
     *
     * <p>Everything outside this list stays nullable on purpose: a free-tier model that omits the
     * VIN, the origin country or a price must still produce a usable analysis.
     *
     * <p>Oracle: the locked output schema — {@code CLAUDE.md} § "Manual entry and user overrides"
     * and {@code context/changes/llm-analysis-wiring/plan.md} § "Locked output schema". Not this
     * class, and not what the current implementation happens to accept.
     */
    private void validateSpine(ResponseDto dto) {
        ScoresDto s = dto.scores();
        requirePresent(s.completeness(), "scores.completeness");
        requirePresent(s.equipment(), "scores.equipment");
        requirePresent(s.risk(), "scores.risk");
        requirePresent(s.value(), "scores.value");
        requirePresent(s.overall(), "scores.overall");
        requirePresent(dto.verdict().code(), "verdict.code");
        requirePresent(dto.extracted().make(), "extracted.make");
        requirePresent(dto.extracted().model(), "extracted.model");
    }

    private void requirePresent(Object value, String field) {
        if (value == null) {
            throw new LlmResponseSchemaException("Pole wymagane", field);
        }
    }

    private void validateScores(ScoresDto s) {
        checkScore(s.completeness(), "scores.completeness");
        checkScore(s.equipment(), "scores.equipment");
        checkScore(s.risk(), "scores.risk");
        checkScore(s.value(), "scores.value");
        checkScore(s.overall(), "scores.overall");
    }

    private void checkScore(int value, String field) {
        if (value < 0 || value > 100) {
            throw new LlmResponseSchemaException("Wartość poza zakresem 0–100: " + value, field);
        }
    }

    private ExtractedData mapExtracted(ExtractedDto d) {
        return new ExtractedData(
                d.make(), d.model(), d.year(),
                d.priceAmount() != null ? BigDecimal.valueOf(d.priceAmount()) : null,
                d.priceCurrency(), d.mileageKm(), d.fuel(), d.transmission(),
                d.originCountry(), d.sellerType(),
                d.serviceHistoryMentioned(), d.accidentClaim(), d.vinPresent(),
                d.vin(), d.registrationPlate(), d.firstRegistrationDate()
        );
    }

    private List<EquipmentItem> mapEquipment(List<EquipmentItemDto> list) {
        return list.stream().map(e -> {
            EquipmentStatus status;
            try {
                status = EquipmentStatus.valueOf(e.status());
            // NullPointerException too: Enum.valueOf(null) throws NPE, not IllegalArgumentException,
            // so a model that emitted "status": null escaped this catch entirely and surfaced as a
            // generic 500 "Błąd serwera" instead of the honest schema 502.
            } catch (IllegalArgumentException | NullPointerException ex) {
                throw new LlmResponseSchemaException("Nieprawidłowy status wyposażenia: " + e.status(), "equipment[].status");
            }
            return new EquipmentItem(e.name(), status, e.note());
        }).toList();
    }

    private List<RiskFlag> mapRiskFlags(List<RiskFlagDto> list) {
        return list.stream().map(f -> {
            RiskSeverity severity;
            try {
                severity = RiskSeverity.valueOf(f.severity());
            } catch (IllegalArgumentException | NullPointerException ex) {
                throw new LlmResponseSchemaException("Nieprawidłowa wartość severity: " + f.severity(), "riskFlags[].severity");
            }
            return new RiskFlag(f.code(), severity, f.description());
        }).toList();
    }

    private CategoryScores mapScores(ScoresDto s) {
        return new CategoryScores(s.completeness(), s.equipment(), s.risk(), s.value(), s.overall());
    }

    private Verdict mapVerdict(VerdictDto v) {
        VerdictCode code;
        try {
            code = VerdictCode.valueOf(v.code());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new LlmResponseSchemaException("Nieprawidłowy kod verdyktu: " + v.code(), "verdict.code");
        }
        return new Verdict(code, v.label());
    }

    // --- private DTOs for deserialization ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResponseDto(
            ExtractedDto extracted,
            List<EquipmentItemDto> equipment,
            List<RiskFlagDto> riskFlags,
            List<String> sellerQuestions,
            ScoresDto scores,
            VerdictDto verdict
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ExtractedDto(
            String make, String model, Integer year,
            Double priceAmount, String priceCurrency,
            Integer mileageKm, String fuel, String transmission,
            String originCountry, String sellerType,
            Boolean serviceHistoryMentioned, String accidentClaim, Boolean vinPresent,
            String vin, String registrationPlate, String firstRegistrationDate
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EquipmentItemDto(String name, String status, String note) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RiskFlagDto(String code, String severity, String description) {}

    /**
     * Boxed on purpose. With primitive {@code int} an absent score deserialises to {@code 0}, so a
     * model that simply forgot {@code risk} produced a confident-looking number nobody generated.
     * {@code Integer} makes absence representable, and {@link #validateSpine} rejects it.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ScoresDto(Integer completeness, Integer equipment, Integer risk, Integer value,
                     Integer overall) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VerdictDto(String code, String label) {}
}
