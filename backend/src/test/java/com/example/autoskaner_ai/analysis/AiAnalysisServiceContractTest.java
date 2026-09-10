package com.example.autoskaner_ai.analysis;

import com.example.autoskaner_ai.analysis.llm.AnalysisResponseParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * The accident-declaration rule that holds of <em>any</em> {@link AiAnalysisService}, asserted once
 * and run against every implementation.
 *
 * <p>The subject is the interface, not a class — the implementations are parameters — which is why
 * the file is named after the port and deviates from the one-test-class-per-class naming in
 * {@code context/foundation/test-plan.md} §6.1. It is the sibling of
 * {@code CepikEnrichmentServiceContractTest} and follows its shape deliberately.
 *
 * <h2>The property, and where it comes from</h2>
 *
 * <p>One property: <b>when {@code extracted().accidentClaim()} is null, {@code riskFlags} carries
 * {@code NO_ACCIDENT_DECLARATION} at {@code MEDIUM}.</b> Its oracle is
 * {@code AnalysisPrompt.java:16} verbatim — the locked output schema — and behind that the root
 * {@code CLAUDE.md} § "Key business rules": <em>absence of accident data means unknown, not clean.
 * Never present missing data as confirmation of clean history.</em> The oracle is not
 * {@link AnalysisResponseParser}, and it is not whatever an implementation currently happens to do;
 * an implementation that disagrees with the prompt is the thing being measured.
 *
 * <p>It is a port property rather than a per-class one because the failure it guards against is
 * silence, and silence looks identical from the outside whoever produced it. A listing that talks a
 * model out of the flag, a free-tier model that drops a list entry under length pressure, and a stub
 * that never had the rule in the first place all render the same way: an accident history the app
 * does not know anything about, shown with nothing said about it. Under the {@code mock} profile —
 * the only profile the git hooks and the E2E specs ever run — no parser and no registry adjuster is
 * in the path at all, so this is the only place the rule can be pinned for that profile.
 *
 * <h2>The two parameters consume different inputs, and that is unavoidable here</h2>
 *
 * <p>{@link AiAnalysisService#analyze(String)} takes a listing text, and a text-driven
 * implementation reads it. The port's real counterpart, though, is
 * {@link AnalysisResponseParser} — the accident rule lives there, and both network beans call it —
 * and a parser consumes <em>model JSON</em>, not a listing. There is therefore no single input that
 * drives both sides, and pretending otherwise would be worse than the asymmetry: a reader who
 * assumes both parameters saw the same text will draw a wrong conclusion from a future failure.
 *
 * <p>So each parameter carries <em>its own</em> inputs, and what is shared is the <b>property</b>,
 * not the input. The parser side is reached through an adapter whose {@code analyze} ignores its
 * argument (see {@link #parserBackedAdapter()}); its input list holds one deliberately empty string
 * to make that visible rather than to feed it anything.
 *
 * <h2>What is deliberately not asserted</h2>
 *
 * <p><b>The converse — "no flag when a declaration exists" — is not a property here.</b> A
 * text-driven implementation can key the flag off different words than it keys {@code accidentClaim}
 * off and so emit the flag for a listing that plainly mentions a collision; that is over-flagging,
 * and this contract is silent about it on purpose. The direction of the two errors is not
 * symmetric: an extra "the advert is silent" entry on a listing that was not silent is noise, while
 * a missing one hands a buyer an unknown history dressed as a clean one.
 *
 * <p><b>An extra, unrelated flag violates nothing.</b> {@code withAccidentDeclarationFlag} only ever
 * appends, so the property is about what {@code riskFlags} <em>contains</em>, never about its size or
 * ordering.
 *
 * <p><b>The Polish description string is not asserted.</b>
 * {@code ListingClaimsCannotMoveTheFloorTest} pins the parser's exact wording, which is the right
 * place for it; binding every future implementation to one sentence would be pinning our copy rather
 * than the rule.
 *
 * <h2>What this adds over {@code ListingClaimsCannotMoveTheFloorTest}</h2>
 *
 * <p>That file already pins all three of the parser's own branches — the flag added back, the
 * model's own entry left alone, and a stated claim not getting the flag — driving the parser
 * directly. Nothing here improves on that. What is new is only that the assertion is now expressed
 * against the <em>interface</em>, so it can be run against a second implementation as a single
 * {@code Stream.of} element. The parser parameter below is the harness proving itself against the
 * implementation already known to satisfy the rule; the value arrives when another implementation is
 * added to {@link #implementations()}.
 */
class AiAnalysisServiceContractTest {

    /**
     * The flag code, spelled out rather than read off {@link AnalysisResponseParser} (where it is
     * private anyway). A test that imported the constant from one implementation would agree with
     * that implementation by construction; the string belongs to the prompt.
     */
    private static final String NO_ACCIDENT_DECLARATION = "NO_ACCIDENT_DECLARATION";

    /**
     * A committed model response in the locked output schema with {@code "accidentClaim": null} and
     * {@code "riskFlags": []} — so the flag the property looks for can only have been put there by
     * the implementation under test. It arrives inside a markdown fence, which the parser strips;
     * that is incidental here but harmless.
     *
     * <p>Not {@code hollow-all-leaves-null.json}, which the plan named: since the minimal-spine
     * check landed, that fixture is <em>rejected</em> — {@code AnalysisResponseParserTest
     * .throwsOnAHollowResponseWhereEveryLeafIsNull} pins it to a {@code LlmResponseSchemaException}
     * on {@code scores.completeness}, so it never reaches the accident rule and cannot drive this
     * property. This one parses, and its null {@code accidentClaim} is independently pinned by
     * {@code AnalysisResponseParserTest.aResponseMissingOptionalFieldsStillParses}, so editing the
     * fixture out from under this test breaks that test too.
     */
    private static final String SILENT_MODEL_RESPONSE = "valid-response-with-fence.json";

    /**
     * One implementation under test, together with the inputs that drive it into the branch the
     * property is about.
     *
     * <p>The inputs ride along per parameter rather than being shared, because the two sides of this
     * port do not consume the same thing — see the class comment. Making the inputs part of the
     * parameter is what lets the property stay single.
     */
    private record Implementation(String name, AiAnalysisService service, List<Input> inputs) {
        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * A named listing text, so a failure names the input class rather than a list index. The
     * description is ASCII even where the text is not — AssertJ descriptions are read out of a
     * terminal.
     */
    private record Input(String description, String listingText) {
    }

    /**
     * Every implementation of the port. Adding one is a single {@code Stream.of} element — that is
     * the point of the shape, since an implementation the contract does not run against is an
     * implementation free to drift.
     */
    static Stream<Implementation> implementations() {
        return Stream.of(
                new Implementation("AnalysisResponseParser (adapted)", parserBackedAdapter(),
                        // Exactly one input, and its text is ignored: the adapter is driven by the
                        // fixture, not by this string. Empty rather than plausible-looking so nobody
                        // reads a listing into it.
                        List.of(new Input("committed model response with a null accidentClaim", ""))),
                new Implementation("MockAiAnalysisService", new MockAiAnalysisService(),
                        // Two inputs, because the mock breaks this property in two independent ways
                        // and one input can only show one of them. Neither text contains
                        // "bezwypadkowy", "wypadek" or "kolizja", so both leave accidentClaim null
                        // and both are inside the branch the property is about.
                        List.of(
                                // The input class the upstream report missed. The mock suppresses the
                                // flag on the substring "historia" -- a word that says nothing about
                                // an accident declaration -- so ordinary service-history boilerplate
                                // silences it while accidentClaim stays null. This is the absence
                                // presented as clean.
                                new Input("service-history boilerplate containing \"historia\"",
                                        "Pelna historia serwisowa, drugi wlasciciel, rok 2019."),
                                // No suppressing keyword at all, so the flag IS raised here and only
                                // its severity can be wrong. Separated from the case above so the two
                                // defects are counted separately rather than one masking the other.
                                new Input("plain listing with no accident keyword and no \"historia\"",
                                        "Sprzedam auto, rok 2019, przebieg 120 tys. km, cena 45000 PLN."))));
    }

    /**
     * The port's real counterpart behind the port's signature: {@code analyze} ignores the listing
     * text and returns the parse of {@link #SILENT_MODEL_RESPONSE}.
     *
     * <p>{@link AnalysisResponseParser} is the real side of this port rather than
     * {@code OpenRouterAnalysisService} or {@code BedrockClaudeAnalysisService}: the accident rule
     * lives in the parser and both network beans call it, so a contract test against a network bean
     * would be testing a socket. Constructed without Spring — the parser's only collaborator is an
     * {@link ObjectMapper}, matching the idiom in {@code AnalysisResponseParserTest} and
     * {@code ListingClaimsCannotMoveTheFloorTest}.
     */
    private static AiAnalysisService parserBackedAdapter() {
        AnalysisResponseParser parser = new AnalysisResponseParser(new ObjectMapper());
        String modelJson = fixture(SILENT_MODEL_RESPONSE);
        return listingText -> parser.parse(modelJson, "openrouter", "contract-test", 1L);
    }

    private static String fixture(String name) {
        String path = "fixtures/llm/" + name;
        try (InputStream in = AiAnalysisServiceContractTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            assertThat(in).as("fixture %s not found on the test classpath", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read fixture " + path, e);
        }
    }

    // ---------------------------------------------------------------------------------------
    // The property: an absent accident declaration is reported as unknown, at MEDIUM
    // ---------------------------------------------------------------------------------------

    // Soft assertions so one run reports every violating input at once. A hard assertion would stop
    // at the first, which turns measuring a candidate implementation against this contract into one
    // run per defect.
    @ParameterizedTest
    @MethodSource("implementations")
    void anAbsentAccidentDeclarationIsFlaggedAsUnknownAtMedium(Implementation implementation) {
        assertSoftly(softly -> {
            for (Input input : implementation.inputs()) {
                AnalysisResult result = implementation.service().analyze(input.listingText());

                // A guard, not the property. The property is conditional on a null accidentClaim, so
                // an input that yields a non-null one satisfies it vacuously and quietly stops
                // testing anything. This assertion is what fails loudly instead -- including if the
                // fixture behind the parser parameter is edited to state a claim.
                softly.assertThat(result.extracted().accidentClaim())
                        .as("%s: %s must reach the branch under test -- accidentClaim null",
                                implementation.name(), input.description())
                        .isNull();

                List<RiskSeverity> severities = result.riskFlags().stream()
                        .filter(flag -> NO_ACCIDENT_DECLARATION.equals(flag.code()))
                        .map(RiskFlag::severity)
                        .toList();

                // Split in two on purpose: "the flag is missing" and "the flag is there at the wrong
                // severity" are different defects with different fixes, and a single combined
                // assertion would report whichever it hit first as if it were the only one.
                softly.assertThat(severities)
                        .as("%s: %s must raise %s -- absence of accident data is unknown, never clean",
                                implementation.name(), input.description(), NO_ACCIDENT_DECLARATION)
                        .isNotEmpty();
                // Vacuous on an empty list, which is deliberate: when the flag is absent the
                // assertion above already said so, and reporting the same defect twice buries the
                // count of how many things are actually wrong.
                softly.assertThat(severities)
                        .as("%s: %s must raise %s at the severity AnalysisPrompt.java:16 fixes",
                                implementation.name(), input.description(), NO_ACCIDENT_DECLARATION)
                        .allSatisfy(severity -> assertThat(severity).isEqualTo(RiskSeverity.MEDIUM));
            }
        });
    }
}
