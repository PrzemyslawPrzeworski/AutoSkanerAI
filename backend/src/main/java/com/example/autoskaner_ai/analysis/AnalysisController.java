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
    private final CepikRiskAdjuster cepikRiskAdjuster;

    public AnalysisController(AiAnalysisService aiAnalysisService,
                              ListingFetchService listingFetchService,
                              CepikEnrichmentService cepikEnrichmentService,
                              MarketPriceEnrichmentService marketPriceEnrichmentService,
                              CepikRiskAdjuster cepikRiskAdjuster) {
        this.aiAnalysisService = aiAnalysisService;
        this.listingFetchService = listingFetchService;
        this.cepikEnrichmentService = cepikEnrichmentService;
        this.marketPriceEnrichmentService = marketPriceEnrichmentService;
        this.cepikRiskAdjuster = cepikRiskAdjuster;
    }

    @PostMapping
    public ResponseEntity<AnalysisResponse> analyze(@Valid @RequestBody AnalysisRequest request) {
        if (request.url() != null && !request.url().isBlank()) {
            FetchResult fetch = listingFetchService.fetch(request.url());
            if (fetch.isOk()) {
                // Manual fields ride along with a URL: the user pastes the link and types the VIN
                // the advert does not publish, so both reach the same analysis.
                String text = request.hasManualEntry()
                        ? ManualListingComposer.compose(request.manual(), fetch.text())
                        : fetch.text();
                AnalysisResult result = aiAnalysisService.analyze(text);
                return ResponseEntity.ok(buildResponse(result, "ok", request));
            } else {
                return ResponseEntity.ok(AnalysisResponse.urlFailed(fetch.reason()));
            }
        }

        if (request.hasManualEntry()) {
            String text = ManualListingComposer.compose(request.manual(), request.listingText());
            AnalysisResult result = aiAnalysisService.analyze(text);
            // "manual" even when free text came along with the form: the fields are what the user
            // vouched for, and the frontend words the source of the analysis from this value.
            return ResponseEntity.ok(buildResponse(result, "manual", request));
        }

        AnalysisResult result = aiAnalysisService.analyze(request.listingText());
        return ResponseEntity.ok(buildResponse(result, "text", request));
    }

    private AnalysisResponse buildResponse(AnalysisResult result, String fetchStatus,
                                           AnalysisRequest request) {
        // Before enrichment, so the registry lookup and the market-price query both use the
        // values the user vouched for rather than the model's reading of the advert.
        result = withExtracted(result, UserOverrides.apply(result.extracted(), request));

        var cepikResult = cepikEnrichmentService.enrich(result.extracted());
        var marketPriceContext = marketPriceEnrichmentService.enrich(result.extracted());

        // The LLM scored the listing before the registry was queried, so it never saw these
        // findings. Fold them in before anything else reads scores or verdict.
        result = cepikRiskAdjuster.apply(result, cepikResult);

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

    private static AnalysisResult withExtracted(AnalysisResult result, ExtractedData extracted) {
        if (extracted == result.extracted()) {
            return result;
        }
        return new AnalysisResult(extracted, result.equipment(), result.riskFlags(),
                result.sellerQuestions(), result.scores(), result.verdict(), result.meta());
    }
}
