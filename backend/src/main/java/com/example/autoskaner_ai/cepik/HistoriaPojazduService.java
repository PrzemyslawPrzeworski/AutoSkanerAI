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

@Service
@Profile("!mock")
public class HistoriaPojazduService {

    private static final Logger log = LoggerFactory.getLogger(HistoriaPojazduService.class);
    private static final String LOOKUP_URL = "https://historiapojazdu.gov.pl";

    // historiapojazdu answers "this plate/VIN/date triple matches no vehicle" with a
    // 404 carrying HIPO-0002. That is a definitive negative answer from the registry,
    // not an outage, and the UI renders the two cases differently — so they must not
    // both collapse into LOOKUP_FAILED.
    private static final String NOT_FOUND_CODE = "HIPO-0002";

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
            if (indicatesVehicleNotFound(e)) {
                log.info("historiapojazdu has no vehicle for the supplied plate/VIN/first-registration-date triple");
                return result(CepikStatus.NOT_FOUND, vin);
            }
            log.warn("historiapojazdu lookup failed: {}", e.getMessage());
            return result(CepikStatus.LOOKUP_FAILED, vin);
        } finally {
            session.close();
        }
    }

    private boolean indicatesVehicleNotFound(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            String message = cur.getMessage();
            if (message != null && message.contains(NOT_FOUND_CODE)) {
                return true;
            }
            if (cur.getCause() == cur) {
                break;
            }
        }
        return false;
    }

    // mileageStamps and damageRecords stay null for every non-FOUND status. An empty
    // damage list reads as "checked, nothing found", which is the one thing the product
    // must never imply when it does not actually know.
    private CepikResult result(CepikStatus status, String vin) {
        return new CepikResult(
                status, vin, null, null, null,
                null, null, null, LOOKUP_URL, Instant.now()
        );
    }
}
