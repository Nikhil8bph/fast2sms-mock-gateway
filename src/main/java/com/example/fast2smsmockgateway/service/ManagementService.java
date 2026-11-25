package com.example.fast2smsmockgateway.service;

import com.example.fast2smsmockgateway.clients.DltExportClient;
import com.example.fast2smsmockgateway.dtos.common.SandboxResource;
import com.example.fast2smsmockgateway.dtos.request.GatewayRequest;
import com.example.fast2smsmockgateway.exception.GatewayException;
import com.example.fast2smsmockgateway.validation.Fields;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Transactional
public class ManagementService {
    private static final String WABA = "100000000000001";
    private static final String PHONE = "100000000000002";
    private static final String NUMBER = "919000000001";
    private static final String TEMPLATE = "20005";
    private static final Map<String, Object> PHONE_PAGING =
            Map.of("cursors", Map.of("before", PHONE, "after", PHONE));

    private final ResourceStore store;
    private final CallRecorder recorder;
    private final DltExportClient dltExports;
    private final DltExportReader exportReader;
    private final ObjectReader phoneFilters = JsonMapper.builder().enable(JsonReadFeature.ALLOW_SINGLE_QUOTES).build()
            .readerFor(new TypeReference<List<Map<String, Object>>>() {});

    public ManagementService(ResourceStore store, CallRecorder recorder,
            DltExportClient dltExports, DltExportReader exportReader) {
        this.store = store;
        this.recorder = recorder;
        this.dltExports = dltExports;
        this.exportReader = exportReader;
    }

    public Map<String, Object> dltList(String account, GatewayRequest request) {
        String type = new Fields(request.fields()).get("type");
        if (type.isBlank()) {
            type = "sender";
        }
        if (!Set.of("sender", "template").contains(type)) {
            throw GatewayException.badRequest(400, "Invalid DLT listing type");
        }
        recorder.audit(account, "DLT list", Map.of("type", type));
        return Map.of("success", true, "data", list(account, "dlt-" + type, ""));
    }

    public Map<String, Object> dltAdd(String account, String kind, GatewayRequest request) {
        Fields fields = new Fields(request.fields());
        fields.require("entity_id");
        String reference = switch (kind) {
            case "entity" -> fields.get("entity_id");
            case "sender" -> fields.require("sender_id");
            case "template" -> fields.require("template_id");
            default -> throw GatewayException.badRequest(400, "Invalid DLT resource");
        };
        Map<String, Object> data = new LinkedHashMap<>(request.fields());
        if ("template".equals(kind)) {
            fields.require("message_text");
        }
        saveDlt(account, kind, reference, data);
        recorder.audit(account, "DLT add " + kind, request.fields());
        return Map.of("success", true, "data", data);
    }

    public Map<String, Object> dltImport(String account, String kind, GatewayRequest request, byte[] upload) {
        Fields fields = new Fields(request.fields());
        String entityId = fields.require("entity_id");
        boolean templates = "template".equals(kind);
        String source = upload == null ? fields.require(templates ? "content_excel" : "sender_excel") : "upload";
        if (upload == null) {
            validateHttpUrl(source, "Invalid Excel URL");
        }
        byte[] contents = upload != null ? upload : dltExports.fetch(source).orElse(null);
        int imported = 0;
        if (contents != null) {
            for (Map<String, Object> row : exportReader.read(contents, templates)) {
                row.put("entity_id", entityId);
                row.put("status", "APPROVED");
                String sender = (String) row.get("sender_id");
                if (templates && store.find(account, "dlt-sender", "", sender).isEmpty()) {
                    saveDlt(account, "sender", sender, new LinkedHashMap<>(Map.of(
                            "entity_id", entityId, "sender_id", sender, "status", "APPROVED")));
                }
                String reference = templates ? (String) row.get("template_id") : sender;
                if (store.find(account, "dlt-" + kind, "", reference).isEmpty()) {
                    saveDlt(account, kind, reference, row);
                    imported++;
                }
            }
        }
        String id = store.numericId();
        Map<String, Object> data = Map.of("id", id, "entity_id", entityId, "source", source,
                "status", contents == null ? "recorded" : "imported", "imported", imported);
        store.save(account, "dlt-import", kind, id, data, contents);
        recorder.audit(account, "DLT Excel " + kind + " import", data);
        return Map.of("success", true, "data", data);
    }

    private void saveDlt(String account, String kind, String reference, Map<String, Object> data) {
        if ("template".equals(kind)) {
            Object messageId = store.find(account, "dlt-template", "", reference)
                    .map(store::data).map(existing -> existing.get("message_id")).orElseGet(store::numericId);
            data.put("message_id", messageId);
            store.save(account, "dlt-message", "", messageId.toString(), data);
        }
        store.save(account, "dlt-" + kind, "", identifier(reference), data);
    }

