package com.example.fast2smsmockgateway.dtos.common;

import java.time.Instant;

public record OtpChallenge(
        String id, String code, String payload, Instant issuedAt, Instant lastSentAt, Instant expiresAt,
        int sends, int failures, boolean verified) {
}
