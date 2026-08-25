package com.example.autoskaner_ai.cepik;

import com.example.autoskaner_ai.analysis.CepikResult;
import com.example.autoskaner_ai.analysis.CepikStatus;
import com.example.autoskaner_ai.analysis.ExtractedData;
import com.example.autoskaner_ai.analysis.VinValidator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
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

    private static final String LOOKUP_URL = "https://historiapojazdu.gov.pl";
    private static final Pattern PLATE_PATTERN = Pattern.compile("[A-Z]{2,3}[A-Z0-9]{4,5}");

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

        String plate = extracted.registrationPlate();
        if (plate == null || plate.isBlank() || !PLATE_PATTERN.matcher(plate.strip().toUpperCase()).matches()) {
            return missingInputs(vin);
        }
        plate = plate.strip().toUpperCase();

        String firstRegDate = extracted.firstRegistrationDate();
        if (firstRegDate == null || firstRegDate.isBlank()) {
            return missingInputs(vin);
        }

        return historiaPojazduService.lookup(plate, vin, firstRegDate);
    }

    // mileageStamps and damageRecords stay null rather than empty: an empty list reads as
    // "we looked and there was nothing", which for damage records is exactly the
    // "unknown is not clean" confusion the product must never present.
    private CepikResult missingInputs(String vin) {
        return new CepikResult(
                CepikStatus.MISSING_INPUTS, vin, null, null, null,
                null, null, null, LOOKUP_URL, Instant.now()
        );
    }
}
