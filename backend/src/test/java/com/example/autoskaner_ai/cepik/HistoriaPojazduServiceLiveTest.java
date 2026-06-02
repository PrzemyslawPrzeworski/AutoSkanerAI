package com.example.autoskaner_ai.cepik;

import com.example.autoskaner_ai.analysis.CepikStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest
@ActiveProfiles("mock")
@Tag("live-llm")
class HistoriaPojazduServiceLiveTest {

    @Autowired
    HistoriaPojazduService historiaPojazduService;

    @Test
    void noExceptionThrownAndStatusIsFoundOrFailed() {
        assertThatCode(() -> {
            var result = historiaPojazduService.lookup("WA12345", "WBAAM31060GE12345", "2018-03-15");
            System.out.println("HistoriaPojazdu result status: " + result.status());
            assertThat(result.status()).isIn(CepikStatus.FOUND, CepikStatus.LOOKUP_FAILED, CepikStatus.NOT_FOUND);
            assertThat(result.lookupUrl()).isEqualTo("https://historiapojazdu.gov.pl");
            assertThat(result.fetchedAt()).isNotNull();
        }).doesNotThrowAnyException();
    }
}
