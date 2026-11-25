package com.example.fast2smsmockgateway.service;

import com.example.fast2smsmockgateway.dtos.common.ApiCall;
import com.example.fast2smsmockgateway.dtos.request.GatewayRequest;
import com.example.fast2smsmockgateway.exception.GatewayException;
import com.example.fast2smsmockgateway.facade.GatewayFacade;
import com.example.fast2smsmockgateway.validation.Fields;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class VoiceService {
    private static final int PAGE_SIZE = 1000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT).withZone(Fields.IST);
    private final CallRecorder recorder;
    private final GatewayFacade facade;
    private final ResourceStore store;

    public VoiceService(CallRecorder recorder, GatewayFacade facade, ResourceStore store) {
        this.recorder = recorder;
        this.facade = facade;
        this.store = store;
    }

    @Transactional
    public Map<String, Object> files(String account) {
        recorder.audit(account, "Voice file list", Map.of());
        return Map.of("return", true, "files", List.of(
                file(30023, "Sandbox simple call", "welcome"),
                file(30031, "Sandbox key press", "menu"),
                file(30041, "Sandbox call patch", "menu")));
    }

    @Transactional
    public Map<String, Object> send(String account, GatewayRequest request) {
        Fields fields = new Fields(request.fields());
        for (String name : request.fields().keySet()) {
            if (request.fields().get(name) instanceof List<?> || request.fields().get(name) instanceof Map<?, ?>) {
                throw invalid(name + " must be a single value");
            }
        }
        String idempotencyKey = fields.get("idempotency_key");
        if (idempotencyKey.length() > 128) {
            throw invalid("idempotency_key must be at most 128 characters");
        }
        if (!idempotencyKey.isBlank()) {
            var previous = store.reserve(account, "voice-request", "", idempotencyKey);
            if (!previous.isEmpty()) {
                return previous;
            }
        }
        fileId(fields, "file_id");
        campaignType(fields);
        String raw = fields.get("numbers").strip();
        List<String> submitted = raw.isBlank() ? List.of() : Arrays.asList(raw.split("[,;\\s]+"));
        if (submitted.size() > 100_000) {
            throw invalid("Use at most 100000 numbers per campaign");
        }
        List<String> recipients = submitted.stream().map(this::normalizeMobile)
                .filter(mobile -> mobile.matches("[6-9][0-9]{9}")).distinct().toList();
        if (recipients.isEmpty()) {
            throw invalid("Provide at least one valid mobile number");
        }
        Instant now = Instant.now();
        Instant due = schedule(fields.get("schedule_time"), now);
        Map<String, Object> payload = new LinkedHashMap<>(request.fields());
        payload.put("schedule_time", TIME.format(due));
        if (fields.get("campaign_name").isBlank()) {
            payload.put("campaign_name", "API " + TIME.format(now));
        }
        String id = recorder.enqueue(account, "VOICE", "Voice campaign", payload, recipients, due).requestId();
        Map<String, Object> response = Map.of("return", true, "request_id", id, "numbers", recipients.size(),
                "removed", submitted.size() - recipients.size(), "dnd", 0, "amount_held", 0,
                "message", List.of(due.isAfter(now) ? "Voice campaign scheduled for " + TIME.format(due) + "."
                        : "Voice campaign accepted. Calls start within a minute."));
        if (!idempotencyKey.isBlank()) {
            store.save(account, "voice-request", "", idempotencyKey, response);
        }
        return response;
    }

    @Transactional
    public Map<String, Object> report(String account, GatewayRequest request) {
        Fields fields = new Fields(request.fields());
        String id = fields.require("request_id");
        int includeNumbers = fields.integer("numbers", 0, 0, 1);
        int page = fields.integer("page", 1, 1, Integer.MAX_VALUE);
        ApiCall call = facade.findCampaign(id, account, "VOICE").stream().findFirst()
                .orElseThrow(() -> GatewayException.missing("Invalid request_id."));
        Fields payload = new Fields(recorder.decode(call.payload()));
        List<String> recipients = Arrays.asList(call.recipients().split(","));
        boolean sent = "SENT".equals(call.status());
        boolean failed = "FAILED".equals(call.status());
        boolean completed = sent || failed;
        boolean scheduled = LocalDateTime.parse(payload.get("schedule_time"), TIME).atZone(Fields.IST)
                .toInstant().isAfter(Instant.now());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("return", true);
        result.put("request_id", id);
        result.put("campaign_name", payload.get("campaign_name"));
        result.put("type", campaignType(payload));
        result.put("status", sent ? "completed" : failed ? "failed" : scheduled ? "scheduled" : "submitting");
        result.put("schedule_time", payload.get("schedule_time"));
        result.put("numbers", recipients.size());
        result.put("dialled", sent ? recipients.size() : 0);
        result.put("answered", sent ? recipients.size() : 0);
        result.put("pulses", 0);
        result.put("amount_held", 0);
        result.put("amount_charged", completed ? 0 : null);
        result.put("amount_refunded", completed ? 0 : null);
        result.put("completed", completed);
        if (includeNumbers == 1) {
            int start = (int) Math.min(recipients.size(), (long) (page - 1) * PAGE_SIZE);
            int end = Math.min(recipients.size(), start + PAGE_SIZE);
            List<Map<String, Object>> data = new ArrayList<>();
            for (String mobile : recipients.subList(start, end)) {
                data.add(numberResult(call, mobile, sent, failed));
            }
            result.put("page", page);
            result.put("last_page", Math.ceilDiv(recipients.size(), PAGE_SIZE));
            result.put("data", data);
        }
        recorder.audit(account, "Voice campaign report", request.fields());
        return result;
    }

    private Map<String, Object> numberResult(ApiCall call, String mobile, boolean sent, boolean failed) {
        String time = sent ? TIME.format(call.sentAt()) : null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mobile", mobile);
        result.put("status", sent ? "answered" : failed ? "failed" : "queued");
        result.put("duration", 0);
        result.put("agent_duration", 0);
        result.put("pulses", 0);
        result.put("charge", 0);
        result.put("dtmf", null);
        result.put("agent_number", null);
        result.put("start_time", time);
        result.put("end_time", time);
        result.put("reason", sent ? "Sandbox email accepted by SMTP"
                : failed ? "Sandbox email failed" : "Sandbox email queued");
        return result;
    }

    private Map<String, Object> file(int id, String name, String role) {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("file_id", id);
        file.put("name", name);
        file.put("used_as", role);
        file.put("duration", 15);
        file.put("status", "approved");
        file.put("remarks", null);
        return file;
    }

    private String campaignType(Fields fields) {
        String groups = fields.get("agent_groups");
        if (!groups.isBlank()) {
            validateKeys(groups, true);
            fileId(fields, "no_agent_file_id");
            return "callpatch";
        }
        String keys = fields.get("dtmf_keys");
        if (!keys.isBlank()) {
            validateKeys(keys, false);
            return "dtmf";
        }
        return "simple";
    }

    private void validateKeys(String value, boolean groups) {
        String[] entries = value.split(",", -1);
        String pattern = groups ? "[0-9]:[1-9][0-9]*" : "[0-9]";
        if (Arrays.stream(entries).map(String::trim).anyMatch(entry -> !entry.matches(pattern))
                || Arrays.stream(entries).map(String::trim).map(entry -> entry.charAt(0)).distinct().count()
                != entries.length) {
            throw invalid("Invalid " + (groups ? "agent_groups" : "dtmf_keys"));
        }
    }

    private void fileId(Fields fields, String field) {
        if (!fields.get(field).matches("[1-9][0-9]*")) {
            throw invalid(field + " must be an approved voice file ID");
        }
    }

    private Instant schedule(String value, Instant now) {
        if (value.isBlank()) {
            return now;
        }
        try {
            LocalDateTime date = LocalDateTime.parse(value, TIME);
            Instant due = date.atZone(Fields.IST).toInstant();
            if (due.isAfter(now) && due.isBefore(Instant.parse("2038-01-19T03:14:07Z"))
                    && !date.toLocalTime().isBefore(LocalTime.of(10, 0))
                    && date.toLocalTime().isBefore(LocalTime.of(21, 0))) {
                return due;
            }
        } catch (DateTimeParseException ignored) {
            // The same error covers invalid syntax and invalid calling times.
        }
        throw invalid("schedule_time must be a future YYYY-MM-DD HH:MM:SS in IST, between 10 AM and 9 PM");
    }

    private String normalizeMobile(String mobile) {
        return mobile.replaceFirst("^(?:\\+?91|0)(?=[0-9]{10}$)", "");
    }

    private GatewayException invalid(String message) {
        return new GatewayException(HttpStatus.UNPROCESSABLE_CONTENT, 422, message);
    }
}
