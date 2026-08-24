package com.example.autoskaner_ai.cepik;

import com.example.autoskaner_ai.analysis.CepikResult;
import com.example.autoskaner_ai.analysis.ExtractedData;

public interface CepikEnrichmentService {
    CepikResult enrich(ExtractedData extracted);
}
