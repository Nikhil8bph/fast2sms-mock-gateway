package com.example.fast2smsmockgateway.service;

import com.example.fast2smsmockgateway.dtos.common.ApiCall;
import com.example.fast2smsmockgateway.dtos.request.GatewayRequest;
import com.example.fast2smsmockgateway.dtos.response.SendResponse;
import com.example.fast2smsmockgateway.exception.GatewayException;
import com.example.fast2smsmockgateway.util.SmsParts;
import com.example.fast2smsmockgateway.validation.Fields;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MessageService {
    private static final Pattern DLT_VARIABLE = Pattern.compile("\\{#var#}");
    private static final Set<String> SMS_ROUTES = Set.of("q", "dlt", "dlt_manual", "otp");
    private static final Set<String> WHATSAPP_TYPES = Set.of("text", "template", "image", "video", "audio",
            "document", "sticker", "location", "reaction", "interactive", "contacts", "catalogue",
            "catalog", "product", "product_list", "carousel");
    private final CallRecorder recorder;
    private final ResourceStore store;

    public MessageService(CallRecorder recorder, ResourceStore store) {
        this.recorder = recorder;
        this.store = store;
    }

    @Transactional
    public SendResponse sms(String account, GatewayRequest request, boolean custom) {
        Fields fields = new Fields(request.fields());
        String route = fields.require("route", 403, "Route Missing");
        if (!SMS_ROUTES.contains(route) || (custom && "otp".equals(route))) {
            throw GatewayException.badRequest(408, "Invalid Route");
        }
        Instant now = Instant.now();
        Instant schedule = fields.schedule(now);
        if (custom) {
            return batch(account, request, fields, route, now, schedule);
        }
        validateSms(fields, route, false);
        var numbers = fields.smsNumbers();
        var payload = smsPayload(account, route, fields);
        ApiCall saved = recorder.enqueue(account, "SMS", "SMS", payload, numbers.accepted(), schedule);
        Map<String, Object> details = "1".equals(fields.get("sms_details"))
                ? smsDetails(route, new Fields(payload), numbers) : null;
        return smsResponse(saved.requestId(), details, schedule.isAfter(now));
    }

    private SendResponse batch(String account, GatewayRequest request, Fields fields, String route,
            Instant now, Instant defaultSchedule) {
        String requestId = recorder.requestId("SMS");
        Map<Instant, List<Fields>> groups = new LinkedHashMap<>();
        for (Fields item : fields.objects("requests")) {
            Instant scheduled = item.values().containsKey("schedule_time") ? item.schedule(now) : defaultSchedule;
            groups.computeIfAbsent(scheduled, ignored -> new ArrayList<>()).add(item);
        }
        int count = 0;
        for (var group : groups.entrySet()) {
            List<Fields> items = group.getValue();
            items.forEach(item -> validateSms(item, route, true));
            List<String> numbers = items.stream().flatMap(item -> item.smsNumbers().accepted().stream()).toList();
            Map<String, Object> payload = new LinkedHashMap<>(request.fields());
            payload.put("requests", items.stream().map(item -> smsPayload(account, route, item)).toList());
            recorder.enqueueBatch(requestId, account, payload, numbers, group.getKey());
            count += numbers.size();
        }
        return smsResponse(requestId, details(fields, count, route), !groups.containsKey(now));
    }

    private SendResponse smsResponse(String id, Map<String, Object> details, boolean scheduled) {
        return new SendResponse(true, id, List.of(scheduled
                ? "SMS scheduled successfully." : "SMS sent successfully."), details);
    }

    private Map<String, Object> smsDetails(String route, Fields payload, Fields.NumberSelection numbers) {
        SmsParts parts = SmsParts.measure(SmsParts.text(route, payload));
        return Map.of("character_count", parts.characters(), "per_sms_rate", "0.0000", "sms_count", parts.count(),
                "sms_language", parts.language(), "total_valid_number", numbers.accepted().size(),
                "total_invalid_number", numbers.rejected().size(), "valid_numbers", numbers.accepted(),
                "invalid_numbers", numbers.rejected(), "amount_debited", "0.0000");
    }

    private Map<String, Object> smsPayload(String account, String route, Fields fields) {
        Map<String, Object> payload = new LinkedHashMap<>();
        fields.values().forEach((key, value) -> payload.put(key.toString(), value));
        if ("dlt".equals(route)) {
            findDltMessage(account, fields.get("message")).ifPresent(template -> {
                String text = new Fields(template).get("message_text");
                String values = fields.get("variables_values");
                if (!values.isBlank()) {
                    var variable = DLT_VARIABLE.matcher(text);
                    StringBuilder rendered = new StringBuilder();
                    for (String value : values.split("\\|", -1)) {
                        if (!variable.find()) {
                            break;
                        }
                        variable.appendReplacement(rendered, Matcher.quoteReplacement(value));
                    }
                    text = variable.appendTail(rendered).toString();
                }
                payload.put("rendered_message", text);
            });
        } else if ("dlt_manual".equals(route)
                && (fields.get("template_id").isBlank() || fields.get("entity_id").isBlank())) {
            addManualIds(account, fields, payload);
        }
        return payload;
    }

    private Optional<Map<String, Object>> findDltMessage(String account, String messageId) {
        return store.find(account, "dlt-message", "", messageId).map(store::data)
                .or(() -> store.list(account, "dlt-template", "").stream().map(store::data)
                        .filter(template -> messageId.equals(new Fields(template).get("message_id"))).findFirst());
    }

    private void addManualIds(String account, Fields request, Map<String, Object> payload) {
        String templateId = request.get("template_id");
        String entityId = request.get("entity_id");
        List<Fields> matches = store.list(account, "dlt-template", "").stream()
                .map(store::data).map(Fields::new)
                .filter(template -> templateId.isBlank() || templateId.equals(template.get("template_id")))
                .filter(template -> entityId.isBlank() || entityId.equals(template.get("entity_id")))
                .filter(template -> !template.get("template_id").isBlank() && !template.get("entity_id").isBlank())
                .filter(template -> matchesSender(template, request.get("sender_id")))
                .filter(template -> matchesTemplate(template.get("message_text"), request.get("message")))
                .limit(2).toList();
        if (matches.size() != 1) {
            throw GatewayException.badRequest(425, "Provide entity_id and template_id or use a matching saved DLT template");
        }
        payload.put("template_id", matches.getFirst().get("template_id"));
        payload.put("entity_id", matches.getFirst().get("entity_id"));
    }

    private boolean matchesSender(Fields template, String sender) {
        return switch (template.values().get("sender_id")) {
            case List<?> senders -> senders.contains(sender);
            case String senders -> Arrays.stream(senders.split(",")).map(String::trim).anyMatch(sender::equals);
            default -> false;
        };
    }

    private boolean matchesTemplate(String template, String text) {
        String[] literals = DLT_VARIABLE.split(template, -1);
        if (literals.length == 1) {
            return template.equals(text);
        }
        String first = literals[0];
        String last = literals[literals.length - 1];
        int suffixStart = text.length() - last.length();
        int position = first.length();
        if (position > suffixStart || !text.startsWith(first) || !text.endsWith(last)) {
            return false;
        }
        for (int index = 1; index < literals.length - 1; index++) {
            String literal = literals[index];
            int match = text.indexOf(literal, position);
            if (match < 0 || match + literal.length() > suffixStart) {
                return false;
            }
            position = match + literal.length();
        }
        return true;
    }

    private void validateSms(Fields fields, String route, boolean custom) {
        if (route.startsWith("dlt")) {
            fields.require("sender_id", 401, "Sender ID Missing");
        }
        if ("otp".equals(route)) {
            fields.require("variables_values", 402, "Message Text Missing");
        } else {
            String message = fields.require("message", 402, "Message Text Missing");
            if ("dlt".equals(route) && !message.matches("[0-9]+")) {
                throw GatewayException.badRequest(424, "Invalid Message ID");
            }
        }
        if (custom && "dlt_manual".equals(route)) {
            fields.require("entity_id", 425, "Invalid Template");
            fields.require("template_id", 425, "Invalid Template");
        }
    }

    @Transactional
    public Object whatsapp(String account, GatewayRequest request, String operation,
            String version, String phoneId) {
        Fields fields = new Fields(request.fields());
        Map<String, Object> payload = new LinkedHashMap<>(request.fields());
        List<String> numbers;
        switch (operation) {
            case "WhatsApp template" -> {
                if (!fields.require("message_id").matches("[0-9]+")) {
                    throw GatewayException.badField("message_id", 424, "Invalid WhatsApp template message ID");
                }
                numbers = fields.numbers("numbers");
            }
            case "WhatsApp Direct Send" -> {
                fields.require("phone_number_id");
                if (!"utility".equalsIgnoreCase(fields.require("category")) || fields.require("text").length() > 1024) {
                    throw GatewayException.badRequest(400, "Direct Send requires utility text up to 1024 characters");
                }
                numbers = fields.numbers("numbers");
            }
            default -> {
                boolean meta = phoneId != null;
                if (meta) {
                    if (!"whatsapp".equals(fields.require("messaging_product"))) {
                        throw GatewayException.badRequest(400, "messaging_product must be whatsapp");
                    }
                    payload.put("version", version);
                    payload.put("phone_number_id", phoneId);
                } else if (fields.get("phone_number_id").isBlank()) {
                    fields.require("display_number");
                }
                String type = !meta && fields.get("type").isBlank() ? "text" : fields.require("type");
                if (!WHATSAPP_TYPES.contains(type)) {
                    throw GatewayException.badRequest(400, "Invalid WhatsApp message type");
                }
                if (meta || fields.values().get(type) instanceof Map<?, ?>) {
                    Fields content = fields.object(type);
                    if ("text".equals(type)) {
                        content.require("body");
                    } else if ("template".equals(type)) {
                        content.require("name");
                        content.object("language").require("code");
                    }
                } else {
                    validateSimpleSession(fields, type);
                }
                String to = fields.require("to");
                numbers = List.of(meta ? fields.internationalMobile(to) : fields.indianMobile(to));
            }
        }
        Instant now = Instant.now();
        Instant schedule = fields.schedule(now);
        ApiCall saved = recorder.enqueue(account, "WHATSAPP", operation, payload, numbers, schedule);
        if (phoneId != null) {
            return Map.of("messaging_product", "whatsapp",
                    "contacts", List.of(Map.of("input", fields.get("to"), "wa_id", numbers.getFirst())),
                    "messages", List.of(Map.of("id", "wamid." + saved.id(), "message_status", "accepted")));
        }
        return new SendResponse(true, saved.id(), List.of(schedule.isAfter(now)
                ? "Whatsapp Message scheduled successfully." : "Whatsapp Message sent successfully."),
                details(fields, numbers.size(), operation));
    }

    private void validateSimpleSession(Fields fields, String type) {
        switch (type) {
            case "text" -> fields.require("text");
            case "image", "video", "audio", "sticker" -> fields.require("url");
            case "document" -> {
                fields.require("url");
                fields.require("document_filename");
            }
            case "location" -> {
                fields.require("latitude");
                fields.require("longitude");
            }
            case "reaction" -> {
                fields.require("message_id");
                if (!fields.values().containsKey("emoji")) {
                    throw GatewayException.badRequest(400, "emoji is required");
                }
            }
            default -> fields.object(type);
        }
    }

    private Map<String, Object> details(Fields fields, int count, String category) {
        return "1".equals(fields.get("sms_details"))
                ? Map.of("count", count, "rate", 0, "category", category) : null;
    }
}
