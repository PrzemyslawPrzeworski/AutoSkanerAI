package com.example.autoskaner_ai.cepik;

import com.example.autoskaner_ai.analysis.AiAnalysisService;
import com.example.autoskaner_ai.analysis.AnalysisController;
import com.example.autoskaner_ai.analysis.AnalysisMeta;
import com.example.autoskaner_ai.analysis.AnalysisResult;
import com.example.autoskaner_ai.analysis.CategoryScores;
import com.example.autoskaner_ai.analysis.CepikRiskAdjuster;
import com.example.autoskaner_ai.analysis.ExtractedData;
import com.example.autoskaner_ai.analysis.ListingFetchService;
import com.example.autoskaner_ai.analysis.RiskFlag;
import com.example.autoskaner_ai.analysis.RiskSeverity;
import com.example.autoskaner_ai.analysis.Verdict;
import com.example.autoskaner_ai.analysis.VerdictCode;
import com.example.autoskaner_ai.common.GlobalExceptionHandler;
import com.example.autoskaner_ai.market.MarketPriceEnrichmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.lessThan;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The one test that spans the whole journey: bytes captured from historiapojazdu.gov.pl, through
 * the real HTTP edge, session, parser, enrichment service and risk adjuster, into the JSON body
 * {@code POST /api/analyses} puts on the wire.
 *
 * <p>Nothing else in the repo joins those halves. {@code HistoriaPojazduParserTest} can read a
 * capture but stops at a {@code CepikResult}; {@code AnalysisControllerTest} reaches JSON but
 * starts from a hand-built one. A swapped payload argument, a renamed request key or a rotted API
 * version therefore sits in the gap between them and keeps the whole suite green — which is
 * exactly how production came back {@code risk: 88, verdict: WORTH_CHECKING} on 2026-08-26 for a
 * car carrying a registered szkoda istotna.
 *
 * <p><b>Only the network edge is stubbed.</b> Every collaborator between the socket and the
 * response body is the production class. The three mocks — {@code AiAnalysisService},
 * {@code ListingFetchService}, {@code MarketPriceEnrichmentService} — sit outside the journey
 * under test.
 *
 * <p>Expected values come from four independent oracles and nowhere else, and each assertion below
 * is labelled with the one it rests on: the bytes of the committed captures, the 2026-08-26
 * production incident, hand arithmetic, and the product guardrail <em>absence is not clean</em>.
 */
class CepikDamageReachesTheResponseTest {

    private static final String VIN = "NMTBZ3BE40R000000";
    private static final String PLATE = "WX00000";
    private static final String FIRST_REG_DATE = "2022-04-12";

    private static final String BASE = "https://moj.gov.pl";
    private static final String SESSION_URL =
            BASE + "/uslugi/engine/ng/index?xFormsAppName=HistoriaPojazdu";

    // Deliberately NOT HistoriaPojazduSession.FALLBACK_API_VERSION. If the stub named the
    // fallback's own version, every URL assertion here would pass whether version discovery
    // works or not — and the literal that rotted from 1.0.17 to 1.1.0 broke production silently.
    private static final String DISCOVERED_VERSION = "1.2.3";
    private static final String API_BASE =
            BASE + "/nforms/api/HistoriaPojazdu/" + DISCOVERED_VERSION + "/data";

    private static final String BOOTSTRAP_HTML = """
            <html><head>
            <script src="/nforms/api/HistoriaPojazdu/%s/assets/main.js"></script>
            </head><body></body></html>
            """.formatted(DISCOVERED_VERSION);

    // What the LLM scored before the registry was consulted, and what the incident actually
    // returned: risk 88 with the label "warto sprawdzić" for a car with a registered damage.
    // Held as constants so the post-adjustment assertions can be relative — the point is that
    // the registry moved the score down, not that it landed on any particular integer.
    private static final int LLM_COMPLETENESS = 90;
    private static final int LLM_EQUIPMENT = 80;
    private static final int LLM_RISK = 88;
    private static final int LLM_VALUE = 70;
    // Hand arithmetic: (90 + 80 + 88 + 70) / 4. The scorers define overall as the mean of four.
    private static final int LLM_OVERALL = 82;

