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
            } catch (IllegalArgumentException ex) {
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
            } catch (IllegalArgumentException ex) {
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
        } catch (IllegalArgumentException ex) {
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ScoresDto(int completeness, int equipment, int risk, int value, int overall) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VerdictDto(String code, String label) {}
}
