package com.example.fast2smsmockgateway.service;

import com.example.fast2smsmockgateway.config.SandboxProperties;
import com.example.fast2smsmockgateway.dtos.common.ApiCall;
import com.example.fast2smsmockgateway.exception.GatewayException;
import com.example.fast2smsmockgateway.facade.GatewayFacade;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

@Service
public class CallRecorder {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ID_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private final GatewayFacade facade;
    private final SandboxProperties properties;
    private final JsonMapper json;

    public CallRecorder(GatewayFacade facade, SandboxProperties properties, JsonMapper json) {
        this.facade = facade;
        this.properties = properties;
        this.json = json;
    }

    public ApiCall enqueue(String account, String channel, String operation,
            Map<String, Object> fields, List<String> numbers, Instant dueAt) {
        String id = requestId(channel);
        return persist(id, id, account, channel, operation, fields, numbers, dueAt);
    }

    public ApiCall enqueueBatch(String requestId, String account, Map<String, Object> fields,
            List<String> numbers, Instant dueAt) {
        return persist(requestId("SMS"), requestId, account, "SMS", "SMS batch", fields, numbers, dueAt);
    }

    public String requestId(String channel) {
        StringBuilder id = new StringBuilder(15).append(channel.charAt(0));
        for (int index = 1; index < 15; index++) {
            id.append(ID_CHARACTERS.charAt(RANDOM.nextInt(ID_CHARACTERS.length())));
        }
        return id.toString();
    }

    private ApiCall persist(String id, String requestId, String account, String channel, String operation,
            Map<String, Object> fields, List<String> numbers, Instant dueAt) {
        return facade.enqueue(new ApiCall(id, requestId, account, channel, operation,
                encode(fields, "VOICE".equals(channel) ? 2_000_000 : 60_000),
                String.join(",", numbers), properties.emailFrom(), properties.emailTo(), Instant.now(), dueAt,
                "PENDING", 0, null, null, null));
    }

    public void audit(String account, String operation, Map<String, Object> fields) {
        enqueue(account, "AUDIT", operation, fields, List.of(), Instant.now());
    }

    public String encode(Map<String, Object> fields) {
        return encode(fields, 60_000);
    }

    private String encode(Map<String, Object> fields, int limit) {
        String payload = json.writeValueAsString(fields);
        if (payload.getBytes(StandardCharsets.UTF_8).length > limit) {
            throw GatewayException.badRequest(400, "Request exceeds the sandbox payload limit");
        }
        return payload;
    }

    public Map<String, Object> decode(String payload) {
        return json.readValue(payload, new TypeReference<Map<String, Object>>() {});
    }
}
