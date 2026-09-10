package com.example.autoskaner_ai.cepik;

import com.example.autoskaner_ai.analysis.CepikResult;
import com.example.autoskaner_ai.analysis.CepikStatus;
import com.example.autoskaner_ai.analysis.ExtractedData;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The degraded-path rules that hold of <em>any</em> {@link CepikEnrichmentService}, asserted once
 * and run against every implementation.
 *
 * <p>The subject here is the interface, not a class — both implementations are parameters — which
 * is why the file is named after the port and deviates from the one-test-class-per-class naming in
 * {@code context/foundation/test-plan.md} §6.1. It exists because the port's two hard rules are
 * carried by convention rather than by the type system: a second implementation is free to mean
 * something else by {@code MISSING_INPUTS}, or to answer an empty list where the first answers
 * null, and nothing would notice. Empty means "the registry was read and reported nothing"; null
 * means "unknown". The product guardrail is <em>absence is not clean</em>, so a bean that
 * substitutes one for the other reports a clean history for a car nobody looked up.
 *
 * <p><b>The inputs axis is malformed-only, on purpose.</b> With well-formed inputs the
 * implementations are <em>supposed</em> to differ: the real bean delegates to
 * {@code HistoriaPojazduService} and returns whatever the registry said, while a mock synthesises
 * an answer. There is no shared property to assert there. Every property below is therefore a
 * statement about what happens when an input is absent or unusable — the only stretch of the port
 * where both beans must agree.
 *
 * <p><b>Narrowing 1 — the date axis covers {@code null} and blank only.</b> The two beans
 * legitimately disagree about date <em>format</em>: {@code RealCepikEnrichmentService} parses six
 * formats under {@code ResolverStyle.STRICT} and rejects {@code 31.02.2016} and
 * {@code kwiecień 2022}, whereas a mock that treats any non-blank string as well-formed is still a
 * correct implementation of this port. Format coverage stays where the behaviour lives, in
 * {@code RealCepikEnrichmentServiceTest}; asserting it here would bind every future implementation
 * to that bean's date parser.
 *
 * <p><b>Narrowing 2 — {@code result.vin()} is not asserted.</b> "The result echoes the requested
 * VIN" is false by design: {@code RealCepikEnrichmentServiceTest.invalidVinShortCircuitsWithNullVin}
 * pins {@code vin()} to null for {@code "NOT-A-VIN"}, because there is no normalised VIN to echo,
 * while the plate and date cases do carry it. A contract assertion on that field would have to pick
 * one of the two and would then contradict the other.
 *
 * <p>The VIN below is the committed synthetic value — this repository is public, so no real
 * vehicle's VIN may appear in it.
 */
class CepikEnrichmentServiceContractTest {

    private static final String VIN = "NMTBZ3BE40R000000";
    private static final String PLATE = "WX00000";
    private static final String FIRST_REG_DATE = "2022-04-12";

