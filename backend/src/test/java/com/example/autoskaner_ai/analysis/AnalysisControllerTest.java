package com.example.autoskaner_ai.analysis;

import com.example.autoskaner_ai.analysis.CepikResult;
import com.example.autoskaner_ai.analysis.CepikStatus;
import com.example.autoskaner_ai.cepik.CepikEnrichmentService;
import com.example.autoskaner_ai.common.GlobalExceptionHandler;
import com.example.autoskaner_ai.market.MarketPriceEnrichmentService;
import com.example.autoskaner_ai.market.MarketPriceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("mock")
class AnalysisControllerTest {

    private MockMvc mockMvc;
    private AiAnalysisService aiAnalysisService;
    private ListingFetchService listingFetchService;
    private CepikEnrichmentService cepikEnrichmentService;
    private MarketPriceEnrichmentService marketPriceEnrichmentService;

    @BeforeEach
    void setUp() {
        aiAnalysisService = mock(AiAnalysisService.class);
        listingFetchService = mock(ListingFetchService.class);
        cepikEnrichmentService = mock(CepikEnrichmentService.class);
        when(cepikEnrichmentService.enrich(any())).thenReturn(null);
        marketPriceEnrichmentService = mock(MarketPriceEnrichmentService.class);
        when(marketPriceEnrichmentService.enrich(any())).thenReturn(
                new MarketPriceContext(MarketPriceStatus.OK, 45_000, 55_000, 70_000, 12,
                        "https://www.otomoto.pl/osobowe/toyota/corolla", Instant.now()));
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AnalysisController(aiAnalysisService, listingFetchService,
                        cepikEnrichmentService, marketPriceEnrichmentService,
                        new CepikRiskAdjuster()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private AnalysisResult fullResult() {
        var extracted = new ExtractedData("BMW", "3 Series", 2018, null, null, 120000,
                "benzyna", null, null, null, Boolean.TRUE, "bezwypadkowy", Boolean.TRUE,
                "WBAAM31060GE12345", null, "2018-03-15");
        var equipment = List.of(
                new EquipmentItem("klimatyzacja", EquipmentStatus.CONFIRMED, null),
                new EquipmentItem("tempomat", EquipmentStatus.UNCLEAR, "Nie wspomniano w ogłoszeniu")
        );
        var flags = List.of(
                new RiskFlag("NO_SERVICE_HISTORY", RiskSeverity.MEDIUM, "Brak historii serwisowej")
        );
        var questions = List.of("Czy pojazd był w wypadku?", "Jaki jest powód sprzedaży?");
        var scores = new CategoryScores(83, 50, 75, 60, 67);
        var verdict = new Verdict(VerdictCode.WORTH_CHECKING, "warto sprawdzić");
        var meta = new AnalysisMeta("mock", "mock-v1", 5L, Instant.now());
        return new AnalysisResult(extracted, equipment, flags, questions, scores, verdict, meta);
    }

    @Test
    void returnsFullAnalysisResult_whenValidInput() throws Exception {
        when(aiAnalysisService.analyze(anyString())).thenReturn(fullResult());

        mockMvc.perform(post("/api/analyses")
                        .contentType("application/json")
                        .content("{\"listingText\":\"BMW 3 Series 2018, bezwypadkowy, VIN dostępny\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fetchStatus").value("text"))
                .andExpect(jsonPath("$.analysis.extracted").exists())
                .andExpect(jsonPath("$.analysis.equipment").isArray())
                .andExpect(jsonPath("$.analysis.riskFlags").isArray())
                .andExpect(jsonPath("$.analysis.sellerQuestions").isArray())
                .andExpect(jsonPath("$.analysis.scores").exists())
                .andExpect(jsonPath("$.analysis.verdict").exists())
                .andExpect(jsonPath("$.analysis.meta").exists())
                .andExpect(jsonPath("$.analysis.meta.provider").value("mock"))
                .andExpect(jsonPath("$.analysis.verdict.code").value("WORTH_CHECKING"))
                .andExpect(jsonPath("$.marketPriceContext.status").value("OK"));
    }

    @Test
    void returnsUrlFailed_whenFetchFails() throws Exception {
        when(listingFetchService.fetch(anyString())).thenReturn(FetchResult.failed("blocked"));

        mockMvc.perform(post("/api/analyses")
                        .contentType("application/json")
                        .content("{\"url\":\"https://otomoto.pl/listing/123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fetchStatus").value("url_failed"))
                .andExpect(jsonPath("$.fetchFailureReason").value("blocked"))
                .andExpect(jsonPath("$.analysis").doesNotExist());
    }

    @Test
    void returnsOk_whenUrlFetchSucceeds() throws Exception {
        when(listingFetchService.fetch(anyString())).thenReturn(FetchResult.ok("BMW text from listing"));
        when(aiAnalysisService.analyze(anyString())).thenReturn(fullResult());

        mockMvc.perform(post("/api/analyses")
                        .contentType("application/json")
                        .content("{\"url\":\"https://otomoto.pl/listing/123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fetchStatus").value("ok"))
                .andExpect(jsonPath("$.analysis.verdict.code").value("WORTH_CHECKING"))
                .andExpect(jsonPath("$.marketPriceContext.status").value("OK"));
    }

    @Test
    void returns400_whenNeitherFieldProvided() throws Exception {
        mockMvc.perform(post("/api/analyses")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Błąd walidacji"))
                .andExpect(jsonPath("$.messages[0]").value("Wymagane jest podanie url, listingText lub danych pojazdu"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void returns400_whenListingTextIsBlank() throws Exception {
        mockMvc.perform(post("/api/analyses")
                        .contentType("application/json")
                        .content("{\"listingText\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Błąd walidacji"))
                .andExpect(jsonPath("$.messages[0]").value("Wymagane jest podanie url, listingText lub danych pojazdu"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void returns400_whenBodyIsNotJson() throws Exception {
        mockMvc.perform(post("/api/analyses")
                        .contentType("application/json")
                        .content("not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void mockProfile_cepikResultIsLookupFailed() throws Exception {
        var cepikResult = CepikResult.withoutData(
                CepikStatus.LOOKUP_FAILED, null, "https://historiapojazdu.gov.pl");
        when(cepikEnrichmentService.enrich(any())).thenReturn(cepikResult);
        when(aiAnalysisService.analyze(anyString())).thenReturn(fullResult());

        mockMvc.perform(post("/api/analyses")
                        .contentType("application/json")
                        .content("{\"listingText\":\"BMW 3 Series 2018\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cepikResult.status").value("LOOKUP_FAILED"));
    }

    // The unit tests cover the caps; this one covers the wiring, which is where the original bug
    // lived — the adjustment existing but never being applied is indistinguishable from no fix.
    @Test
    void foundCepikDamage_downgradesTheVerdictInTheResponse() throws Exception {
        var damage = new DamageRecord("2023-02-07", "Powstanie szkody istotnej", "PZU",
                List.of("Uszkodzenie elementów układu nośnego"));
        var cepikResult = new CepikResult(CepikStatus.FOUND, "WBAAM31060GE12345", "2018-03-15",
                null, null, 2, List.of(), List.of(damage), "https://historiapojazdu.gov.pl",
                Instant.now(), "BMW", "BMW 320I", "SAMOCHÓD OSOBOWY", 2018,
                "Zarejestrowany", "aktualne", Boolean.TRUE, Boolean.FALSE, Boolean.FALSE,
                "mazowieckie", List.of());
        when(cepikEnrichmentService.enrich(any())).thenReturn(cepikResult);
        when(aiAnalysisService.analyze(anyString())).thenReturn(fullResult());

        mockMvc.perform(post("/api/analyses")
                        .contentType("application/json")
                        .content("{\"listingText\":\"BMW 3 Series 2018, bezwypadkowy\"}"))
                .andExpect(status().isOk())
                // fullResult() claims "bezwypadkowy", so the contradiction rule applies too.
                .andExpect(jsonPath("$.analysis.verdict.code").value("HIGH_RISK_SKIP"))
                .andExpect(jsonPath("$.analysis.scores.risk").value(25))
                .andExpect(jsonPath("$.analysis.riskFlags[0].code").value("CEPIK_SIGNIFICANT_DAMAGE"))
                .andExpect(jsonPath("$.analysis.riskFlags[1].code").value("CEPIK_CONTRADICTS_LISTING"));
    }

    // The reason this slice exists: Otomoto hides the VIN from logged-out fetches, so the VIN the
    // user types has to reach the registry lookup. The mock LLM returns no VIN here.
    @Test
    void aTypedVinReachesTheCepikLookup() throws Exception {
        var extracted = new ExtractedData("Toyota", "Corolla", 2022, null, null, 26320,
                "hybryda", null, null, null, Boolean.TRUE, null, Boolean.FALSE,
                null, null, null);
        when(aiAnalysisService.analyze(anyString())).thenReturn(new AnalysisResult(extracted,
                List.of(), List.of(), List.of(), new CategoryScores(80, 80, 80, 60, 75),
                new Verdict(VerdictCode.WORTH_CHECKING, "warto sprawdzić"),
                new AnalysisMeta("mock", "mock-v1", 1L, Instant.now())));

        mockMvc.perform(post("/api/analyses")
                        .contentType("application/json")
                        .content("""
                                {"listingText":"Toyota Corolla 2022",
                                 "vin":"NMTBZ3BE40R000000",
                                 "registrationPlate":"WX00000",
                                 "firstRegistrationDate":"2022-04-12"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysis.extracted.vin").value("NMTBZ3BE40R000000"))
                .andExpect(jsonPath("$.analysis.extracted.vinPresent").value(true))
                .andExpect(jsonPath("$.analysis.extracted.registrationPlate").value("WX00000"))
                .andExpect(jsonPath("$.analysis.extracted.firstRegistrationDate").value("2022-04-12"))
                // All three present, so none of the "proszę podać..." questions may be appended.
                .andExpect(jsonPath("$.analysis.sellerQuestions").isEmpty());

        var forLookup = ArgumentCaptor.forClass(ExtractedData.class);
        verify(cepikEnrichmentService).enrich(forLookup.capture());
        assertThat(forLookup.getValue().vin()).isEqualTo("NMTBZ3BE40R000000");
        assertThat(forLookup.getValue().registrationPlate()).isEqualTo("WX00000");
        assertThat(forLookup.getValue().firstRegistrationDate()).isEqualTo("2022-04-12");
    }

    @Test
    void manualFieldsAloneAreEnoughToAnalyse() throws Exception {
        when(aiAnalysisService.analyze(anyString())).thenReturn(fullResult());

        mockMvc.perform(post("/api/analyses")
                        .contentType("application/json")
                        .content("""
                                {"manual":{"make":"Toyota","model":"Corolla","year":2022,
                                           "mileageKm":26320,"priceAmount":82900}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fetchStatus").value("manual"))
                .andExpect(jsonPath("$.analysis.verdict.code").value("WORTH_CHECKING"))
                // The manual values win over what the mock LLM read back out of the composed text.
                .andExpect(jsonPath("$.analysis.extracted.make").value("Toyota"))
                .andExpect(jsonPath("$.analysis.extracted.year").value(2022));

        var prompt = ArgumentCaptor.forClass(String.class);
        verify(aiAnalysisService).analyze(prompt.capture());
        assertThat(prompt.getValue())
                .contains("Marka: Toyota")
                .contains("Przebieg: 26320 km")
                .contains("Cena: 82900 PLN");
    }

    @Test
    void returns400_whenManualEntryIsAllBlank() throws Exception {
        mockMvc.perform(post("/api/analyses")
                        .contentType("application/json")
                        .content("{\"manual\":{\"make\":\"  \"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]").value("Wymagane jest podanie url, listingText lub danych pojazdu"));
    }

    @Test
    void returns400_whenManualYearIsImpossible() throws Exception {
        mockMvc.perform(post("/api/analyses")
                        .contentType("application/json")
                        .content("{\"manual\":{\"make\":\"Toyota\",\"year\":1823}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]").value("manual.year: rok poza zakresem"));
    }

    // A mistyped VIN must not 400 away an analysis that is still useful; the registry reports
    // MISSING_INPUTS and the seller question asks for it again.
    @Test
    void aMalformedVinIsNotAValidationError() throws Exception {
        when(aiAnalysisService.analyze(anyString())).thenReturn(fullResult());

        mockMvc.perform(post("/api/analyses")
                        .contentType("application/json")
                        .content("{\"listingText\":\"Toyota Corolla\",\"vin\":\"NMTB-TOO-SHORT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysis").exists());
    }

    @Test
    void manualFieldsRideAlongWithASuccessfulUrlFetch() throws Exception {
        when(listingFetchService.fetch(anyString())).thenReturn(FetchResult.ok("Treść z Otomoto"));
        when(aiAnalysisService.analyze(anyString())).thenReturn(fullResult());

        mockMvc.perform(post("/api/analyses")
                        .contentType("application/json")
                        .content("""
                                {"url":"https://otomoto.pl/listing/123",
                                 "manual":{"notes":"Widziałem rysę na drzwiach"}}"""))
                .andExpect(status().isOk())
                // Still "ok": the advert was fetched, the form only added to it.
                .andExpect(jsonPath("$.fetchStatus").value("ok"));

        var prompt = ArgumentCaptor.forClass(String.class);
        verify(aiAnalysisService).analyze(prompt.capture());
        assertThat(prompt.getValue())
                .contains("Widziałem rysę na drzwiach")
                .contains("Treść z Otomoto");
    }

    @Test
    void missingPlate_injectsPlateQuestion() throws Exception {
        var extracted = new ExtractedData("BMW", "3 Series", 2018, null, null, 120000,
                "benzyna", null, null, null, Boolean.TRUE, "bezwypadkowy", Boolean.TRUE,
                "WBAAM31060GE12345", null, "2018-03-15");
        var result = new AnalysisResult(extracted, List.of(), List.of(),
                List.of("Istniejące pytanie"), new CategoryScores(80, 80, 80, 60, 75),
                new Verdict(VerdictCode.WORTH_CHECKING, "warto sprawdzić"),
                new AnalysisMeta("mock", "mock-v1", 1L, java.time.Instant.now()));
        when(aiAnalysisService.analyze(anyString())).thenReturn(result);
        when(cepikEnrichmentService.enrich(any())).thenReturn(null);

        mockMvc.perform(post("/api/analyses")
                        .contentType("application/json")
                        .content("{\"listingText\":\"BMW 3 Series 2018, VIN: WBAAM31060GE12345\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysis.sellerQuestions[?(@=='Proszę podać numer rejestracyjny pojazdu')]").exists());
    }
}
