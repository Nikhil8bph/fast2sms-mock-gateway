package com.example.fast2smsmockgateway.dtos.common;

public record SandboxResource(String key, String account, String kind, String scope,
        String reference, String payload, byte[] content) {
}