    private MockRestServiceServer server;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Mirrors HistoriaPojazduConfig's builder minus its request factory, which
        // MockRestServiceServer replaces. The bind survives both of HistoriaPojazduSession's
        // later builder.build() rebuilds, which is what makes this seam usable at all.
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE)
                .defaultHeader("Accept", "application/json, */*")
                .defaultHeader("Content-Type", "application/json");
        server = MockRestServiceServer.bindTo(builder).build();

        var aiAnalysisService = mock(AiAnalysisService.class);
        when(aiAnalysisService.analyze(anyString())).thenReturn(llmResult());

        var marketPriceEnrichmentService = mock(MarketPriceEnrichmentService.class);
        when(marketPriceEnrichmentService.enrich(any())).thenReturn(null);

        var cepikEnrichmentService = new RealCepikEnrichmentService(
                new HistoriaPojazduService(builder, new HistoriaPojazduParser()));

        mockMvc = MockMvcBuilders
                .standaloneSetup(new AnalysisController(aiAnalysisService,
                        mock(ListingFetchService.class), cepikEnrichmentService,
                        marketPriceEnrichmentService, new CepikRiskAdjuster()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ---------------------------------------------------------------------------------------
    // 1. The journey: capture bytes → damage record in the payload AND in the verdict
    // ---------------------------------------------------------------------------------------

    @Test
    void aRegisteredDamageReachesBothThePayloadAndTheVerdict() throws Exception {
        expectSessionOpen(BOOTSTRAP_HTML);
        expectVehicleData(withSuccess(fixture("vehicle-data-found.json"), MediaType.APPLICATION_JSON));
        expectTimelineData(withSuccess(fixture("timeline-data-found.json"), MediaType.APPLICATION_JSON));
        expectSessionClose();

        mockMvc.perform(analysisRequest())
                .andExpect(status().isOk())

                // Oracle: the bytes of timeline-data-found.json, event "szkoda-istotna".
                .andExpect(jsonPath("$.cepikResult.status").value("FOUND"))
                .andExpect(jsonPath("$.cepikResult.damageRecords[0].date").value("2023-02-07"))
                .andExpect(jsonPath("$.cepikResult.damageRecords[0].insurer").value("PZU"))
                .andExpect(jsonPath("$.cepikResult.damageRecords[0].categories[0]")
                        .value("Uszkodzenie elementów układu nośnego"))

                // Oracle: the bytes of vehicle-data-found.json — and the argument-order guard.
                // Only vehicle-data carries technicalData.basicData.make; swap the two payloads
                // at HistoriaPojazduService.lookup and this is the assertion that notices,
                // because MockRestServiceServer's expectations are ordered.
                .andExpect(jsonPath("$.cepikResult.make").value("TOYOTA"))

                // Oracle: the registry-flags-first rule — the frontend collapses everything past
                // the fourth flag, so these two have to survive that cut.
                .andExpect(jsonPath("$.analysis.riskFlags[0].code").value("CEPIK_SIGNIFICANT_DAMAGE"))
                .andExpect(jsonPath("$.analysis.riskFlags[1].code").value("CEPIK_CONTRADICTS_LISTING"))

                // Oracle: the 2026-08-26 production incident. This exact response came back
                // WORTH_CHECKING with the damage visible in the panel above it.
                .andExpect(jsonPath("$.analysis.verdict.code").value("HIGH_RISK_SKIP"))
                .andExpect(jsonPath("$.analysis.scores.risk").value(lessThan(LLM_RISK)))
                // overall is what the UI leads with, and no test at the API layer asserted it.
                .andExpect(jsonPath("$.analysis.scores.overall").value(lessThan(LLM_OVERALL)));

        server.verify();
    }

    // ---------------------------------------------------------------------------------------
    // 2. The three-way damageRecords wire contract: populated (above) / null / empty
    // ---------------------------------------------------------------------------------------

    @Test
    void anUnreadableRegistryAnswerPutsAnExplicitNullOnTheWire() throws Exception {
        expectSessionOpen(BOOTSTRAP_HTML);
        // "{}" rather than a bodyless 200, on purpose. A truly empty body fails inside RestClient,
        // and HistoriaPojazduService's catch block would then reach LOOKUP_FAILED without the
        // parser's nothing-readable guard ever running — the status assertion would pass while
        // proving nothing. An empty JSON object reaches the parser as an unreadable payload,
        // which is the case the guard exists for.
        expectVehicleData(withSuccess("{}", MediaType.APPLICATION_JSON));
        expectTimelineData(withSuccess("{}", MediaType.APPLICATION_JSON));
        expectSessionClose();

        String body = mockMvc.perform(analysisRequest())
                .andExpect(status().isOk())
                // Oracle: absence is not clean. An empty FOUND would build a "found in the
                // registry" panel with every field blank, which reads as a clean history.
                .andExpect(jsonPath("$.cepikResult.status").value("LOOKUP_FAILED"))
                // A lookup that failed must move nothing, in either direction.
                .andExpect(jsonPath("$.analysis.verdict.code").value("WORTH_CHECKING"))
                .andExpect(jsonPath("$.analysis.scores.risk").value(LLM_RISK))
                .andReturn().getResponse().getContentAsString();

        // Not a jsonPath, deliberately: both doesNotExist() and value(nullValue()) pass for a JSON
        // null, so neither can tell an omitted key from an explicit one. The frontend renders null
        // as "unknown" and [] as "the registry reported nothing" — a global JsonInclude.NON_NULL
        // would collapse every unknown into an absent key and silently turn it into clean, without
        // touching a line of CEPiK code. This raw-string read is what notices.
        assertThat(body)
                .as("damageRecords must be present and null on the wire, not omitted")
                .contains("\"damageRecords\":null");

        server.verify();
    }

    @Test
    void aCleanRegistryTimelineIsAnEmptyListAndMovesNothing() throws Exception {
        expectSessionOpen(BOOTSTRAP_HTML);
        expectVehicleData(withSuccess(fixture("vehicle-data-found.json"), MediaType.APPLICATION_JSON));
        // The capture with its single szkoda-istotna event deleted — see the fixture README.
        expectTimelineData(withSuccess(fixture("timeline-data-clean-derived.json"),
                MediaType.APPLICATION_JSON));
        expectSessionClose();

        mockMvc.perform(analysisRequest())
                .andExpect(status().isOk())
                // Oracle: absence is not clean, read the other way round. A timeline that parsed
                // and holds no damage event is the one case where [] is the truth, and it has to
                // stay distinguishable from the null above all the way out to the wire.
                .andExpect(jsonPath("$.cepikResult.status").value("FOUND"))
                .andExpect(jsonPath("$.cepikResult.damageRecords").isArray())
                .andExpect(jsonPath("$.cepikResult.damageRecords").isEmpty())
                // Nothing adverse was reported, so the LLM's judgement stands untouched.
                .andExpect(jsonPath("$.analysis.verdict.code").value("WORTH_CHECKING"))
                .andExpect(jsonPath("$.analysis.scores.risk").value(LLM_RISK))
                .andExpect(jsonPath("$.analysis.scores.overall").value(LLM_OVERALL));

        server.verify();
    }

    // ---------------------------------------------------------------------------------------
    // 3. NOT_FOUND driven by a real 404 rather than an authored exception message
    // ---------------------------------------------------------------------------------------

    @Test
    void aRegistry404CarriesHipo0002AllTheWayToNotFound() throws Exception {
        expectSessionOpen(BOOTSTRAP_HTML);
        // The wrapping is real here, and that is the whole point: HistoriaPojazduSession catches a
        // 404 Spring raised, and HistoriaPojazduService.indicatesVehicleNotFound has to find
        // HIPO-0002 in the message Spring built. HistoriaPojazduServiceTest writes that message
        // itself, from the same literal the production code matches against, so the coupling was
        // asserted only in the live test.
        expectVehicleData(withStatus(HttpStatus.NOT_FOUND)
                .body(fixture("not-found-hipo-0002.json"))
                .contentType(MediaType.APPLICATION_JSON));
        // No timeline expectation: vehicle-data throws, so the session never reaches it.
        expectSessionClose();

        String body = mockMvc.perform(analysisRequest())
                .andExpect(status().isOk())
                // Oracle: the observed 404 body. NOT_FOUND, not LOOKUP_FAILED — the registry gave
                // a definitive answer, and the UI words the two cases differently.
                .andExpect(jsonPath("$.cepikResult.status").value("NOT_FOUND"))
                .andExpect(jsonPath("$.analysis.verdict.code").value("WORTH_CHECKING"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .as("a vehicle the registry does not know is unknown, not clean")
                .contains("\"damageRecords\":null");

        server.verify();
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures and stub plumbing
    // ---------------------------------------------------------------------------------------

    private static ClassPathResource fixture(String name) {
        var resource = new ClassPathResource("cepik/" + name);
        assertThat(resource.exists()).as("missing fixture %s", name).isTrue();
        return resource;
    }

    /**
     * The LLM's output, before enrichment. Claims {@code bezwypadkowy} so the contradiction rule
     * applies, and carries the VIN, plate and date the registry lookup needs.
     */
    private static AnalysisResult llmResult() {
        var extracted = new ExtractedData("Toyota", "Corolla", 2022, null, null, 26320,
                "hybryda", null, null, null, Boolean.TRUE, "bezwypadkowy", Boolean.TRUE,
                VIN, PLATE, FIRST_REG_DATE);
        var flags = List.of(
                new RiskFlag("NO_SERVICE_HISTORY", RiskSeverity.MEDIUM, "Brak historii serwisowej"));
        var scores = new CategoryScores(LLM_COMPLETENESS, LLM_EQUIPMENT, LLM_RISK, LLM_VALUE,
                LLM_OVERALL);
        return new AnalysisResult(extracted, List.of(), flags, List.of(), scores,
                new Verdict(VerdictCode.WORTH_CHECKING, "warto sprawdzić"),
                new AnalysisMeta("mock", "mock-v1", 1L, Instant.now()));
    }

    private static MockHttpServletRequestBuilder analysisRequest() {
        return post("/api/analyses")
                .contentType("application/json")
                .content("{\"listingText\":\"Toyota Corolla 2022, hybryda, bezwypadkowy\"}");
    }

    /** The two calls {@code HistoriaPojazduSession.open()} makes, in order. */
    private void expectSessionOpen(String bootstrapHtml) {
        server.expect(requestTo(SESSION_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess()
                        .header(HttpHeaders.SET_COOKIE, "JSESSIONID=stub-session; Path=/"));

        server.expect(requestTo(SESSION_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(bootstrapHtml, MediaType.TEXT_HTML)
                        .header(HttpHeaders.SET_COOKIE, "XSRF-TOKEN=stub-xsrf-token; Path=/"));
    }

    private void expectVehicleData(ResponseCreator response) {
        expectDataCall("/vehicle-data", response);
    }

    private void expectTimelineData(ResponseCreator response) {
        expectDataCall("/timeline-data", response);
    }

    private void expectDataCall(String path, ResponseCreator response) {
        server.expect(requestTo(API_BASE + path))
                .andExpect(method(HttpMethod.POST))
                // The token was never handed to the session directly — it came out of the
                // Set-Cookie above, so this asserts the whole cookie-to-header hop.
                .andExpect(header("X-Xsrf-Token", "stub-xsrf-token"))
                // The registry's own key names. A rename here is a silent LOOKUP_FAILED in prod.
                .andExpect(MockRestRequestMatchers.jsonPath("$.registrationNumber").value(PLATE))
                .andExpect(MockRestRequestMatchers.jsonPath("$.VINNumber").value(VIN))
                .andExpect(MockRestRequestMatchers.jsonPath("$.firstRegistrationDate")
                        .value(FIRST_REG_DATE))
                .andRespond(response);
    }

    /** {@code lookup} closes the session in a finally block, so the stub has to expect it. */
    private void expectSessionClose() {
        server.expect(requestTo(API_BASE + "/close"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess());
    }
}
