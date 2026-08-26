package com.example.autoskaner_ai.cepik;

import com.example.autoskaner_ai.analysis.CepikResult;
import com.example.autoskaner_ai.analysis.CepikStatus;
import com.example.autoskaner_ai.analysis.ExtractedData;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("mock")
public class MockCepikService implements CepikEnrichmentService {

    // The empty lists this used to return were the same "no damage reported" lie as the parser
    // bug, just under the mock profile — a LOOKUP_FAILED knows nothing about damage.
    @Override
    public CepikResult enrich(ExtractedData extracted) {
        return CepikResult.withoutData(
                CepikStatus.LOOKUP_FAILED, extracted.vin(), "https://historiapojazdu.gov.pl");
    }
}
