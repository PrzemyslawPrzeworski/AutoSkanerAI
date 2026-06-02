package com.example.autoskaner_ai.cepik;

import com.example.autoskaner_ai.analysis.CepikResult;
import com.example.autoskaner_ai.analysis.CepikStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class HistoriaPojazduService {

    private static final Logger log = LoggerFactory.getLogger(HistoriaPojazduService.class);
    private static final String LOOKUP_URL = "https://historiapojazdu.gov.pl";

    private final HistoriaPojazduSession session;
    private final HistoriaPojazduParser parser;

    public HistoriaPojazduService(HistoriaPojazduSession session, HistoriaPojazduParser parser) {
        this.session = session;
        this.parser = parser;
    }

    public CepikResult lookup(String plate, String vin, String firstRegDate) {
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
