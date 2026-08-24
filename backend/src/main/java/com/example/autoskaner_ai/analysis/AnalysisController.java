package com.example.autoskaner_ai.analysis;

import com.example.autoskaner_ai.cepik.CepikEnrichmentService;
import com.example.autoskaner_ai.market.MarketPriceEnrichmentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/analyses")
public class AnalysisController {

    private final AiAnalysisService aiAnalysisService;
    private final ListingFetchService listingFetchService;
    private final CepikEnrichmentService cepikEnrichmentService;
    private final MarketPriceEnrichmentService marketPriceEnrichmentService;

    public AnalysisController(AiAnalysisService aiAnalysisService,
                              ListingFetchService listingFetchService,
                              CepikEnrichmentService cepikEnrichmentService,
                              MarketPriceEnrichmentService marketPriceEnrichmentService) {
        this.aiAnalysisService = aiAnalysisService;
        this.listingFetchService = listingFetchService;
        this.cepikEnrichmentService = cepikEnrichmentService;
        this.marketPriceEnrichmentService = marketPriceEnrichmentService;
    }

    @PostMapping
    public ResponseEntity<AnalysisResponse> analyze(@Valid @RequestBody AnalysisRequest request) {
        if (request.url() != null && !request.url().isBlank()) {
            FetchResult fetch = listingFetchService.fetch(request.url());
            if (fetch.isOk()) {
                AnalysisResult result = aiAnalysisService.analyze(fetch.text());
                return ResponseEntity.ok(buildResponse(result, "ok"));
            } else {
                return ResponseEntity.ok(AnalysisResponse.urlFailed(fetch.reason()));
            }
        }

        AnalysisResult result = aiAnalysisService.analyze(request.listingText());
        return ResponseEntity.ok(buildResponse(result, "text"));
    }

    private AnalysisResponse buildResponse(AnalysisResult result, String fetchStatus) {
        var cepikResult = cepikEnrichmentService.enrich(result.extracted());
        var marketPriceContext = marketPriceEnrichmentService.enrich(result.extracted());

        List<String> augmentedQuestions = new ArrayList<>(result.sellerQuestions());
        var vin = result.extracted().vin();
        var plate = result.extracted().registrationPlate();
        var date = result.extracted().firstRegistrationDate();

        if (vin == null || VinValidator.normalise(vin).isEmpty()) {
            augmentedQuestions.add("Proszę podać numer VIN pojazdu");
        }
        if (plate == null || plate.isBlank()) {
            augmentedQuestions.add("Proszę podać numer rejestracyjny pojazdu");
        }
        if (date == null || date.isBlank()) {
            augmentedQuestions.add("Proszę podać datę pierwszej rejestracji pojazdu");
        }

        AnalysisResult augmented = new AnalysisResult(
                result.extracted(), result.equipment(), result.riskFlags(),
                augmentedQuestions, result.scores(), result.verdict(), result.meta()
        );

        return new AnalysisResponse(fetchStatus, null, augmented, cepikResult, marketPriceContext);
    }
}
