package com.example.autoskaner_ai.cepik;

import com.example.autoskaner_ai.analysis.CepikStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

// Reaching historiapojazdu.gov.pl needs a JVM that trusts the local TLS chain. Behind a
// TLS-intercepting proxy, run with -DargLine="-Djavax.net.ssl.trustStoreType=Windows-ROOT".
// Without it the handshake fails and this test fails loudly rather than passing vacuously.
@SpringBootTest
@ActiveProfiles("openrouter")
@Tag("live-llm")
class HistoriaPojazduServiceLiveTest {

    @Autowired
    HistoriaPojazduService historiaPojazduService;

    // A syntactically valid but deliberately unregistered triple. The registry must answer
    // definitively (NOT_FOUND) rather than degrade to LOOKUP_FAILED — LOOKUP_FAILED here
    // means the session/scrape contract broke and is a real regression.
    @Test
    void unregisteredTripleIsReportedAsNotFoundNotAsFailure() {
        var result = historiaPojazduService.lookup("WA12345", "WBAAM31060GE12345", "2018-03-15");

        assertThat(result.status())
                .as("LOOKUP_FAILED means the moj.gov.pl session or scrape contract broke; "
                        + "check the logged cause before assuming a network blip")
                .isEqualTo(CepikStatus.NOT_FOUND);
        assertThat(result.lookupUrl()).isEqualTo("https://historiapojazdu.gov.pl");
        assertThat(result.fetchedAt()).isNotNull();
        assertThat(result.damageRecords())
                .as("non-FOUND results must not carry an empty damage list — it reads as 'no damage'")
                .isNull();
        assertThat(result.mileageStamps()).isNull();
    }
}
