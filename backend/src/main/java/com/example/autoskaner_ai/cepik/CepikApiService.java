package com.example.autoskaner_ai.cepik;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@Profile("!mock")
public class CepikApiService {

    private static final Logger log = LoggerFactory.getLogger(CepikApiService.class);

    private static final List<String> VOIVODESHIPS = List.of(
            "02", "04", "06", "08", "10", "12", "14", "16",
            "18", "20", "22", "24", "26", "28", "30", "32"
    );

    private final RestClient restClient;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public CepikApiService(@Qualifier("cepikApiBuilder") RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    public Optional<String> lookupFirstRegistrationDate(String normalisedVin) {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        List<CompletableFuture<Optional<String>>> futures = VOIVODESHIPS.stream()
                .map(code -> CompletableFuture.supplyAsync(
                        () -> queryVoivodeship(code, normalisedVin, today), executor))
                .toList();

        try {
            List<CompletableFuture<Optional<String>>> remaining = new ArrayList<>(futures);
            long deadline = System.currentTimeMillis() + 12_000;
            while (!remaining.isEmpty()) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    log.warn("CEPiK API scan timed out after 12s; cancelling remaining futures");
                    futures.forEach(f -> f.cancel(true));
                    return Optional.empty();
                }
                CompletableFuture.anyOf(remaining.toArray(CompletableFuture[]::new))
                        .get(left, TimeUnit.MILLISECONDS);

                for (CompletableFuture<Optional<String>> f : remaining) {
                    if (f.isDone()) {
                        Optional<String> result = f.get();
                        if (result.isPresent()) {
                            futures.forEach(fut -> fut.cancel(true));
                            return result;
                        }
                    }
                }
                remaining.removeIf(CompletableFuture::isDone);
            }
        } catch (TimeoutException e) {
            log.warn("CEPiK API scan timed out after 12s");
            futures.forEach(f -> f.cancel(true));
        } catch (Exception e) {
            log.warn("CEPiK API scan interrupted: {}", e.getMessage());
            futures.forEach(f -> f.cancel(true));
        }
        return Optional.empty();
    }

    private Optional<String> queryVoivodeship(String code, String vin, String today) {
        try {
            String uri = "/pojazdy?wojewodztwo={code}&data-od=19800101&data-do={today}" +
                    "&typ-daty=2&tylko-zarejestrowane=false&pokaz-wszystkie-pola=true" +
                    "&filter[numer-vin]={vin}&limit=1";

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                    .uri(uri, code, today, vin)
                    .retrieve()
                    .body(Map.class);

            if (response == null) return Optional.empty();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
            if (data == null || data.isEmpty()) return Optional.empty();

            @SuppressWarnings("unchecked")
            Map<String, Object> attrs = (Map<String, Object>) data.get(0).get("attributes");
            if (attrs == null) return Optional.empty();

            Object raw = attrs.get("data-pierwszej-rejestracjiwkraju");
            if (raw == null) return Optional.empty();

            String yyyymmdd = raw.toString();
            if (yyyymmdd.length() == 8) {
                return Optional.of(yyyymmdd.substring(0, 4) + "-" +
                        yyyymmdd.substring(4, 6) + "-" + yyyymmdd.substring(6, 8));
            }
            return Optional.of(yyyymmdd);
        } catch (Exception e) {
            log.debug("CEPiK voivodeship {} query failed: {}", code, e.getMessage());
            return Optional.empty();
        }
    }
}
