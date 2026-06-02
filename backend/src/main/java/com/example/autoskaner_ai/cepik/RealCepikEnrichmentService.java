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

@Service
@Profile("!mock")
public class RealCepikEnrichmentService implements CepikEnrichmentService {

    private static final String LOOKUP_URL = "https://historiapojazdu.gov.pl";

    private final CepikApiService cepikApiService;
    private final HistoriaPojazduService historiaPojazduService;

    public RealCepikEnrichmentService(CepikApiService cepikApiService,
                                      HistoriaPojazduService historiaPojazduService) {
        this.cepikApiService = cepikApiService;
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
        if (plate == null || plate.isBlank()) {
            return missingInputs(vin);
        }

        String firstRegDate = extracted.firstRegistrationDate();
        if (firstRegDate == null || firstRegDate.isBlank()) {
            firstRegDate = cepikApiService.lookupFirstRegistrationDate(vin).orElse(null);
            if (firstRegDate == null) {
                return missingInputs(vin);
            }
        }

        return historiaPojazduService.lookup(plate, vin, firstRegDate);
    }

    private CepikResult missingInputs(String vin) {
        return new CepikResult(
                CepikStatus.MISSING_INPUTS, vin, null, null, null,
                null, List.of(), List.of(), LOOKUP_URL, Instant.now()
        );
    }
}
