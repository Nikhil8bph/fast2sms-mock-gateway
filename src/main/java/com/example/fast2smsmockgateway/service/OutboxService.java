package com.example.fast2smsmockgateway.service;

import com.example.fast2smsmockgateway.dtos.common.ApiCall;
import com.example.fast2smsmockgateway.facade.GatewayFacade;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class OutboxService {
    private static final int MAX_ATTEMPTS = 5;
    private final GatewayFacade facade;

    public OutboxService(GatewayFacade facade) {
        this.facade = facade;
    }

    public List<String> readyIds() {
        return facade.readyIds(Instant.now());
    }

    @Transactional
    public Optional<ApiCall> claim(String id) {
        Instant now = Instant.now();
        Optional<ApiCall> candidate = facade.lockCall(id)
                .filter(call -> "PENDING".equals(call.status()) || "SENDING".equals(call.status()))
                .filter(call -> !call.dueAt().isAfter(now));
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        ApiCall call = candidate.get();
        if (call.attempts() >= MAX_ATTEMPTS) {
            facade.update(state(call, "FAILED", now, call.attempts(), null, null, "SMTP outcome unknown"));
            return Optional.empty();
        }
        return Optional.of(facade.update(state(call, "SENDING", now.plus(5, ChronoUnit.MINUTES),
                call.attempts() + 1, UUID.randomUUID().toString(), null, call.failure())));
    }

    @Transactional
    public void finish(ApiCall attempt, String failure) {
        facade.lockCall(attempt.id()).filter(call -> attempt.lease().equals(call.lease())).ifPresent(call -> {
            Instant now = Instant.now();
            if (failure == null) {
                facade.update(state(call, "SENT", now, call.attempts(), null, now, null));
                return;
            }
            String status = call.attempts() >= MAX_ATTEMPTS ? "FAILED" : "PENDING";
            Instant retryAt = now.plusSeconds(15L << (call.attempts() - 1));
            facade.update(state(call, status, retryAt, call.attempts(), null, null, failure));
        });
    }

    private ApiCall state(ApiCall call, String status, Instant dueAt, int attempts, String lease,
            Instant sentAt, String failure) {
        return new ApiCall(call.id(), call.requestId(), call.account(), call.channel(), call.operation(), call.payload(),
                call.recipients(), call.emailFrom(), call.emailTo(), call.createdAt(), dueAt,
                status, attempts, lease, sentAt, failure);
    }
}
