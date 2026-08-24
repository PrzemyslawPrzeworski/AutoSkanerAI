package com.example.autoskaner_ai.market;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Map;
import java.util.Optional;

@Component
public class OtomotoSlugMapper {

    private static final Map<String, String> MAKE_SLUGS = Map.ofEntries(
            Map.entry("ALFA ROMEO", "alfa-romeo"),
            Map.entry("AUDI", "audi"),
            Map.entry("BMW", "bmw"),
            Map.entry("CHEVROLET", "chevrolet"),
            Map.entry("CHRYSLER", "chrysler"),
            Map.entry("CITROEN", "citroen"),
            Map.entry("CITROËN", "citroen"),
            Map.entry("DACIA", "dacia"),
            Map.entry("DAEWOO", "daewoo"),
            Map.entry("FIAT", "fiat"),
            Map.entry("FORD", "ford"),
            Map.entry("HONDA", "honda"),
            Map.entry("HYUNDAI", "hyundai"),
            Map.entry("INFINITI", "infiniti"),
            Map.entry("JAGUAR", "jaguar"),
            Map.entry("JEEP", "jeep"),
            Map.entry("KIA", "kia"),
            Map.entry("LAND ROVER", "land-rover"),
            Map.entry("LEXUS", "lexus"),
            Map.entry("MAZDA", "mazda"),
            Map.entry("MERCEDES", "mercedes-benz"),
            Map.entry("MERCEDES-BENZ", "mercedes-benz"),
            Map.entry("MERC", "mercedes-benz"),
            Map.entry("MINI", "mini"),
            Map.entry("MITSUBISHI", "mitsubishi"),
            Map.entry("NISSAN", "nissan"),
            Map.entry("OPEL", "opel"),
            Map.entry("PEUGEOT", "peugeot"),
            Map.entry("PORSCHE", "porsche"),
            Map.entry("RENAULT", "renault"),
            Map.entry("SEAT", "seat"),
            Map.entry("SKODA", "skoda"),
            Map.entry("ŠKODA", "skoda"),
            Map.entry("SUBARU", "subaru"),
            Map.entry("SUZUKI", "suzuki"),
            Map.entry("TESLA", "tesla"),
            Map.entry("TOYOTA", "toyota"),
            Map.entry("VOLKSWAGEN", "volkswagen"),
            Map.entry("VW", "volkswagen"),
            Map.entry("VOLVO", "volvo")
    );

    public Optional<String> makeSlug(String make) {
        if (make == null || make.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(MAKE_SLUGS.get(make.trim().toUpperCase()));
    }

    public String modelSlug(String model) {
        if (model == null || model.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(model.trim(), Normalizer.Form.NFKD);
        // Strip combining diacritical marks
        normalized = normalized.replaceAll("\\p{InCombiningDiacriticalMarks}", "");
        return normalized
                .toLowerCase()
                .replaceAll("\\s+", "-")
                .replaceAll("[^a-z0-9-]", "");
    }
}