    public Map<String, Object> wabaDetails(String account, GatewayRequest request) {
        Fields fields = new Fields(request.fields());
        String type = fields.get("type");
        if (!type.isBlank() && !Set.of("template", "number").contains(type)) {
            throw GatewayException.badRequest(400, "Invalid WABA listing type");
        }
        Map<String, Object> phone = new LinkedHashMap<>(phoneDetails());
        phone.put("waba_id", WABA);
        phone.put("phone_number_id", PHONE);
        phone.put("number", NUMBER);
        phone.put("messaging_limit", "TIER_100K");
        phone.put("connection_status", "CONNECTED");
        if ("template".equals(type)) {
            phone.put("templates", templates(account, WABA).stream().map(template -> Map.of(
                    "message_id", template.get("message_id"), "template_id", template.get("id"),
                    "template_name", template.get("name"), "phone_number_id", PHONE, "sender_number", NUMBER,
                    "status", "Approved", "category", template.get("category"), "language", template.get("language"),
                    "components", template.getOrDefault("components", List.of()))).toList());
        }
        recorder.audit(account, "WhatsApp WABA details", request.fields());
        String phoneId = fields.get("phone_number_id");
        return Map.of("success", true, "data", phoneId.isBlank() || PHONE.equals(phoneId) ? List.of(phone) : List.of());
    }

    public Map<String, Object> phoneNumbers(String account, String wabaId, GatewayRequest request) {
        Fields query = new Fields(request.fields());
        var phone = phoneDetails();
        boolean matches = WABA.equals(wabaId) && phoneMatches(phone, query.get("filtering"))
                && query.get("after").isBlank() && query.get("before").isBlank();
        recorder.audit(account, "WhatsApp phone numbers", Map.of("waba_id", wabaId, "filters", request.fields()));
        if (!matches) {
            return Map.of("data", List.of());
        }
        return Map.of("data", List.of(project(phone, query.get("fields"))), "paging", PHONE_PAGING);
    }

    public Map<String, Object> object(String account, String id, GatewayRequest request) {
        Map<String, Object> result = switch (id) {
            case PHONE -> phoneDetails();
            case WABA -> Map.of("id", WABA, "name", "Sandbox Business",
                    "health_status", Map.of("can_send_message", "AVAILABLE", "entities",
                            List.of(Map.of("entity_type", "WABA", "id", WABA, "can_send_message", "AVAILABLE"))));
            case TEMPLATE -> demoTemplate();
            default -> store.findByReference(account, "wa-template", id).map(store::data)
                    .orElseThrow(() -> GatewayException.missing("WhatsApp resource not found"));
        };
        recorder.audit(account, "WhatsApp resource lookup", Map.of("id", id, "fields", request.fields()));
        Map<String, Object> selected = new LinkedHashMap<>(project(result, new Fields(request.fields()).get("fields")));
        selected.put("id", id);
        return selected;
    }

    public Map<String, Object> profile(String account, String phoneId, GatewayRequest request,
            boolean update, String mediaBase) {
        var current = update ? store.lock(account, "wa-profile", "", phoneId)
                : store.find(account, "wa-profile", "", phoneId);
        Map<String, Object> profile = new LinkedHashMap<>(current.map(store::data)
                .orElseGet(() -> Map.of("messaging_product", "whatsapp",
                        "about", "Sandbox business", "vertical", "OTHER")));
        recorder.audit(account, update ? "WhatsApp profile update" : "WhatsApp profile lookup",
                Map.of("phone_number_id", phoneId, "fields", request.fields()));
        if (update) {
            profile.putAll(request.fields());
            store.save(account, "wa-profile", "", identifier(phoneId), profile);
            return Map.of("success", true);
        }
        Object pictureHandle = profile.remove("profile_picture_handle");
        if (pictureHandle instanceof String handle && handle.startsWith("2:")) {
            try {
                String mediaId = new String(java.util.Base64.getUrlDecoder().decode(handle.substring(2)),
                        java.nio.charset.StandardCharsets.UTF_8);
                if (mediaId.matches("[0-9]{1,20}") && store.find(account, "wa-media", phoneId, mediaId).isPresent()) {
                    profile.put("profile_picture_url", mediaBase + mediaId + "/file");
                }
            } catch (IllegalArgumentException ignored) {
                // External or invalid handles do not resolve to a sandbox upload.
            }
        }
        return Map.of("data", List.of(project(profile, new Fields(request.fields()).get("fields"))));
    }

