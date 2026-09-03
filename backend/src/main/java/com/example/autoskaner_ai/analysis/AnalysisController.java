package com.example.autoskaner_ai.analysis;

import com.example.autoskaner_ai.cepik.CepikEnrichmentService;
import com.example.autoskaner_ai.market.MarketPriceEnrichmentService;
import com.example.autoskaner_ai.market.MarketPriceStatus;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/analyses")
public class AnalysisController {

    private static final Logger log = LoggerFactory.getLogger(AnalysisController.class);

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

        // Effectively final for the lambdas below; `result` is reassigned twice in this method.
        ExtractedData extracted = result.extracted();

        var cepikResult = degradeOnThrow("cepik",
                () -> cepikEnrichmentService.enrich(extracted),
                // The lookup URL travels even on the degraded path: this is exactly the case where
                // the UI tells the user to check the registry by hand, so the link has to work.
                () -> CepikResult.withoutData(CepikStatus.LOOKUP_FAILED, extracted.vin(),
                        CepikResult.LOOKUP_URL));
        var marketPriceContext = degradeOnThrow("market-price",
                () -> marketPriceEnrichmentService.enrich(extracted),
                () -> new MarketPriceContext(MarketPriceStatus.FETCH_FAILED,
                        null, null, null, null, null, Instant.now(), null, null));

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

    /**
     * Runs one enrichment, degrading it to its own failure status if it throws.
     *
     * <p>Enrichment is the last ~11 s of a ~27 s request, and it runs <em>after</em> the analysis is
     * already in hand. An uncaught throw here therefore discards a finished analysis and answers 500
     * — the user waited out the whole LLM call to be told the server broke. Both enrichments are
     * best-effort by design and both already have a vocabulary for "this did not work"
     * ({@code LOOKUP_FAILED}, {@code FETCH_FAILED}), so a throw is just another way of arriving at
     * the same answer.
     *
     * <p>{@code MarketPriceFetchService} handles its own {@code RestClientException}, but two calls
     * on that path are unguarded and neither is a network fault: {@code slugMapper.makeSlug} and
     * {@code MarketPriceStatistics.of}. This also honours the invariant S-05 stated and never
     * enforced — the endpoint always returns a {@code marketPriceContext}, never absent and never an
     * uncaught exception.
     *
     * <p>Scoped to the enrichment calls on purpose. An LLM failure must still reach the client as
     * the 502 that names its cause, never as a 200 carrying an empty analysis — which is why
     * {@code aiAnalysisService.analyze} is called outside this guard, in {@link #analyze}.
     *
     * <p>The degraded value is built by a supplier rather than passed in, so the {@code Instant.now()}
     * on it is the moment the failure was handled and the happy path does not pay for it.
     */
    private static <T> T degradeOnThrow(String stage, Supplier<T> enrichment, Supplier<T> degraded) {
        try {
            return enrichment.get();
        } catch (RuntimeException e) {
            // Stage, exception type and message, because the throw is by definition one nobody
            // predicted: whoever reads this line in production has only what it says.
            // ASCII only: an em dash here survives neither the Windows console codepage nor every
            // log shipper, and a mojibake character in the middle of the line is worse than a hyphen.
            log.warn("Enrichment degraded, analysis returned without it: stage={} cause={} message={}",
                    stage, e.getClass().getName(), e.getMessage(), e);
            return degraded.get();
        }
    }

    private static AnalysisResult withExtracted(AnalysisResult result, ExtractedData extracted) {
        if (extracted == result.extracted()) {
            return result;
        }
        return new AnalysisResult(extracted, result.equipment(), result.riskFlags(),
                result.sellerQuestions(), result.scores(), result.verdict(), result.meta());
    }
}
