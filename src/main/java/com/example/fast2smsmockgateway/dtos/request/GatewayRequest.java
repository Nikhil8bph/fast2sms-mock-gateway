package com.example.fast2smsmockgateway.dtos.request;

import java.util.LinkedHashMap;
import java.util.Map;

public record GatewayRequest(Map<String, Object> fields) {
    public GatewayRequest {
        fields = new LinkedHashMap<>(fields);
        fields.keySet().removeIf("authorization"::equalsIgnoreCase);
    }

    public static GatewayRequest of(Map<String, String> parameters, Map<String, Object> body) {
        Map<String, Object> fields = new LinkedHashMap<>(parameters);
        if (body != null) {
            fields.putAll(body);
        }
        return new GatewayRequest(fields);
    }
}
