package com.example.fast2smsmockgateway.dtos.request;

public record MediaUploadRequest(String product, String mimeType, byte[] content) {
}
