package com.example.fast2smsmockgateway.dtos.common;

import java.time.Instant;

public record ApiCall(
        String id, String requestId, String account, String channel, String operation, String payload,
        String recipients, String emailFrom, String emailTo, Instant createdAt,
        Instant dueAt, String status, int attempts, String lease, Instant sentAt, String failure) {
}
