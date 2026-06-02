package com.example.autoskaner_ai.cepik;

import com.example.autoskaner_ai.analysis.CepikResult;
import com.example.autoskaner_ai.analysis.CepikStatus;
import com.example.autoskaner_ai.analysis.DamageRecord;
import com.example.autoskaner_ai.analysis.MileageStamp;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@Profile("!mock")
public class HistoriaPojazduParser {

    private static final String LOOKUP_URL = "https://historiapojazdu.gov.pl";

    @SuppressWarnings("unchecked")
    public CepikResult parse(Map<String, Object> vehicleData, Map<String, Object> timelineData, String vin) {
        Integer ownerCount = null;
        String deregisteredDate = null;
        String originCountry = null;

        if (vehicleData != null) {
            ownerCount = asInteger(vehicleData.get("liczbaWlascicieli"));
            if (ownerCount == null) ownerCount = asInteger(vehicleData.get("liczba-wlascicieli"));
            deregisteredDate = asString(vehicleData.get("dataWyrejestrowania"));
            originCountry = asString(vehicleData.get("krajPochodzenia"));
        }

        List<MileageStamp> mileageStamps = new ArrayList<>();
        List<DamageRecord> damageRecords = new ArrayList<>();

        if (timelineData != null) {
            Object events = timelineData.get("zdarzenia");
            if (events instanceof List<?> list) {
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> event)) continue;
                    String type = asString(((Map<String, Object>) event).get("typ"));
                    if ("BADANIE_TECHNICZNE".equals(type) || "badanieTechniczne".equals(type)) {
                        String date = asString(((Map<String, Object>) event).get("data"));
                        Integer km = asInteger(((Map<String, Object>) event).get("przebieg"));
                        if (date != null) mileageStamps.add(new MileageStamp(date, km));
                    }
                }
            }

            Object szkody = timelineData.get("szkodyIstotne");
            if (szkody instanceof List<?> damages) {
                for (Object item : damages) {
                    if (!(item instanceof Map<?, ?> damage)) continue;
                    String date = asString(((Map<String, Object>) damage).get("data"));
                    String desc = asString(((Map<String, Object>) damage).get("opis"));
                    if (date != null) damageRecords.add(new DamageRecord(date, desc));
                }
            }
        }

        return new CepikResult(
                CepikStatus.FOUND, vin, null, deregisteredDate, originCountry,
                ownerCount, mileageStamps, damageRecords, LOOKUP_URL, Instant.now()
        );
    }

    private String asString(Object value) {
        return value instanceof String s ? s : null;
    }

    private Integer asInteger(Object value) {
        if (value instanceof Integer i) return i;
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }
}
