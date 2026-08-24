package com.example.autoskaner_ai.cepik;

import com.example.autoskaner_ai.analysis.CepikResult;
import com.example.autoskaner_ai.analysis.CepikStatus;
import com.example.autoskaner_ai.analysis.ExtractedData;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@Profile("mock")
public class MockCepikService implements CepikEnrichmentService {

    @Override
    public CepikResult enrich(ExtractedData extracted) {
        return new CepikResult(
                CepikStatus.LOOKUP_FAILED, extracted.vin(), null, null, null,
                null, List.of(), List.of(), "https://historiapojazdu.gov.pl", Instant.now()
        );
    }
}