    public Map<String, Object> listTemplates(String account, String wabaId, GatewayRequest request) {
        Fields fields = new Fields(request.fields());
        String name = fields.get("name");
        int limit = fields.integer("limit", 25, 1, 100);
        List<Map<String, Object>> matches = templates(account, wabaId).stream()
                .filter(template -> name.isBlank() || name.equals(template.get("name"))).toList();
        String before = fields.get("before");
        String after = fields.get("after");
        if (!before.isBlank() && !after.isBlank()) {
            throw GatewayException.badRequest(400, "Only one template cursor may be used");
        }
        int start = 0;
        int end = Math.min(limit, matches.size());
        if (!before.isBlank()) {
            end = cursorIndex(matches, before);
            start = Math.max(0, end - limit);
        } else if (!after.isBlank()) {
            start = cursorIndex(matches, after) + 1;
            end = Math.min(matches.size(), start + limit);
        }
        var page = matches.subList(start, end);
        recorder.audit(account, "WhatsApp templates lookup", Map.of("waba_id", wabaId, "filters", request.fields()));
        if (page.isEmpty() || !name.isBlank()) {
            return Map.of("data", page);
        }
        return Map.of("data", page, "paging", Map.of("cursors",
                Map.of("before", page.getFirst().get("id"), "after", page.getLast().get("id"))));
    }

    private int cursorIndex(List<Map<String, Object>> matches, String cursor) {
        for (int index = 0; index < matches.size(); index++) {
            if (cursor.equals(matches.get(index).get("id"))) {
                return index;
            }
        }
        throw GatewayException.badRequest(400, "Invalid template pagination cursor");
    }

    public Map<String, Object> createTemplate(String account, String wabaId, GatewayRequest request) {
        Fields fields = new Fields(request.fields());
        String id = store.numericId();
        String name = fields.require("name");
        fields.require("language");
        String category = fields.require("category");
        fields.objects("components");
        Map<String, Object> template = new LinkedHashMap<>(request.fields());
        template.put("id", id);
        template.put("name", name);
        template.put("status", "APPROVED");
        template.put("message_id", Integer.toUnsignedLong(id.hashCode()));
        store.save(account, "wa-template", identifier(wabaId), id, template);
        recorder.audit(account, "WhatsApp template create", Map.of("waba_id", wabaId, "template", template));
        return Map.of("id", id, "status", "APPROVED", "category", category);
    }

    public Map<String, Object> editTemplate(String account, String id, GatewayRequest request) {
        SandboxResource saved = store.findByReference(account, "wa-template", id)
                .flatMap(resource -> store.lock(account, resource.kind(), resource.scope(), resource.reference()))
                .orElseThrow(() -> GatewayException.missing("WhatsApp template not found"));
        Map<String, Object> template = new LinkedHashMap<>(store.data(saved));
        for (String field : List.of("category", "components", "parameter_format")) {
            if (request.fields().containsKey(field)) {
                template.put(field, request.fields().get(field));
            }
        }
        store.save(account, "wa-template", saved.scope(), id, template);
        recorder.audit(account, "WhatsApp template edit", Map.of("id", id, "template", template));
        return Map.of("success", true);
    }

    public Map<String, Object> deleteTemplate(String account, String wabaId, GatewayRequest request) {
        Fields fields = new Fields(request.fields());
        String name = fields.get("name");
        String id = fields.get("hsm_id");
        if (name.isBlank() && id.isBlank()) {
            throw GatewayException.badRequest(400, "name or hsm_id is required");
        }
        List<SandboxResource> matches = store.list(account, "wa-template", wabaId).stream()
                .filter(item -> id.isBlank() || id.equals(item.reference()))
                .filter(item -> name.isBlank() || name.equals(store.data(item).get("name"))).toList();
        if (matches.isEmpty()) {
            throw GatewayException.missing("WhatsApp template not found");
        }
        matches.forEach(store::delete);
        recorder.audit(account, "WhatsApp template delete", Map.of("waba_id", wabaId, "filters", request.fields()));
        return Map.of("success", true);
    }

    public Map<String, Object> webhooks(String account, String service) {
        recorder.audit(account, "List " + service + " webhooks", Map.of());
        return Map.of("return", true, "data", activeWebhooks(account, service));
    }

