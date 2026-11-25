package com.example.fast2smsmockgateway.dtos.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public record FieldErrorResponse(@JsonProperty("return") boolean accepted,
        @JsonProperty("errors_keys") List<String> errorKeys, Map<String, List<String>> errors) {
}
