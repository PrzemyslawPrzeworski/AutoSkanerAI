package com.example.autoskaner_ai.cepik;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest
@ActiveProfiles("mock")
@Tag("live-llm")
class CepikApiServiceLiveTest {

    @Autowired
    CepikApiService cepikApiService;

    @Test
    void noExceptionThrownRegardlessOfApiAvailability() {
        assertThatCode(() -> {
            Optional<String> result = cepikApiService.lookupFirstRegistrationDate("WBAAM31060GE12345");
            System.out.println("CEPiK result: " + result);
        }).doesNotThrowAnyException();
    }
}
