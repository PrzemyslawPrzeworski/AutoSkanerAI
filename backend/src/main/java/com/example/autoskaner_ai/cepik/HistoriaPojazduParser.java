package com.example.autoskaner_ai.cepik;

import com.example.autoskaner_ai.analysis.CepikResult;
import com.example.autoskaner_ai.analysis.CepikStatus;
import com.example.autoskaner_ai.analysis.DamageRecord;
import com.example.autoskaner_ai.analysis.MileageStamp;
import com.example.autoskaner_ai.analysis.VehicleEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps the two historiapojazdu payloads onto {@link CepikResult}.
 *
 * <p>Every field name here was read off live responses for NMTBZ3BE40R000000 (captured as the
 * test fixtures under {@code src/test/resources/cepik/}). The previous version of this class
 * looked for {@code zdarzenia}, {@code szkodyIstotne}, {@code przebieg} and
 * {@code liczbaWlascicieli} — none of which the registry has ever returned — and its tests
 * passed because the fixtures were hand-written to match the invented names. The result was a
 * FOUND response with an empty damage list for a vehicle that carries a szkoda istotna, which
 * the UI renders as "no damage reported". Do not add a mapping that has not been observed in a
 * real payload; leave the field null and say so instead.
 */
@Component
@Profile("!mock")
public class HistoriaPojazduParser {

    private static final Logger log = LoggerFactory.getLogger(HistoriaPojazduParser.class);

    private static final String LOOKUP_URL = "https://historiapojazdu.gov.pl";

    /** Damage events are identified by type; the registry uses a kebab-case Polish vocabulary. */
    private static final String DAMAGE_EVENT_TYPE = "szkoda-istotna";
    private static final String INSPECTION_EVENT_PREFIX = "badanie-techniczne";
    private static final String FIRST_REGISTRATION_PL_EVENT = "pierwsza-rejestracja-w-polsce";

    /**
     * Every {@code eventType} value present in the captured timeline (all ten of them, across the
     * fourteen events in {@code timeline-data-found.json}) — a record of what was observed, not a
     * new mapping. Only three are interpreted below; the rest are here because they are evidence
     * that the vocabulary is the one this parser was written against.
     *
     * <p>Its job is to catch the 2026-08-26 failure reached <em>without</em> anyone inventing a
     * field name. If the registry renames or restructures its event types, every match below stops
     * firing silently and {@code damagesFrom} returns an empty list, which the UI renders as "brak
     * zgłoszonych szkód istotnych" for a car that may carry a szkoda istotna. So a timeline whose
     * events carry none of these types is reported as unknown rather than clean.
     */
    private static final Set<String> KNOWN_EVENT_TYPES = Set.of(
            "pierwszy-wlasciciel",
            "dodanie-wspolwlasciciela",
            "pierwsza-rejestracja-w-polsce",
            "szkoda-istotna",
            "zbycie-i-nabycie",
            "zbycie",
            "nabycie",
            "zmiana-wlasciciela",
            "badanie-techniczne-dodatkowe",
            "badanie-techniczne-okresowe");

    // Detail row labels, as returned. Matched case-insensitively on a prefix so a trailing
    // wording change ("Kategorie" -> "Kategorie szkody") does not silently drop the value.
    private static final String DETAIL_INSURER = "nazwa ubezpieczyciela";
    private static final String DETAIL_CATEGORIES = "kategorie";
    private static final String DETAIL_ODOMETER = "odczytany stan drogomierza";

    /** "12330 km", "26 320 km" — digits with optional spacing, unit ignored. */
    private static final Pattern ODOMETER_VALUE = Pattern.compile("(\\d[\\d\\s\\u00A0]*)");

    public CepikResult parse(Map<String, Object> vehicleData, Map<String, Object> timelineData, String vin) {
        Map<String, Object> basic = nested(nested(vehicleData, "technicalData"), "basicData");
        Map<String, Object> timeline = nested(timelineData, "timelineData");

        // The registry answered but neither payload was readable — a 204, an empty 200, or a shape
        // with neither node in it. Reporting FOUND here builds a "found in the registry" panel with
        // every field empty, which reads as a clean history. LOOKUP_FAILED rather than NOT_FOUND
        // because a definitive "no such vehicle" arrives as a 404 carrying HIPO-0002 and is
        // classified by HistoriaPojazduService; this is the registry answering unintelligibly.
        if (basic == null && timeline == null) {
            log.warn("historiapojazdu returned no readable technicalData.basicData and no timelineData"
                    + " — reporting LOOKUP_FAILED rather than an empty FOUND");
            return CepikResult.withoutData(CepikStatus.LOOKUP_FAILED, vin, LOOKUP_URL);
        }

        List<VehicleEvent> events = readEvents(timeline);
        boolean vocabularyRecognised = recognisesVocabulary(events);
        if (events != null && !vocabularyRecognised) {
            log.warn("historiapojazdu timeline carries {} event(s) and not one of a recognised type"
                    + " — reporting damage and mileage as unknown, not as none", events.size());
        }

        // Null, not empty, when the timeline could not be read or its vocabulary is not the one
        // observed in the captures: an empty damage list is a positive claim that the registry
        // reported nothing, and in neither case can we make it.
        List<DamageRecord> damageRecords = vocabularyRecognised ? damagesFrom(events) : null;
        List<MileageStamp> mileageStamps = vocabularyRecognised ? mileageFrom(events) : null;

        return new CepikResult(
                CepikStatus.FOUND,
                vin,
                firstRegistrationInPoland(events),
                // Not derivable from any payload observed so far. Guessing an event name is
                // exactly how the previous mappings went wrong — leave null until seen.
                null,
                null,
                asInteger(value(timeline, "totalOwners")),
                mileageStamps,
                damageRecords,
                LOOKUP_URL,
                Instant.now(),
                asString(value(basic, "make")),
                asString(value(basic, "model")),
                asString(value(basic, "type")),
                asInteger(asFirstNonNull(value(basic, "yearOfManufacture"), value(timeline, "yearOfManufacture"))),
                asString(value(basic, "registrationStatus")),
                asString(asFirstNonNull(value(basic, "technicalInspectionStatus"),
                        value(timeline, "technicalInspectionStatus"))),
                asBoolean(asFirstNonNull(value(basic, "hasCurrentOCPolicy"), value(timeline, "validOcInsurance"))),
                asBoolean(asFirstNonNull(value(basic, "vehicleLost"), value(timeline, "vehicleLost"))),
                odometerRolledBack(basic, timeline),
                asString(value(timeline, "registrationProvince")),
                events);
    }

