package com.example.fast2smsmockgateway.dtos.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ErrorResponse(@JsonProperty("return") boolean accepted,
        @JsonProperty("status_code") int statusCode, String message) {
}
