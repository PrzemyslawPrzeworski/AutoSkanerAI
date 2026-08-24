package com.example.autoskaner_ai.cepik;

import com.example.autoskaner_ai.analysis.CepikResult;
import com.example.autoskaner_ai.analysis.CepikStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

@Service
@Profile("!mock")
public class HistoriaPojazduService {

    private static final Logger log = LoggerFactory.getLogger(HistoriaPojazduService.class);
    private static final String LOOKUP_URL = "https://historiapojazdu.gov.pl";

    private final RestClient.Builder builder;
    private final HistoriaPojazduParser parser;

    public HistoriaPojazduService(@Qualifier("historiaPojazduBuilder") RestClient.Builder builder,
                                   HistoriaPojazduParser parser) {
        this.builder = builder;
        this.parser = parser;
    }

    HistoriaPojazduSession createSession() {
        return new HistoriaPojazduSession(builder);
    }

    public CepikResult lookup(String plate, String vin, String firstRegDate) {
        var session = createSession();
        try {
            session.open();
            var vehicleData = session.fetchVehicleData(plate, vin, firstRegDate);
            var timelineData = session.fetchTimelineData(plate, vin, firstRegDate);
            return parser.parse(vehicleData, timelineData, vin);
        } catch (Exception e) {
            log.warn("historiapojazdu lookup failed: {}", e.getMessage());
            return failedResult(vin);
        } finally {
            session.close();
        }
    }

    private CepikResult failedResult(String vin) {
        return new CepikResult(
                CepikStatus.LOOKUP_FAILED, vin, null, null, null,
                null, List.of(), List.of(), LOOKUP_URL, Instant.now()
        );
    }
}