    /** @return null when the timeline is absent or not a list — never an empty list. */
    private List<VehicleEvent> readEvents(Map<String, Object> timeline) {
        Object raw = value(timeline, "events");
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        List<VehicleEvent> events = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            events.add(new VehicleEvent(
                    asString(map.get("eventDate")),
                    asString(map.get("eventType")),
                    asString(map.get("eventName")),
                    readDetails(map.get("eventDetails"))));
        }
        return Collections.unmodifiableList(events);
    }

    /**
     * @return true only if at least one event carries a type observed in the captured payload.
     *     An empty event list takes the same branch as an unrecognised one: no registered vehicle
     *     has a timeline with zero events, so that is a shape we do not understand rather than a
     *     history with nothing in it.
     */
    private boolean recognisesVocabulary(List<VehicleEvent> events) {
        if (events == null) {
            return false;
        }
        for (VehicleEvent event : events) {
            if (event.type() != null && KNOWN_EVENT_TYPES.contains(event.type())) {
                return true;
            }
        }
        return false;
    }

    private List<VehicleEvent.EventDetail> readDetails(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<VehicleEvent.EventDetail> details = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                details.add(new VehicleEvent.EventDetail(asString(map.get("name")), asString(map.get("value"))));
            }
        }
        return Collections.unmodifiableList(details);
    }

    private List<DamageRecord> damagesFrom(List<VehicleEvent> events) {
        List<DamageRecord> damages = new ArrayList<>();
        for (VehicleEvent event : events) {
            if (!DAMAGE_EVENT_TYPE.equals(event.type())) continue;
            damages.add(new DamageRecord(
                    event.date(),
                    event.name(),
                    detail(event, DETAIL_INSURER),
                    splitCategories(detail(event, DETAIL_CATEGORIES))));
        }
        return Collections.unmodifiableList(damages);
    }

    // A vehicle can carry several categories on one claim; the registry joins them with commas.
    private List<String> splitCategories(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> categories = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) categories.add(trimmed);
        }
        return Collections.unmodifiableList(categories);
    }

    // The registry's own odometer list carries no date, so the dated history comes from the
    // inspection events instead — each one records the reading taken at the station.
    private List<MileageStamp> mileageFrom(List<VehicleEvent> events) {
        List<MileageStamp> stamps = new ArrayList<>();
        for (VehicleEvent event : events) {
            if (event.type() == null || !event.type().startsWith(INSPECTION_EVENT_PREFIX)) continue;
            Integer km = parseOdometer(detail(event, DETAIL_ODOMETER));
            if (event.date() != null && km != null) {
                stamps.add(new MileageStamp(event.date(), km));
            }
        }
        stamps.sort((a, b) -> a.date().compareTo(b.date()));
        return Collections.unmodifiableList(stamps);
    }

    private Integer parseOdometer(String raw) {
        if (raw == null) return null;
        Matcher matcher = ODOMETER_VALUE.matcher(raw);
        if (!matcher.find()) return null;
        String digits = matcher.group(1).replaceAll("[\\s\\u00A0]", "");
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String firstRegistrationInPoland(List<VehicleEvent> events) {
        if (events == null) return null;
        for (VehicleEvent event : events) {
            if (FIRST_REGISTRATION_PL_EVENT.equals(event.type())) {
                return event.date();
            }
        }
        return null;
    }

    /** True if the registry flagged any reading as rolled back. Null when there are no readings. */
    private Boolean odometerRolledBack(Map<String, Object> basic, Map<String, Object> timeline) {
        Object raw = asFirstNonNull(value(basic, "odometerReadings"), value(timeline, "odometerReadings"));
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> map && Boolean.TRUE.equals(asBoolean(map.get("rolledBack")))) {
                return true;
            }
        }
        return false;
    }

    private String detail(VehicleEvent event, String labelPrefix) {
        for (VehicleEvent.EventDetail d : event.details()) {
            if (d.name() != null && d.name().strip().toLowerCase().startsWith(labelPrefix)) {
                return d.value();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nested(Map<String, Object> source, String key) {
        Object child = value(source, key);
        return child instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private Object value(Map<String, Object> source, String key) {
        return source == null ? null : source.get(key);
    }

    private Object asFirstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }

    private String asString(Object value) {
        return value instanceof String s ? s : null;
    }

    private Boolean asBoolean(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) {
            if ("true".equalsIgnoreCase(s)) return true;
            if ("false".equalsIgnoreCase(s)) return false;
        }
        return null;
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.strip());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return null;
    }
}
