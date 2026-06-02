package com.example.autoskaner_ai.analysis;

import java.time.Instant;
import java.util.List;

public record CepikResult(
        CepikStatus status,
        String vin,
        String firstRegistrationDatePl,
        String deregisteredDate,
        String originCountry,
        Integer ownerCount,
        List<MileageStamp> mileageStamps,
        List<DamageRecord> damageRecords,
        String lookupUrl,
        Instant fetchedAt
) {}
