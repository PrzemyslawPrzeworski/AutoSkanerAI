package com.example.autoskaner_ai.analysis;

import java.time.Instant;
import java.util.List;

/**
 * Outcome of a historiapojazdu lookup.
 *
 * <p><b>Null and empty are not interchangeable here.</b> {@code damageRecords} may be an empty
 * list only when the registry's timeline was read successfully and contained no damage event —
 * that is a real "nothing reported to insurers". Any other case (no lookup, failed lookup,
 * unreadable timeline) must be {@code null}, because the UI renders an empty list as
 * "no damage reported" and a null as "unknown". Collapsing the two is what made this app
 * report a clean history for a vehicle carrying a szkoda istotna.
 *
 * <p>Use {@link #withoutData} for every non-FOUND status so that rule cannot be broken by
 * forgetting an argument.
 */
public record CepikResult(
        CepikStatus status,
        String vin,
        String firstRegistrationDatePl,
        String deregisteredDate,
        String originCountry,
        Integer ownerCount,
        List<MileageStamp> mileageStamps,
        List<DamageRecord> damageRecords,
        String lookupUrl,
        Instant fetchedAt,

        // Registry-reported identity. Worth surfacing on its own: a mismatch against the
        // listing's make/model/year is a stronger signal than anything the listing text says.
        String make,
        String model,
        String vehicleType,
        Integer yearOfManufacture,

        // Status flags. vehicleLost is a theft marker and odometerRolledBack is the registry's
        // own rollback detection — both are high-severity and neither was previously read.
        String registrationStatus,
        String technicalInspectionStatus,
        Boolean ocInsuranceValid,
        Boolean vehicleLost,
        Boolean odometerRolledBack,
        String registrationProvince,

        /** The full timeline, so an event type this code does not model still reaches the user. */
        List<VehicleEvent> events
) {

    /**
     * Where a user goes to repeat the lookup by hand. Lives here, next to the field it fills,
     * because it belongs to every result rather than to one of the classes that build them: it
     * was copied into four of those and the fifth caller — the controller's degrade-on-throw
     * guard — had no copy in reach and passed {@code null}, which the LOOKUP_FAILED card then
     * rendered as {@code href="null"} under copy telling the user to go look it up themselves.
     */
    public static final String LOOKUP_URL = "https://historiapojazdu.gov.pl";

    /**
     * A result carrying no registry data. Every list stays null rather than empty — see the
     * class comment.
     */
    public static CepikResult withoutData(CepikStatus status, String vin, String lookupUrl) {
        return new CepikResult(
                status, vin, null, null, null, null, null, null, lookupUrl, Instant.now(),
                null, null, null, null,
                null, null, null, null, null, null,
                null);
    }
}