    /**
     * One implementation under test, plus the collaborator it was built with.
     *
     * <p>The collaborator rides along because one of the properties is about an outbound call and
     * not only about a returned status, and only the real bean has anything to observe: a mock
     * implementation answers out of itself and has no registry to leave alone. It is therefore
     * nullable, and the {@code verifyNoInteractions} assertion is skipped where it is null rather
     * than the parameter being dropped — the status and null-list properties bind every
     * implementation either way.
     */
    private record Implementation(String name, CepikEnrichmentService service,
                                  HistoriaPojazduService registry) {
        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * Every implementation of the port. Adding one is a single {@code Stream.of} element — that is
     * the point of the shape, since an implementation the contract does not run against is an
     * implementation free to drift.
     */
    static Stream<Implementation> implementations() {
        // Fresh per test method: JUnit calls the factory once for each @ParameterizedTest, so
        // interactions (or the absence of them) never leak from one property to the next.
        HistoriaPojazduService registry = mock(HistoriaPojazduService.class);
        return Stream.of(
                new Implementation("RealCepikEnrichmentService",
                        new RealCepikEnrichmentService(registry), registry),
                new Implementation("MockCepikService", new MockCepikService(), null));
    }

    /** A named degraded input, so a failure names the case rather than a list index. */
    private record Inputs(String description, ExtractedData data) {
    }

    /**
     * The six ways the three required inputs can be absent or unusable — two per input, absent and
     * malformed, with the other two fields well-formed so exactly one thing is wrong at a time.
     */
    private static List<Inputs> malformedInputs() {
        return List.of(
                new Inputs("null VIN", extracted(null, PLATE, FIRST_REG_DATE)),
                // Seven characters once the hyphens are stripped, so VinValidator rejects it.
                new Inputs("VIN rejected by VinValidator", extracted("NOT-A-VIN", PLATE, FIRST_REG_DATE)),
                new Inputs("null plate", extracted(VIN, null, FIRST_REG_DATE)),
                new Inputs("plate rejected by the plate pattern", extracted(VIN, "??", FIRST_REG_DATE)),
                new Inputs("null date", extracted(VIN, PLATE, null)),
                // Blank, not misformatted -- see narrowing 1 in the class comment.
                new Inputs("blank date", extracted(VIN, PLATE, "   ")));
    }

    private static ExtractedData extracted(String vin, String plate, String firstRegDate) {
        return new ExtractedData(
                "Toyota", "Corolla", 2022, BigDecimal.valueOf(89_000), "PLN", 26_320,
                null, null, null, null, null, null, null,
                vin, plate, firstRegDate);
    }

    // ---------------------------------------------------------------------------------------
    // Property 1: an absent or malformed input is MISSING_INPUTS, and never reaches the registry
    // ---------------------------------------------------------------------------------------

    // Soft assertions so one run reports every violating case at once. A hard assertion would
    // stop at the first, which turns measuring a candidate implementation against this contract
    // into one run per defect.
    @ParameterizedTest
    @MethodSource("implementations")
    void absentOrMalformedInputsYieldMissingInputs(Implementation implementation) {
        assertSoftly(softly -> {
            for (Inputs inputs : malformedInputs()) {
                CepikResult result = implementation.service().enrich(inputs.data());

                softly.assertThat(result.status())
                        .as("%s: %s must yield MISSING_INPUTS", implementation.name(),
                                inputs.description())
                        .isEqualTo(CepikStatus.MISSING_INPUTS);
            }
        });

        // "Malformed inputs never reach the registry" is a claim about the outbound call, so the
        // status above cannot carry it: a bean could call the registry, discard the answer and
        // still return MISSING_INPUTS. Only implementations with a collaborator can be checked
        // this way -- see the Implementation record.
        if (implementation.registry() != null) {
            verifyNoInteractions(implementation.registry());
        }
    }

    // ---------------------------------------------------------------------------------------
    // Property 2: a result that is not FOUND carries null lists, never empty ones
    // ---------------------------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("implementations")
    void aResultThatIsNotFoundCarriesNullListsNeverEmptyOnes(Implementation implementation) {
        assertSoftly(softly -> {
            for (Inputs inputs : malformedInputs()) {
                CepikResult result = implementation.service().enrich(inputs.data());

                softly.assertThat(result.status())
                        .as("%s: %s cannot be FOUND", implementation.name(), inputs.description())
                        .isNotEqualTo(CepikStatus.FOUND);
                // An empty damage list renders as "no damage reported to insurers". For a result
                // where nothing was ever looked up, that is the absence-is-not-clean violation.
                softly.assertThat(result.damageRecords())
                        .as("%s: %s must carry null damageRecords, never an empty list",
                                implementation.name(), inputs.description())
                        .isNull();
                softly.assertThat(result.mileageStamps())
                        .as("%s: %s must carry null mileageStamps, never an empty list",
                                implementation.name(), inputs.description())
                        .isNull();
            }
        });
    }

    // ---------------------------------------------------------------------------------------
    // Property 3: fetchedAt is stamped on every path, degraded ones included
    // ---------------------------------------------------------------------------------------

    // Valid as a contract property because CepikResult.withoutData stamps Instant.now() itself, so
    // an implementation that builds its degraded results through the factory gets this for free --
    // and one that hand-rolls the 21-component constructor is exactly what needs catching. The UI
    // dates the panel from this field; a null there means the card cannot say when it was checked.
    @ParameterizedTest
    @MethodSource("implementations")
    void fetchedAtIsStampedOnDegradedPathsToo(Implementation implementation) {
        assertSoftly(softly -> {
            for (Inputs inputs : malformedInputs()) {
                CepikResult result = implementation.service().enrich(inputs.data());

                softly.assertThat(result.fetchedAt())
                        .as("%s: %s must still record when the lookup was attempted",
                                implementation.name(), inputs.description())
                        .isNotNull();
            }
        });
    }
}