    public Map<String, Object> saveWebhook(String account, String service, String id, GatewayRequest request) {
        boolean creating = id == null;
        if (creating) {
            store.reserve(account, "webhook-limit", service, "");
            if (activeWebhooks(account, service).size() >= 10) {
                throw GatewayException.badRequest(400, "Maximum 10 webhooks allowed for " + service);
            }
            id = "wh_" + store.numericId();
        } else {
            existingWebhook(account, service, id);
        }
        Map<String, Object> data = new LinkedHashMap<>(Map.of(
                "status", "active", "event", "all", "method", "POST", "content_type", "application/json",
                "template", "custom", "payload_type", "SIMPLIFIED", "payload_transform", "standard"));
        data.putAll(request.fields());
        data.remove("deleted_at");
        data.put("id", id);
        data.put("service", service);
        Fields fields = new Fields(data);
        fields.require("name");
        validateHttpUrl(fields.require("url"), "Invalid webhook URL");
        if (!Set.of("POST", "GET", "PUT").contains(fields.get("method"))
                || !Set.of("active", "inactive").contains(fields.get("status"))) {
            throw GatewayException.badRequest(400, "Invalid webhook method or status");
        }
        store.save(account, "webhook", service, id, data);
        recorder.audit(account, "Save " + service + " webhook", Map.of("id", id, "name", fields.get("name")));
        return Map.of("return", true,
                "message", creating ? "Webhook created successfully" : "Webhook updated successfully", "data", data);
    }

    public Map<String, Object> deleteWebhook(String account, String service, String id) {
        Map<String, Object> data = existingWebhook(account, service, id);
        data.put("deleted_at", Instant.now().toString());
        store.save(account, "webhook", service, id, data);
        recorder.audit(account, "Delete " + service + " webhook", Map.of("id", id));
        return Map.of("return", true, "message", "Webhook deleted successfully");
    }

    private List<Map<String, Object>> activeWebhooks(String account, String service) {
        return list(account, "webhook", service).stream().filter(data -> !data.containsKey("deleted_at")).toList();
    }

    private Map<String, Object> existingWebhook(String account, String service, String id) {
        return store.lock(account, "webhook", service, id).map(store::data)
                .filter(data -> !data.containsKey("deleted_at"))
                .orElseThrow(() -> GatewayException.missing("Webhook not found"));
    }

    private List<Map<String, Object>> templates(String account, String wabaId) {
        List<Map<String, Object>> templates = new ArrayList<>(list(account, "wa-template", wabaId));
        if (WABA.equals(wabaId)) {
            templates.addFirst(demoTemplate());
        }
        return templates;
    }

    private Map<String, Object> demoTemplate() {
        return Map.of("id", TEMPLATE, "message_id", 20005, "name", "sandbox_template", "status", "APPROVED",
                "category", "UTILITY", "language", "en", "components",
                List.of(Map.of("type", "BODY", "text", "Sandbox message")));
    }

    private Map<String, Object> phoneDetails() {
        return Map.of("id", PHONE, "verified_name", "Sandbox Business", "display_phone_number", "+" + NUMBER,
                "quality_rating", "GREEN", "name_status", "APPROVED", "platform_type", "CLOUD_API",
                "code_verification_status", "VERIFIED", "is_official_business_account", false,
                "account_mode", "SANDBOX");
    }

    private Map<String, Object> project(Map<String, Object> data, String fields) {
        if (fields.isBlank()) {
            return data;
        }
        Map<String, Object> selected = new LinkedHashMap<>();
        Arrays.stream(fields.split(",")).map(String::trim)
                .filter(data::containsKey).forEach(field -> selected.put(field, data.get(field)));
        return selected;
    }

    private boolean phoneMatches(Map<String, Object> phone, String query) {
        if (query.isBlank()) {
            return true;
        }
        try {
            List<Map<String, Object>> filters = phoneFilters.readValue(query);
            if (filters != null && !filters.contains(null)) {
                return filters.stream().allMatch(filter -> {
                    Fields fields = new Fields(filter);
                    Object actual = phone.get(fields.require("field"));
                    return switch (fields.require("operator")) {
                        case "EQUAL" -> java.util.Objects.equals(actual, filter.get("value"));
                        case "NOT_EQUAL" -> actual != null && !java.util.Objects.equals(actual, filter.get("value"));
                        default -> throw GatewayException.badRequest(400, "Invalid phone filtering operator");
                    };
                });
            }
        } catch (JacksonException ignored) {
            // Report malformed filter syntax as a client error.
        }
        throw GatewayException.badRequest(400, "Invalid phone filtering");
    }

    private List<Map<String, Object>> list(String account, String kind, String scope) {
        return store.list(account, kind, scope).stream().map(store::data).toList();
    }

    private void validateHttpUrl(String value, String error) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() != null
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                return;
            }
        } catch (IllegalArgumentException ignored) {
            // Report the same validation message for malformed or unsupported URL syntax.
        }
        throw GatewayException.badRequest(400, error);
    }

    private String identifier(String value) {
        if (value.length() > 128 || value.isBlank()) {
            throw GatewayException.badRequest(400, "Invalid resource identifier");
        }
        return value;
    }
}
