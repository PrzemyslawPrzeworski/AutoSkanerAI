package com.example.autoskaner_ai.cepik;

import com.example.autoskaner_ai.analysis.AiAnalysisService;
import com.example.autoskaner_ai.analysis.AnalysisController;
import com.example.autoskaner_ai.analysis.AnalysisMeta;
import com.example.autoskaner_ai.analysis.AnalysisResult;
import com.example.autoskaner_ai.analysis.CategoryScores;
import com.example.autoskaner_ai.analysis.CepikResult;
import com.example.autoskaner_ai.analysis.CepikRiskAdjuster;
import com.example.autoskaner_ai.analysis.CepikStatus;
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
    // 4. The two FOUND shapes no capture can drive: a timeline that was never read, and the
    //    mock profile's own canned answer
    // ---------------------------------------------------------------------------------------

    @Test
    void aFoundResultWithNoTimelineReadLeavesTheLlmScoreExactlyAsItWas() throws Exception {
        // Hand-built, and it has to be: neither HistoriaPojazduParser nor MockCepikService can
        // produce FOUND with a null damageRecords — the parser reaches FOUND only by having read a
        // timeline, and the mock always carries one. So no capture and no stubbed socket gets here,
        // and the collaborator is replaced instead of the bytes behind it.
        //
        // It is asserted anyway because it is the defensive case for the rule the whole class exists
        // for. null and [] are different facts: [] is "the registry reported nothing to insurers",
        // null is "we did not read a timeline", and only the second is unknown. A future field
        // rename, a partial payload or a new FOUND branch could produce this shape, and if it moved
        // the score it would move it in the *adverse* direction — reporting a damage the registry
        // never mentioned — while looking like diligence.
        //
        // anUnreadableRegistryAnswerPutsAnExplicitNullOnTheWire covers null damageRecords behind a
        // non-FOUND status, which CepikRiskAdjuster rejects at its first guard. This one is past
        // that guard: status is FOUND, so only the damage-list null check stands between an unread
        // timeline and a capped score.
        var foundWithNoTimelineRead = new CepikResult(
                CepikStatus.FOUND, VIN, FIRST_REG_DATE, null, null, 2,
                null, null, CepikResult.LOOKUP_URL, Instant.now(),
                "TOYOTA", "TOYOTA COROLLA", "SAMOCHÓD OSOBOWY", 2022,
                // ocInsuranceValid true on purpose: a null or false there raises CEPIK_NO_OC_POLICY
                // and caps risk at 70, which would move the score for a reason this test is not
                // about and would make a green run indistinguishable from a leak.
                "Zarejestrowany", "aktualne", true, false, false, "mazowieckie",
                null);
        var cepikEnrichmentService = mock(CepikEnrichmentService.class);
        when(cepikEnrichmentService.enrich(any())).thenReturn(foundWithNoTimelineRead);

        String body = standaloneWith(cepikEnrichmentService).perform(analysisRequest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cepikResult.status").value("FOUND"))

                // Oracle: absence is not clean, and its converse — absence is not damage either.
                // Every one of the five scores is the LLM's own, to the integer: a cap would show
                // up on risk and overall, and asserting equality rather than "not lower" is what
                // makes a raise visible too.
                .andExpect(jsonPath("$.analysis.scores.risk").value(LLM_RISK))
                .andExpect(jsonPath("$.analysis.scores.overall").value(LLM_OVERALL))
                .andExpect(jsonPath("$.analysis.scores.completeness").value(LLM_COMPLETENESS))
                .andExpect(jsonPath("$.analysis.scores.equipment").value(LLM_EQUIPMENT))
                .andExpect(jsonPath("$.analysis.scores.value").value(LLM_VALUE))

                // And the verdict the LLM wrote, label included — applyFloor rewrites the label
                // when it lifts the code, so an unchanged label is a second witness to an
                // unchanged code.
                .andExpect(jsonPath("$.analysis.verdict.code").value("WORTH_CHECKING"))
                .andExpect(jsonPath("$.analysis.verdict.label").value("warto sprawdzić"))

                // The registry contributed no flag, so the LLM's own flag is still first. Registry
                // findings are prepended, so this is the assertion that notices a phantom
                // CEPIK_SIGNIFICANT_DAMAGE built out of a list nobody read.
                .andExpect(jsonPath("$.analysis.riskFlags[0].code").value("NO_SERVICE_HISTORY"))
                .andReturn().getResponse().getContentAsString();

        // A self-check on the case under test, not a second copy of the sibling's wire contract:
        // if serialisation ever turned this null into [], the assertions above would keep passing
        // while testing the empty-list case aCleanRegistryTimelineIsAnEmptyListAndMovesNothing owns.
        assertThat(body)
                .as("the case under test is a null timeline, not an empty one")
                .contains("\"damageRecords\":null");
    }

    @Test
    void theMockProfilesOwnFoundResultReachesTheAdjusterAndCapsTheScore() throws Exception {
        // MockCepikService itself, not an equivalent CepikResult typed out here. That coupling is
        // the whole test: mock is the only profile any quality gate or E2E run activates, so while
        // this bean returned LOOKUP_FAILED unconditionally, CepikRiskAdjuster was unreachable from
        // every cross-stack path in the repo. If the mock ever stops producing a FOUND result — or
        // stops carrying a damage record in it — this test fails, which is what stops that
        // reachability from quietly lapsing again.
        String body = standaloneWith(new MockCepikService()).perform(analysisRequest())
                .andExpect(status().isOk())

                // Oracle: MockCepikService.found()'s own values. registrationProvince in
                // particular is supplied by nothing else on this path, so it witnesses that the
                // payload is the mock's canned answer rather than an echo of the request.
                .andExpect(jsonPath("$.cepikResult.status").value("FOUND"))
                .andExpect(jsonPath("$.cepikResult.registrationProvince").value("mazowieckie"))
                .andExpect(jsonPath("$.cepikResult.damageRecords[0].description")
                        .value("Powstanie szkody istotnej"))

                // The damage branch ran. Two flags, not one: llmResult() claims "bezwypadkowy",
                // which the registry damage contradicts — so this is the worse of the two findings
                // and it forces HIGH_RISK_SKIP rather than flooring at NEEDS_MORE_INFO.
                .andExpect(jsonPath("$.analysis.riskFlags[0].code").value("CEPIK_SIGNIFICANT_DAMAGE"))
                .andExpect(jsonPath("$.analysis.riskFlags[1].code").value("CEPIK_CONTRADICTS_LISTING"))
                .andExpect(jsonPath("$.analysis.verdict.code").value("HIGH_RISK_SKIP"))

                // Relative, as in the journey test above: the point is that the registry pulled the
                // score down, not that it landed on whichever integer CAP_CONTRADICTED_CLAIM
                // currently holds. Pinning that constant here would mirror the implementation.
                .andExpect(jsonPath("$.analysis.scores.risk").value(lessThan(LLM_RISK)))
                .andExpect(jsonPath("$.analysis.scores.overall").value(lessThan(LLM_OVERALL)))
                .andReturn().getResponse().getContentAsString();

        // The mock is not stolen, not rolled back and insured, so exactly the two flags above are
        // expected — a third would mean the canned answer changed shape under the test.
        assertThat(body)
                .as("the mock's clean flags must not raise registry findings of their own")
                .doesNotContain("CEPIK_VEHICLE_LOST")
                .doesNotContain("CEPIK_ODOMETER_ROLLBACK")
                .doesNotContain("CEPIK_NO_OC_POLICY");
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures and stub plumbing
    // ---------------------------------------------------------------------------------------

    /**
     * The same controller wiring {@link #setUp()} builds, with the registry bean handed in.
     *
     * <p>A second builder rather than a parameter on {@code setUp}: the four journey tests above
     * depend on the {@code mockMvc} field and on the socket stub bound to its {@code RestClient},
     * and rewiring that for the two tests that need a different bean would put their fixture at
     * risk to buy back eight lines. Neither caller here touches the network, so {@code server} is
     * left with no expectations and is not verified.
     */
    private static MockMvc standaloneWith(CepikEnrichmentService cepikEnrichmentService) {
        var aiAnalysisService = mock(AiAnalysisService.class);
        when(aiAnalysisService.analyze(anyString())).thenReturn(llmResult());

        var marketPriceEnrichmentService = mock(MarketPriceEnrichmentService.class);
        when(marketPriceEnrichmentService.enrich(any())).thenReturn(null);

        return MockMvcBuilders
                .standaloneSetup(new AnalysisController(aiAnalysisService,
                        mock(ListingFetchService.class), cepikEnrichmentService,
                        marketPriceEnrichmentService, new CepikRiskAdjuster()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

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
