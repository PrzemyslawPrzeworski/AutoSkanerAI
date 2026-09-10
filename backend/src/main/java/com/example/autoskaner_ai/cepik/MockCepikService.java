package com.example.autoskaner_ai.cepik;

import com.example.autoskaner_ai.analysis.CepikResult;
import com.example.autoskaner_ai.analysis.CepikStatus;
import com.example.autoskaner_ai.analysis.DamageRecord;
import com.example.autoskaner_ai.analysis.ExtractedData;
import com.example.autoskaner_ai.analysis.MileageStamp;
import com.example.autoskaner_ai.analysis.VehicleEvent;
import com.example.autoskaner_ai.analysis.VinValidator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The registry lookup under the {@code mock} profile: the same three-input gate as the real bean,
 * then one canned answer describing the synthetic vehicle.
 *
 * <p>This used to be twenty lines returning {@code LOOKUP_FAILED} unconditionally, with no
 * conditional of any kind. {@code mock} is the only profile any quality gate or E2E run activates,
 * so that made {@code CepikRiskAdjuster} unreachable end to end — 254 lines of registry scoring
 * that no cross-stack path ever executed — and made this the one implementation of the port that
 * cannot fail the way the real one fails, nor succeed the way it succeeds.
 *
 * <p><b>Why the plate pattern is duplicated here.</b> {@link RealCepikEnrichmentService} holds a
 * copy of the same regex and no shared validator is extracted, because
 * {@code CepikEnrichmentServiceContractTest} runs its malformed-input properties against
 * <em>both</em> beans: the two copies are held in agreement by a test rather than by hope, which is
 * what makes the duplication acceptable instead of drift-prone. The date is the deliberate
 * exception — any non-blank value is well-formed here, while the real bean parses six formats under
 * strict resolution. Do not copy those formats in; {@code backend/CLAUDE.md} forbids the second
 * copy, and the contract test narrows its date axis to null and blank for exactly that reason.
 *
 * <p>A {@code FOUND} result has no factory — {@link CepikResult#withoutData} covers only the
 * degraded paths — so the happy path is built with the full component list. Every value in it is
 * the committed synthetic vehicle, read off the captured fixtures under
 * {@code src/test/resources/cepik/}. This repository is public: no real vehicle's VIN, plate or
 * owner data may appear here.
 */
@Service
@Profile("mock")
public class MockCepikService implements CepikEnrichmentService {

    /** Its own copy, deliberately — see the class comment. */
    private static final Pattern PLATE_PATTERN = Pattern.compile("[A-Z]{2,3}[A-Z0-9]{4,5}");

    private static final String SYNTHETIC_VIN = "NMTBZ3BE40R000000";

    // The two timeline events the canned answer is derived from, in the registry's own vocabulary:
    // eventType "szkoda-istotna" with "Nazwa ubezpieczyciela" / "Kategorie" detail rows, and an
    // inspection carrying "Odczytany stan drogomierza". The timeline travels with the result the
    // way the parser passes it through, so the mock is not a shape the real bean cannot produce.
    private static final VehicleEvent DAMAGE_EVENT = new VehicleEvent(
            "2023-02-07", "szkoda-istotna", "Powstanie szkody istotnej",
            List.of(new VehicleEvent.EventDetail("Nazwa ubezpieczyciela", "PZU"),
                    new VehicleEvent.EventDetail("Kategorie",
                            "Uszkodzenie elementów układu nośnego")));

    private static final VehicleEvent INSPECTION_EVENT = new VehicleEvent(
            "2025-04-14", "badanie-techniczne-okresowe", "Okresowe badanie techniczne",
            List.of(new VehicleEvent.EventDetail("Wynik badania", "pozytywny"),
                    new VehicleEvent.EventDetail("Odczytany stan drogomierza", "26320 km")));

    @Override
    public CepikResult enrich(ExtractedData extracted) {
        // Same order and same short-circuits as the real bean: an unusable VIN has no normalised
        // form to echo, so it reports null, while the later two carry the VIN they did validate.
        var normalisedVin = VinValidator.normalise(extracted.vin());
        if (normalisedVin.isEmpty()) {
            return missingInputs(null);
        }
        String vin = normalisedVin.get();

        if (!plateIsWellFormed(extracted.registrationPlate())) {
            return missingInputs(vin);
        }
        if (extracted.firstRegistrationDate() == null || extracted.firstRegistrationDate().isBlank()) {
            return missingInputs(vin);
        }

        return found();
    }

    /**
     * Trim, upper-case and strip spaces and hyphens before matching, as the real bean does: a plate
     * is printed with a space ("WA 12345") and typed the way it is printed, so matching the pattern
     * against the raw value turns the commonest form of a correct plate into MISSING_INPUTS.
     */
    private static boolean plateIsWellFormed(String raw) {
        if (raw == null) {
            return false;
        }
        return PLATE_PATTERN.matcher(raw.trim().toUpperCase().replaceAll("[\\s\\-]", "")).matches();
    }

    /**
     * Built through {@code withoutData} rather than by hand, so the null-lists rule holds by
     * construction. An empty {@code damageRecords} renders as "no damage reported to insurers",
     * which for a vehicle nobody looked up is the absence-is-not-clean violation.
     */
    private static CepikResult missingInputs(String vin) {
        return CepikResult.withoutData(CepikStatus.MISSING_INPUTS, vin, CepikResult.LOOKUP_URL);
    }

    /** The synthetic vehicle: registered, insured, not stolen, and carrying one szkoda istotna. */
    private static CepikResult found() {
        return new CepikResult(
                CepikStatus.FOUND,
                SYNTHETIC_VIN,
                "2022-04-12",
                // Null for the same reason HistoriaPojazduParser leaves them null: no observed
                // payload carries them, and inventing a value here would make the mock the source
                // of a field the real bean can never fill.
                null,
                null,
                2,
                List.of(new MileageStamp("2025-04-14", 26_320)),
                List.of(new DamageRecord("2023-02-07", "Powstanie szkody istotnej", "PZU",
                        List.of("Uszkodzenie elementów układu nośnego"))),
                CepikResult.LOOKUP_URL,
                Instant.now(),
                "TOYOTA",
                "TOYOTA COROLLA",
                "SAMOCHÓD OSOBOWY",
                2022,
                "Zarejestrowany",
                "aktualne",
                true,
                false,
                false,
                "mazowieckie",
                List.of(DAMAGE_EVENT, INSPECTION_EVENT));
    }
}
