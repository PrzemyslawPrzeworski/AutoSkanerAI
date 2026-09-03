package com.example.autoskaner_ai.cepik;

import com.example.autoskaner_ai.analysis.CepikResult;
import com.example.autoskaner_ai.analysis.CepikStatus;
import com.example.autoskaner_ai.analysis.ExtractedData;
import com.example.autoskaner_ai.analysis.VinValidator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

// historiapojazdu.gov.pl needs all three of plate + VIN + first registration date.
// The date cannot be recovered from the public api.cepik.gov.pl: that API exposes no
// VIN field at all (68 attributes, none of them a VIN — verified 2026-08-25 against
// the live endpoint), requires a mandatory `wojewodztwo` and caps `data-od`/`data-do`
// at a 2-year span. A VIN-keyed date lookup is therefore not expressible against it,
// so when the LLM did not extract the date we report MISSING_INPUTS and let
// AnalysisController ask the user for it.
@Service
@Profile("!mock")
public class RealCepikEnrichmentService implements CepikEnrichmentService {

    private static final Pattern PLATE_PATTERN = Pattern.compile("[A-Z]{2,3}[A-Z0-9]{4,5}");

    // ISO first so a well-formed value is not reinterpreted; the rest are the formats Polish
    // listings actually use. Strict resolution, so 31.02.2016 is rejected rather than shifted.
    // The prose forms are not hypothetical: a live Otomoto listing on 2026-08-26 rendered the
    // date as "12 kwietnia 2022", which failed every numeric pattern and cost the user a CEPiK
    // lookup. MMMM is the genitive Polish dates are actually written in; LLLL is the nominative,
    // for a seller who writes "12 kwiecień 2022".
    private static final List<DateTimeFormatter> ACCEPTED_DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            strict("dd.MM.uuuu"),
            strict("dd-MM-uuuu"),
            strict("dd/MM/uuuu"),
            polishProse("d MMMM uuuu"),
            polishProse("d LLLL uuuu"));

    private static DateTimeFormatter strict(String pattern) {
        return DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT);
    }

    // Case-insensitive because "12 Kwietnia 2022" is just as likely as lower case, and a
    // capital letter is not a reason to tell the user their date is unusable.
    private static DateTimeFormatter polishProse(String pattern) {
        return new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern(pattern)
                .toFormatter(Locale.forLanguageTag("pl"))
                .withResolverStyle(ResolverStyle.STRICT);
    }

    private final HistoriaPojazduService historiaPojazduService;

    public RealCepikEnrichmentService(HistoriaPojazduService historiaPojazduService) {
        this.historiaPojazduService = historiaPojazduService;
    }

    @Override
    public CepikResult enrich(ExtractedData extracted) {
        Optional<String> normalisedVin = VinValidator.normalise(extracted.vin());
        if (normalisedVin.isEmpty()) {
            return missingInputs(null);
        }
        String vin = normalisedVin.get();

        Optional<String> normalisedPlate = normalisePlate(extracted.registrationPlate());
        if (normalisedPlate.isEmpty()) {
            return missingInputs(vin);
        }

        Optional<String> isoDate = toIsoDate(extracted.firstRegistrationDate());
        if (isoDate.isEmpty()) {
            return missingInputs(vin);
        }

        return historiaPojazduService.lookup(normalisedPlate.get(), vin, isoDate.get());
    }

    // Mirrors VinValidator.normalise, and deliberately so: a plate is printed with a space
    // ("WA 12345") and typed the way it is printed, so matching the pattern against the raw value
    // turned the commonest form of a correct plate into MISSING_INPUTS and no lookup at all —
    // while the same user typing a spaced VIN was fine. The registry receives the normalised
    // form; sending the spaced one back would just move the rejection to their validator.
    private static Optional<String> normalisePlate(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalised = raw.trim().toUpperCase().replaceAll("[\\s\\-]", "");
        if (PLATE_PATTERN.matcher(normalised).matches()) {
            return Optional.of(normalised);
        }
        return Optional.empty();
    }

    // The prompt asks the LLM for the date "w formacie z ogłoszenia", and Polish listings
    // write dd.MM.yyyy — but historiapojazdu's nfv_regex validator rejects anything but
    // yyyy-MM-dd with a 400, which surfaces as LOOKUP_FAILED ("temporarily unavailable").
    // Before this normalisation every production lookup failed that way; it only ever
    // worked in tests that hardcoded an ISO date. Returning empty for an unparseable value
    // is deliberate: MISSING_INPUTS asks the user for the date, whereas letting a doomed
    // request through would blame the registry for a value we could see was wrong.
    private static Optional<String> toIsoDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String value = raw.strip();
        for (DateTimeFormatter format : ACCEPTED_DATE_FORMATS) {
            try {
                return Optional.of(LocalDate.parse(value, format).format(DateTimeFormatter.ISO_LOCAL_DATE));
            } catch (DateTimeParseException ignored) {
                // try the next pattern
            }
        }
        return Optional.empty();
    }

    // mileageStamps and damageRecords stay null rather than empty: an empty list reads as
    // "we looked and there was nothing", which for damage records is exactly the
    // "unknown is not clean" confusion the product must never present.
    private CepikResult missingInputs(String vin) {
        return CepikResult.withoutData(CepikStatus.MISSING_INPUTS, vin, CepikResult.LOOKUP_URL);
    }
}
