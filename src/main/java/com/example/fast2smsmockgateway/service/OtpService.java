package com.example.fast2smsmockgateway.service;

import com.example.fast2smsmockgateway.config.SandboxProperties;
import com.example.fast2smsmockgateway.dtos.common.ApiCall;
import com.example.fast2smsmockgateway.dtos.common.OtpChallenge;
import com.example.fast2smsmockgateway.dtos.request.GatewayRequest;
import com.example.fast2smsmockgateway.exception.GatewayException;
import com.example.fast2smsmockgateway.facade.GatewayFacade;
import com.example.fast2smsmockgateway.validation.Fields;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OtpService {
    private static final int MAX_SENDS = 5;
    private static final String INVALIDATED = "OTP not found or already verified";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final GatewayFacade facade;
    private final CallRecorder recorder;
    private final SandboxProperties properties;

    public OtpService(GatewayFacade facade, CallRecorder recorder, SandboxProperties properties) {
        this.facade = facade;
        this.recorder = recorder;
        this.properties = properties;
    }

    @Transactional
    public Map<String, Object> send(String account, GatewayRequest request) {
        Fields fields = new Fields(request.fields());
        String mobile = mobile(fields);
        String id = account + ":" + mobile;
        String otpId = fields.require("otp_id");
        String code = fields.get("otp");
        if (code.isBlank()) {
            code = generate(otpLength(fields));
        } else if (code.length() > 10) {
            throw GatewayException.badRequest(400, "otp must contain at most 10 characters");
        }
        var previous = facade.lockChallenge(id);
        Instant now = Instant.now();
        previous.ifPresent(challenge -> checkSendCooldown(challenge, account, mobile, otpId, now));
        Instant expiry = expiry(fields, now);
        Map<String, Object> payload = new LinkedHashMap<>(request.fields());
        payload.put("mobile", mobile);
        payload.put("otp", code);
        facade.saveChallenge(new OtpChallenge(id, code, recorder.encode(payload), now, now, expiry, 1, 0, false),
                previous.isEmpty());
        return sendEmail(account, mobile, payload, now, "OTP send");
    }

    @Transactional
    public Map<String, Object> resend(String account, GatewayRequest request) {
        String mobile = mobile(new Fields(request.fields()));
        OtpChallenge current = facade.lockChallenge(account + ":" + mobile)
                .orElseThrow(() -> GatewayException.missing("No previous OTP request found for this mobile"));
        Instant now = Instant.now();
        if (current.verified()) {
            throw GatewayException.badRequest(400, "OTP already verified. Please request a new OTP.");
        }
        if (!current.lastSentAt().plusSeconds(600).isAfter(now)) {
            throw GatewayException.badRequest(400, "OTP resend window (10 minutes) has expired. Please request a new OTP.");
        }
        if (current.sends() >= MAX_SENDS) {
            throw GatewayException.badRequest(400, "Maximum resend limit (5 times) reached. Please try again later.");
        }
        if (current.failures() >= properties.otpVerifyAttempts()) {
            throw GatewayException.missing(INVALIDATED);
        }
        checkCooldown(current.lastSentAt(), now);
        Map<String, Object> payload = recorder.decode(current.payload());
        facade.saveChallenge(new OtpChallenge(current.id(), current.code(), current.payload(), current.issuedAt(), now,
                expiry(new Fields(payload), now), current.sends() + 1, current.failures(), false), false);
        return sendEmail(account, mobile, payload, now, "OTP resend");
    }

    @Transactional(noRollbackFor = GatewayException.class)
    public Map<String, Object> verify(String account, GatewayRequest request) {
        Fields fields = new Fields(request.fields());
        String mobile = mobile(fields);
        String code = fields.require("otp");
        OtpChallenge current = facade.lockChallenge(account + ":" + mobile)
                .filter(challenge -> !challenge.verified() && challenge.failures() < properties.otpVerifyAttempts())
                .orElseThrow(() -> GatewayException.missing(INVALIDATED));
        if (!current.expiresAt().isAfter(Instant.now())) {
            throw GatewayException.badRequest(400, "OTP expired");
        }
        boolean matches = current.code().equals(code);
        int failures = current.failures() + (matches ? 0 : 1);
        facade.saveChallenge(new OtpChallenge(current.id(), current.code(), current.payload(), current.issuedAt(),
                current.lastSentAt(), current.expiresAt(), current.sends(), failures, matches), false);
        recorder.audit(account, "OTP verify", Map.of("mobile", mobile, "verified", matches));
        if (!matches) {
            throw GatewayException.badRequest(400, failures >= properties.otpVerifyAttempts()
                    ? "Maximum verification attempts reached. OTP is no longer valid." : "Invalid OTP");
        }
        return Map.of("return", true, "status_code", 200, "message", "OTP verified successfully");
    }

    private void checkSendCooldown(OtpChallenge current, String account, String mobile, String otpId, Instant now) {
        if (properties.otpResendWaitSeconds() == 0) {
            return;
        }
        if (otpId.equals(templateId(current.payload()))) {
            checkCooldown(current.lastSentAt(), now);
            return;
        }
        Instant since = now.minusSeconds(properties.otpResendWaitSeconds());
        facade.recentOtpSends(account, mobile, since).stream()
                .filter(call -> otpId.equals(templateId(call.payload())))
                .map(ApiCall::createdAt).findFirst().ifPresent(lastSentAt -> checkCooldown(lastSentAt, now));
    }

    private String templateId(String payload) {
        return new Fields(recorder.decode(payload)).get("otp_id");
    }

    private void checkCooldown(Instant lastSentAt, Instant now) {
        if (properties.otpResendWaitSeconds() == 0) {
            return;
        }
        Instant next = lastSentAt.plusSeconds(properties.otpResendWaitSeconds());
        if (next.isAfter(now)) {
            long seconds = Math.ceilDiv(Duration.between(now, next).toMillis(), 1000);
            throw GatewayException.badRequest(400, "Please wait " + Math.max(1, seconds)
                    + " seconds before requesting a new OTP");
        }
    }

    private Instant expiry(Fields fields, Instant now) {
        return now.plus(Duration.ofMinutes(fields.integer("otp_expiry", 15, 1, 10080)));
    }

    private int otpLength(Fields fields) {
        try {
            return fields.integer("otp_length", 6, 4, 10);
        } catch (GatewayException ignored) {
            return 6;
        }
    }

    private Map<String, Object> sendEmail(String account, String mobile, Map<String, Object> payload,
            Instant now, String operation) {
        String id = recorder.enqueue(account, "OTP", operation, payload, List.of(mobile), now).requestId();
        return Map.of("return", true, "status_code", 200, "request_id", id, "message", "OTP sent successfully");
    }

    private String mobile(Fields fields) {
        return fields.indianMobile(fields.require("mobile"));
    }

    private String generate(int length) {
        StringBuilder code = new StringBuilder(length);
        for (int digit = 0; digit < length; digit++) {
            code.append(RANDOM.nextInt(10));
        }
        return code.toString();
    }
}
