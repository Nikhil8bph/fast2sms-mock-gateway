package com.example.fast2smsmockgateway.service;

import com.example.fast2smsmockgateway.config.SandboxProperties;
import com.example.fast2smsmockgateway.dtos.common.ApiCall;
import com.example.fast2smsmockgateway.dtos.request.GatewayRequest;
import com.example.fast2smsmockgateway.exception.GatewayException;
import com.example.fast2smsmockgateway.util.SmsParts;
import com.example.fast2smsmockgateway.facade.GatewayFacade;
import com.example.fast2smsmockgateway.validation.Fields;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReportService {
    private static final DateTimeFormatter REPORT_TIME =
            DateTimeFormatter.ofPattern("dd-MM-uuuu hh:mm:ss a", java.util.Locale.ENGLISH).withZone(Fields.IST);
    private final GatewayFacade facade;
    private final CallRecorder recorder;
    private final SandboxProperties properties;

    public ReportService(GatewayFacade facade, CallRecorder recorder, SandboxProperties properties) {
        this.facade = facade;
        this.recorder = recorder;
        this.properties = properties;
    }

    @Transactional
    public Map<String, Object> wallet(String account) {
        recorder.audit(account, "Wallet balance", Map.of());
        BigDecimal wallet = properties.walletBalance();
        return Map.of("return", true, "wallet", wallet.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                "sms_count", wallet.divideToIntegralValue(new BigDecimal("0.25")));
    }

    @Transactional
    public Map<String, Object> delivery(String account, String channel, String requestId) {
        boolean sms = channel.equals("SMS");
        String id = !sms && requestId.startsWith("wamid.") ? requestId.substring(6).split("\\.", 2)[0] : requestId;
        Instant cutoff = Instant.now().minus(sms ? 7 : 90, ChronoUnit.DAYS);
        List<ApiCall> calls = facade.findCampaign(id, account, channel).stream()
                .filter(item -> item.createdAt().isAfter(cutoff)).toList();
        if (calls.isEmpty()) {
            throw GatewayException.missing("No DLR found for the given request_id.");
        }
        recorder.audit(account, channel + " delivery report", Map.of("request_id", requestId));
        return Map.of("success", true, "message", "DLR found successfully.", "data",
                sms ? List.of(smsRow(calls)) : whatsappRows(calls.getFirst()));
    }

    @Transactional
    public Map<String, Object> analytics(String account, String channel, boolean summary, GatewayRequest request) {
        Fields fields = new Fields(request.fields());
        LocalDate from = date(fields, "from");
        LocalDate to = date(fields, "to");
        int maxDays = summary ? 30 : 3;
        if (to.isBefore(from) || to.isAfter(LocalDate.now(Fields.IST))
                || from.isBefore(LocalDate.now(Fields.IST).minusYears(1))
                || ChronoUnit.DAYS.between(from, to) >= maxDays) {
            throw new GatewayException(HttpStatus.UNPROCESSABLE_CONTENT, 422, "Invalid analytics date range");
        }

        boolean sms = channel.equals("SMS");
        String name = sms ? "SMS" : "WhatsApp";
        List<ApiCall> calls = facade.findCalls(account, channel, from.atStartOfDay(Fields.IST).toInstant(),
                to.plusDays(1).atStartOfDay(Fields.IST).toInstant());
        if (summary) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            List<String> statuses = sms ? List.of("sent", "delivered", "pending", "failed", "blocked", "error")
                    : List.of("sent", "accepted", "delivered", "read", "failed", "rejected", "pending");
            statuses.forEach(status -> counts.put(status, 0));
            for (ApiCall call : calls) {
                counts.merge(status(call), call.recipients().split(",").length, Integer::sum);
            }
            recorder.audit(account, name + " summary", request.fields());
            return Map.of("success", true, "message", name + " Summary fetched successfully.", "data", counts);
        }

        int limit = fields.integer("limit", 100, 1, 1000);
        int page = fields.integer("page", 1, 1, Integer.MAX_VALUE);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (sms) {
            Map<String, List<ApiCall>> campaigns = new LinkedHashMap<>();
            calls.forEach(call -> campaigns.computeIfAbsent(call.requestId(), ignored -> new ArrayList<>()).add(call));
            campaigns.values().forEach(campaign -> rows.add(smsRow(campaign)));
        } else {
            calls.forEach(call -> rows.addAll(whatsappRows(call)));
        }
        int start = (int) Math.min(rows.size(), (long) (page - 1) * limit);
        int end = (int) Math.min(rows.size(), (long) start + limit);
        recorder.audit(account, name + " logs", request.fields());
        return Map.of("success", true, "message", name + " Logs generated successfully.",
                "data", rows.subList(start, end), "pagination", Map.of("page", page, "limit", limit,
                        "total", rows.size(), "total_pages", Math.ceilDiv(rows.size(), limit)));
    }

    private LocalDate date(Fields fields, String name) {
        try {
            return LocalDate.parse(fields.require(name));
        } catch (DateTimeParseException exception) {
            throw GatewayException.badRequest(400, name + " must use YYYY-MM-DD");
        }
    }

    private Map<String, Object> smsRow(List<ApiCall> calls) {
        ApiCall first = calls.getFirst();
        List<Map<String, Object>> delivery = new ArrayList<>();
        for (ApiCall call : calls) {
            Fields fields = new Fields(recorder.decode(call.payload()));
            if ("SMS batch".equals(call.operation())) {
                for (Fields item : fields.objects("requests")) {
                    for (String number : item.smsNumbers().accepted()) {
                        delivery.add(smsRecipient(call, fields.get("route"), item, number));
                    }
                }
            } else {
                for (String number : call.recipients().split(",")) {
                    delivery.add(smsRecipient(call, fields.get("route"), fields, number));
                }
            }
        }
        return Map.of("request_id", first.requestId(), "route",
                recorder.decode(first.payload()).get("route"), "delivery_status", delivery);
    }

    private Map<String, Object> smsRecipient(ApiCall call, String route, Fields fields, String number) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("mobile", Long.parseLong(number));
        row.put("sender_id", fields.get("sender_id"));
        String status = status(call);
        row.put("status", Character.toUpperCase(status.charAt(0)) + status.substring(1));
        row.put("status_description", description(call));
        SmsParts parts = SmsParts.measure(SmsParts.text(route, fields));
        row.put("sms_count", parts.count());
        row.put("sms_language", parts.language());
        row.put("character_count", parts.characters());
        row.put("dlr_attempt", call.attempts());
        row.put("amount_debited", "0.0000");
        addTracking(row, fields);
        if (call.sentAt() != null) {
            row.put("sent_timestamp", call.sentAt().getEpochSecond());
            row.put("sent_time", REPORT_TIME.format(call.sentAt()));
        }
        return row;
    }

    private List<Map<String, Object>> whatsappRows(ApiCall call) {
        Fields fields = new Fields(recorder.decode(call.payload()));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String number : call.recipients().split(",")) {
            String recipient = "WhatsApp Meta".equals(call.operation()) ? number : "91" + number;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", "status_update");
            row.put("fast2sms_request_id", call.id());
            row.put("request_id", "wamid." + call.id() + (call.recipients().contains(",") ? "." + number : ""));
            row.put("phone_number_id", fields.get("phone_number_id"));
            row.put("recipient_id", recipient);
            row.put("mobile", Long.parseLong(recipient));
            row.put("status", status(call));
            row.put("status_description", description(call));
            row.put("errors", "FAILED".equals(call.status()) ? List.of(call.failure()) : null);
            row.put("amount_debited", "0.0000");
            String category = fields.get("category");
            row.put("category", category.isBlank() ? "WhatsApp session".equals(call.operation()) ? "SERVICE" : "UTILITY"
                    : category.toUpperCase(java.util.Locale.ROOT));
            addTracking(row, fields);
            if (call.sentAt() != null) {
                row.put("sent_time", REPORT_TIME.format(call.sentAt()));
            }
            rows.add(row);
        }
        return rows;
    }

    private void addTracking(Map<String, Object> row, Fields fields) {
        for (String field : List.of("udf1", "udf2", "udf3")) {
            if (fields.values().containsKey(field)) {
                row.put(field, fields.get(field));
            }
        }
    }

    private String status(ApiCall call) {
        return switch (call.status()) {
            case "SENT" -> "sent";
            case "FAILED" -> "failed";
            default -> "pending";
        };
    }

    private String description(ApiCall call) {
        return switch (call.status()) {
            case "SENT" -> "Sandbox email accepted by SMTP";
            case "FAILED" -> "Sandbox email failed";
            default -> "Sandbox email queued";
        };
    }
}
