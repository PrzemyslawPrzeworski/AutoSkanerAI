package com.example.autoskaner_ai.cepik;

import com.example.autoskaner_ai.analysis.CepikResult;
import com.example.autoskaner_ai.analysis.CepikStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HistoriaPojazduParserTest {

    private final HistoriaPojazduParser parser = new HistoriaPojazduParser();

    @Test
    void mapsVehicleAndTimelineDataCorrectly() {
        Map<String, Object> vehicleData = Map.of(
                "liczbaWlascicieli", 3,
                "krajPochodzenia", "Niemcy"
        );

        Map<String, Object> timelineData = Map.of(
                "zdarzenia", List.of(
                        Map.of("typ", "BADANIE_TECHNICZNE", "data", "2022-05-10", "przebieg", 95000),
                        Map.of("typ", "BADANIE_TECHNICZNE", "data", "2020-04-15", "przebieg", 60000)
                ),
                "szkodyIstotne", List.of(
                        Map.of("data", "2019-08-01", "opis", "Uszkodzenie podwozia")
                )
        );

        CepikResult result = parser.parse(vehicleData, timelineData, "WBAAM31060GE12345");

        assertThat(result.status()).isEqualTo(CepikStatus.FOUND);
        assertThat(result.vin()).isEqualTo("WBAAM31060GE12345");
        assertThat(result.ownerCount()).isEqualTo(3);
        assertThat(result.originCountry()).isEqualTo("Niemcy");
        assertThat(result.mileageStamps()).hasSize(2);
        assertThat(result.mileageStamps().get(0).mileageKm()).isEqualTo(95000);
        assertThat(result.damageRecords()).hasSize(1);
        assertThat(result.damageRecords().get(0).description()).isEqualTo("Uszkodzenie podwozia");
        assertThat(result.lookupUrl()).isEqualTo("https://historiapojazdu.gov.pl");
        assertThat(result.fetchedAt()).isNotNull();
    }

    @Test
    void handlesNullFieldsWithoutThrowing() {
        CepikResult result = parser.parse(null, null, "WBAAM31060GE12345");

        assertThat(result.status()).isEqualTo(CepikStatus.FOUND);
        assertThat(result.ownerCount()).isNull();
        assertThat(result.mileageStamps()).isEmpty();
        assertThat(result.damageRecords()).isEmpty();
    }

    @Test
    void handlesEmptyEventListWithoutThrowing() {
        Map<String, Object> vehicleData = Map.of("liczbaWlascicieli", 1);
        Map<String, Object> timelineData = Map.of(
                "zdarzenia", List.of(),
                "szkodyIstotne", List.of()
        );

        CepikResult result = parser.parse(vehicleData, timelineData, "WBAAM31060GE12345");

        assertThat(result.mileageStamps()).isEmpty();
        assertThat(result.damageRecords()).isEmpty();
        assertThat(result.ownerCount()).isEqualTo(1);
    }
}
